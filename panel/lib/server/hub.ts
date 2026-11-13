import { EventEmitter } from 'node:events';
import type { IncomingMessage } from 'node:http';
import type { Duplex } from 'node:stream';
import { WebSocket, WebSocketServer } from 'ws';
import { roleOf, serverForRelayToken } from './access';
import { ArchiveError, commit, uploadUrls } from './archive';
import { AssetGrants, assetRequest } from './assetRelay';
import { AssetError } from './assets';
import { exec } from './db';
import { linkDeps } from './minecraftLinkStore';
import { AttemptLimit, failure, linkPlayer, playerAssetRequest, unlinkPlayer } from './minecraftLinks';
import { configured as r2Configured, downloadUrl } from './r2';
import { atLeast } from './roles';
import { bearer } from './tokens';

/**
 * Accepts the Minecraft servers' relay sockets (/relay) and forwards requests to them. Frames are those of
 * plugin/common/.../RelayProtocol.java; keep the two in step.
 */
export const RELAY = {
  PATH: '/relay',
  VERSION: 2,
  CLOSE_BAD_TOKEN: 4001,
  CLOSE_BAD_PROTOCOL: 4002,
  CLOSE_REPLACED: 4003,
} as const;

export type Json = Record<string, any>;
export interface Reply { status: number; body: unknown }

// server.ts and Next's route bundles each load their own copy of this module, so callers tell these errors apart
// by `code`, never with instanceof.
export class OfflineError extends Error {
  readonly code = 'OFFLINE';
  constructor(serverId: string) {
    super(`server '${serverId}' is not connected`);
  }
}

export class TimeoutError extends Error {
  readonly code = 'TIMEOUT';
}

const RECENT = 50;
/** The plugin pings every few seconds; a socket quiet for this long is dead. */
const IDLE_MS = 120_000;

/** What the dashboard shows about one server while the panel runs: online state, versions, jobs, open drafts. */
class ServerState {
  online = false;
  since = 0;
  info: Json = {};
  running = new Map<string, Json>();
  drafts = new Map<string, Json>();
  recent: Json[] = [];

  connected(hello: Json) {
    const { t: _t, ...info } = hello;
    this.online = true;
    this.info = info;
    this.since = Date.now();
    this.running.clear(); // the plugin re-sends job.start for whatever still runs
    this.drafts.clear(); // and draft.update for every open draft
  }

  disconnected() {
    this.online = false;
    this.since = Date.now();
    for (const job of this.running.values()) this.remember({ ...job, status: 'unknown' });
    this.running.clear();
  }

  job(event: string, job: Json) {
    const id = String(job.id ?? '?');
    if (event === 'job.end') {
      this.running.delete(id);
      this.remember(job);
    } else {
      this.running.set(id, job);
    }
  }

  draft(summary: Json) {
    if (summary.status === 'open') this.drafts.set(String(summary.id), summary);
    else this.drafts.delete(String(summary.id));
  }

  private remember(job: Json) {
    this.recent = [job, ...this.recent.filter(j => j.id !== job.id)].slice(0, RECENT);
  }

  toJson() {
    return {
      online: this.online,
      since: this.since,
      info: this.info,
      running: [...this.running.values()],
      recent: this.recent,
      drafts: [...this.drafts.values()],
    };
  }
}

class Connection {
  readonly assets = new AssetGrants();
  ready = false;
  lastHeard = Date.now();
  private nextId = 1;
  private pending = new Map<number, { resolve: (r: Json) => void; reject: (e: Error) => void; timer: NodeJS.Timeout }>();

  readonly serverId: string;
  readonly socket: WebSocket;

  // Plain fields, not parameter properties: Node's strip-only TypeScript runs this file in the tests.
  constructor(serverId: string, socket: WebSocket) {
    this.serverId = serverId;
    this.socket = socket;
  }

  request(frame: Json, timeoutMs: number): Promise<Json> {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new TimeoutError(`server '${this.serverId}' did not answer in ${Math.round(timeoutMs / 1000)}s`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      this.socket.send(JSON.stringify({ ...frame, id }), err => {
        if (!err) return;
        clearTimeout(timer);
        this.pending.delete(id);
        reject(new OfflineError(this.serverId));
      });
    });
  }

  complete(id: number, reply: Json) {
    const p = this.pending.get(id);
    if (!p) return;
    clearTimeout(p.timer);
    this.pending.delete(id);
    p.resolve(reply);
  }

  failAll() {
    this.assets.clear();
    for (const p of this.pending.values()) {
      clearTimeout(p.timer);
      p.reject(new OfflineError(this.serverId));
    }
    this.pending.clear();
  }

  close(code: number, reason: string) {
    try {
      this.socket.close(code, reason);
    } catch {
      // already closed
    }
  }
}

export type HubEvent =
  | { type: 'server'; server: string; state: ReturnType<ServerState['toJson']> }
  | { type: 'job'; server: string; event: string; job: Json }
  | { type: 'draft'; server: string; draft: Json }
  /** A history entry was archived. */
  | { type: 'history'; server: string; entry: string }
  /** Memberships, names or servers changed for these users: they need a fresh snapshot. */
  | { type: 'access'; users: number[] };

export class RelayHub {
  private readonly wss = new WebSocketServer({ noServer: true, maxPayload: 64 * 1024 * 1024 });
  private readonly live = new Map<string, Connection>();
  private readonly states = new Map<string, ServerState>();
  /** Link attempts per server, so a server's operator cannot guess codes; kept across reconnects. */
  private readonly linkAttempts = new Map<string, AttemptLimit>();
  private readonly emitter = new EventEmitter();
  private readonly sweeper: NodeJS.Timeout;

  constructor() {
    this.emitter.setMaxListeners(0);
    this.sweeper = setInterval(() => this.sweep(), 30_000);
    this.sweeper.unref();
  }

  /** Takes over an HTTP upgrade to /relay: checks the server token, then accepts the socket. */
  async upgrade(req: IncomingMessage, socket: Duplex, head: Buffer) {
    const token = bearer(req.headers.authorization);
    let serverId: string | null = null;
    try {
      serverId = token ? await serverForRelayToken(token) : null;
    } catch (e) {
      console.error('relay: token lookup failed', e);
      socket.end('HTTP/1.1 503 Service Unavailable\r\n\r\n');
      return;
    }
    this.wss.handleUpgrade(req, socket, head, ws => {
      if (!serverId) {
        console.warn(`relay: connection from ${req.socket.remoteAddress} with an unknown token`);
        ws.close(RELAY.CLOSE_BAD_TOKEN, 'unknown relay token');
        return;
      }
      this.accept(serverId, ws);
    });
  }

  private accept(serverId: string, ws: WebSocket) {
    const c = new Connection(serverId, ws);
    ws.on('ping', () => { c.lastHeard = Date.now(); });
    ws.on('pong', () => { c.lastHeard = Date.now(); });
    ws.on('message', (data, binary) => {
      c.lastHeard = Date.now();
      if (binary) return;
      let frame: Json;
      try {
        frame = JSON.parse(data.toString());
      } catch {
        console.warn(`relay: server ${serverId} sent a frame that is not JSON`);
        return;
      }
      this.onFrame(c, frame);
    });
    ws.on('close', (code, reason) => this.onClose(c, code, reason.toString()));
    ws.on('error', e => console.debug(`relay: socket error for ${serverId}`, e.message));
  }

  private onFrame(c: Connection, frame: Json) {
    switch (frame.t) {
      case 'hello':
        return this.hello(c, frame);
      case 'reply':
        if (c.ready && typeof frame.id === 'number') c.complete(frame.id, frame);
        return;
      case 'upload':
      case 'uploaded':
        if (c.ready) this.archive(c, frame);
        return;
      case 'asset':
        if (c.ready && this.live.get(c.serverId) === c) this.asset(c, frame);
        return;
      case 'link':
      case 'unlink':
      case 'player_asset':
        if (c.ready && this.live.get(c.serverId) === c) this.player(c, frame);
        return;
      case 'event': {
        if (!c.ready) return;
        const s = this.state(c.serverId);
        if (frame.event === 'draft.update') {
          s.draft(frame.draft);
          this.emit({ type: 'draft', server: c.serverId, draft: frame.draft });
        } else {
          s.job(frame.event, frame.job);
          this.emit({ type: 'job', server: c.serverId, event: frame.event, job: frame.job });
        }
        return;
      }
      default:
        console.warn(`relay: server ${c.serverId} sent an unknown frame type '${frame.t}'`);
    }
  }

  private hello(c: Connection, frame: Json) {
    const protocol = typeof frame.protocol === 'number' ? frame.protocol : -1;
    if (protocol !== RELAY.VERSION) {
      const update = protocol < RELAY.VERSION ? 'update the AIBuilder plugin' : 'update the panel';
      c.close(RELAY.CLOSE_BAD_PROTOCOL, `panel protocol ${RELAY.VERSION}, plugin ${protocol}; ${update}`);
      return;
    }
    c.ready = true;
    const old = this.live.get(c.serverId);
    this.live.set(c.serverId, c);
    if (old && old !== c) {
      old.failAll();
      old.close(RELAY.CLOSE_REPLACED, 'replaced by a newer connection');
    }
    const s = this.state(c.serverId);
    s.connected(frame);
    console.info(`relay: server ${c.serverId} connected (${frame.mc ?? '?'} ${frame.bridge ?? '?'}, ${frame.plugin ?? '?'})`);
    // History goes to R2 when it is configured: the plugin then uploads each entry (see archive.ts).
    // links: this panel takes link, unlink and player_asset frames (/aibuilder link, asset save and asset list in game).
    c.socket.send(JSON.stringify({ t: 'welcome', archive: r2Configured() && frame.history === true, assets: r2Configured(), links: true }));
    exec('UPDATE servers SET info = ?, last_seen_at = NOW() WHERE id = ?', [JSON.stringify(s.info), c.serverId])
      .catch(e => console.warn('relay: could not save server info', e.message));
    this.emit({ type: 'server', server: c.serverId, state: s.toJson() });
  }

  /** A plugin's upload request or confirmation; answered with {"t":"answer"} under the same id. */
  private archive(c: Connection, frame: Json) {
    const answer = (status: number, body: unknown) => {
      if (c.socket.readyState === WebSocket.OPEN) c.socket.send(JSON.stringify({ t: 'answer', id: frame.id, status, body }));
    };
    if (typeof frame.id !== 'number') return;
    if (frame.kind !== 'history') return answer(400, { error: `unknown kind '${frame.kind}'` });
    const work = frame.t === 'upload'
      ? uploadUrls(c.serverId, frame.entry, frame.files)
      : commit(c.serverId, frame.entry, frame.detail, frame.files).then(() => {
        this.emit({ type: 'history', server: c.serverId, entry: String(frame.entry) });
        return {};
      });
    work.then(body => answer(200, body), e => {
      if (e instanceof ArchiveError) return answer(e.status, { error: e.message });
      console.warn(`archive: ${frame.t} ${frame.entry} from ${c.serverId} failed`, e);
      answer(500, { error: 'the panel could not store it; try again later' });
    });
  }

  /** Authorize against this socket's active request, never frame.user; issue only short-lived object URLs. */
  private async asset(c: Connection, frame: Json) {
    if (typeof frame.id !== 'number') return;
    let status = 200, body: unknown;
    try {
      const user = c.assets.owner(frame.assetKey);
      if (!r2Configured()) throw new AssetError(503, 'the panel asset library needs object storage configured');
      // Lazy loading avoids the storage adapter's hub/route import cycle during process startup.
      const { store, files } = await import('./assetStore');
      body = await assetRequest({
        store, files,
        async requireRole(server, needed) {
          c.assets.owner(frame.assetKey);
          const role = await roleOf(user, server);
          if (!role || !atLeast(role, needed)) throw new AssetError(403, 'asset access needs builder membership on this server');
        },
        relay: async () => { throw new Error('nested export not supported'); },
        mcVersion: async () => null,
      }, user, c.serverId, this.state(c.serverId).info.mc ?? null, frame, downloadUrl);
    } catch (e) {
      status = e instanceof AssetError ? e.status : 500;
      body = { error: e instanceof AssetError ? e.message : 'the panel could not access the asset library; try again later' };
      if (!(e instanceof AssetError)) console.warn('relay asset request failed', e);
    }
    if (c.socket.readyState === WebSocket.OPEN) c.socket.send(JSON.stringify({ t: 'answer', id: frame.id, status, body }));
  }

  /**
   * A player's request from the game: link, unlink, or an in-game asset list or save (player_asset). The server is this
   * socket's, never a frame field; the account comes from the player's link (minecraftLinks.ts), not from a tool
   * call's grant, so the grant path in asset() is untouched. Link codes are credentials: never log the frame.
   */
  private async player(c: Connection, frame: Json) {
    if (typeof frame.id !== 'number') return;
    let status = 200, body: unknown;
    try {
      const deps = linkDeps();
      if (frame.t === 'link') {
        let limit = this.linkAttempts.get(c.serverId);
        if (!limit) this.linkAttempts.set(c.serverId, limit = new AttemptLimit());
        limit.take();
        body = await linkPlayer(deps, c.serverId, frame);
      } else if (frame.t === 'unlink') {
        body = await unlinkPlayer(deps, c.serverId, frame);
      } else {
        if (!r2Configured()) throw new AssetError(503, 'the panel asset library needs object storage configured');
        // Lazy loading avoids the storage adapter's hub/route import cycle during process startup.
        const { store, files } = await import('./assetStore');
        body = await playerAssetRequest(deps, user => ({
          store, files,
          async requireRole(server, needed) {
            const role = await roleOf(user, server);
            if (!role || !atLeast(role, needed)) throw new AssetError(403, 'asset access needs builder membership on this server');
          },
          relay: async () => { throw new Error('nested export not supported'); },
          mcVersion: async () => null,
        }), c.serverId, this.state(c.serverId).info.mc ?? null, frame, downloadUrl);
      }
    } catch (e) {
      const known = failure(e);
      status = known?.status ?? 500;
      body = { error: known?.error ?? 'the panel could not do that; try again later' };
      if (!known) console.warn(`relay ${frame.t} request from ${c.serverId} failed`, (e as Error)?.message ?? e);
    }
    if (c.socket.readyState === WebSocket.OPEN) c.socket.send(JSON.stringify({ t: 'answer', id: frame.id, status, body }));
  }

  private onClose(c: Connection, code: number, reason: string) {
    c.failAll();
    if (this.live.get(c.serverId) !== c) return;
    this.live.delete(c.serverId);
    const s = this.state(c.serverId);
    s.disconnected();
    exec('UPDATE servers SET last_seen_at = NOW() WHERE id = ?', [c.serverId]).catch(() => {});
    console.info(`relay: server ${c.serverId} disconnected (${code} ${reason})`);
    this.emit({ type: 'server', server: c.serverId, state: s.toJson() });
  }

  /** Drops sockets the plugin stopped talking on (it pings every few seconds). */
  private sweep() {
    const now = Date.now();
    for (const c of this.live.values()) {
      if (now - c.lastHeard > IDLE_MS) c.socket.terminate();
    }
  }

  private state(serverId: string): ServerState {
    let s = this.states.get(serverId);
    if (!s) this.states.set(serverId, s = new ServerState());
    return s;
  }

  /** The live part of a server's state; offline when it never connected since the panel started. */
  snapshot(serverId: string) {
    return this.state(serverId).toJson();
  }

  online(serverId: string): boolean {
    return this.live.has(serverId);
  }

  /** Sends a request frame (without id); resolves with the reply, or rejects with OfflineError or TimeoutError. */
  async request(serverId: string, frame: Json, timeoutMs: number): Promise<Reply> {
    const c = this.live.get(serverId);
    if (!c) throw new OfflineError(serverId);
    const reply = await c.request(frame, timeoutMs);
    return { status: typeof reply.status === 'number' ? reply.status : 502, body: reply.body ?? null };
  }

  /**
   * Identity comes from the access token, outside tool arguments. Grants die on reply, timeout or disconnect. Without
   * object storage there is no library, so the call goes as one without an account (the plugin then keeps server
   * blueprints). With too many calls open the call still runs, with the account but no grant: only asset use fails.
   */
  async requestAsUser(serverId: string, frame: Json, user: number, timeoutMs: number): Promise<Reply> {
    const c = this.live.get(serverId);
    if (!c) throw new OfflineError(serverId);
    if (!r2Configured()) return this.request(serverId, frame, timeoutMs);
    let key: string | null = null;
    try {
      key = c.assets.issue(user, timeoutMs);
    } catch (e) {
      if ((e as { status?: unknown }).status !== 429) throw e;
    }
    try {
      const reply = await c.request(key ? { ...frame, user, assetKey: key } : { ...frame, user }, timeoutMs);
      return { status: typeof reply.status === 'number' ? reply.status : 502, body: reply.body ?? null };
    } finally {
      if (key) c.assets.revoke(key);
    }
  }

  /** Closes a server's socket and forgets its state: the server was deleted or its token replaced. */
  kick(serverId: string, reason: string) {
    const c = this.live.get(serverId);
    if (c) {
      this.live.delete(serverId);
      c.failAll();
      c.close(RELAY.CLOSE_BAD_TOKEN, reason);
      exec('UPDATE servers SET last_seen_at = NOW() WHERE id = ?', [serverId]).catch(() => {});
      console.info(`relay: server ${serverId} disconnected (${reason})`);
    }
    this.states.delete(serverId);
  }

  emit(event: HubEvent) {
    this.emitter.emit('event', event);
  }

  subscribe(listener: (e: HubEvent) => void): () => void {
    this.emitter.on('event', listener);
    return () => this.emitter.off('event', listener);
  }

  closeAll() {
    clearInterval(this.sweeper);
    for (const c of this.live.values()) c.close(1001, 'panel stopping');
  }
}

// The custom server makes the hub; Next's route handlers are bundled separately and find it here.
const g = globalThis as { __aibHub?: RelayHub };

export function installHub(hub: RelayHub) {
  g.__aibHub = hub;
}

export function hub(): RelayHub {
  if (!g.__aibHub) throw new Error('the relay hub is not running; start the app with server.ts (npm run dev / npm start), not next dev');
  return g.__aibHub;
}
