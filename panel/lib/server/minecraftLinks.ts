import { createHash, randomBytes } from 'node:crypto';
import { assetRequest } from './assetRelay.ts';
import { AssetError, type Deps as AssetDeps } from './assets.ts';
import type { Json } from './hub';
import { atLeast, type Role } from './roles.ts';

// Links between Minecraft players and panel accounts, for saving assets in game (`/aibuilder asset save`). A signed-in
// user makes a one-time code on the panel and types it in game with `/aibuilder link <code>`; the plugin sends the code
// with the player's UUID, and the panel takes the server from the relay socket, never from the frame. The rules live
// here, with MySQL passed in (minecraftLinkStore.ts wires it), so the tests run them without a database.
//
// Trust: the plugin reports the player's UUID. A server's operator can therefore claim any player's UUID, but a claimed
// UUID only reaches the library of an account that is a builder on that very server, which the owner of that server
// already trusts to build there.

/** Unambiguous when typed from a screen: no I, O, 0 or 1. 32 symbols, so a code is 40 random bits. */
export const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
export const CODE_LENGTH = 8;
export const CODE_TTL_MS = 10 * 60_000;
/** At most this many codes per user in CODE_WINDOW_MS, used or not. */
export const MAX_CODES = 5;
export const CODE_WINDOW_MS = 10 * 60_000;
/** Minecraft accounts one panel account may link. */
export const MAX_LINKS = 5;
/** Linking is for saving assets, which needs what saving from the panel needs (assets.ts PROMOTE_ROLE). */
export const LINK_ROLE: Role = 'builder';
/** `link` frames per server and minute: a server's operator could otherwise guess codes. */
export const LINK_ATTEMPTS = 20;
export const LINK_ATTEMPT_WINDOW_MS = 60_000;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
/** Java names, and the prefixed names Geyser/Floodgate gives Bedrock players. */
const PLAYER_NAME = /^[.*]?[A-Za-z0-9_]{1,30}$/;

export class LinkError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export interface MinecraftLink {
  uuid: string;
  /** The player's name as a server last reported it, or null. */
  name: string | null;
  linked: number;
}

/** The link rows (MySQL). */
export interface LinkStore {
  /**
   * In one transaction for the user: `check` sees how many codes the user made since `since` and throws to refuse;
   * every unused code of the user is then spent and this one added. Codes made before `since` may be deleted.
   */
  issueCode(user: number, hash: string, created: Date, expires: Date, since: Date, check: (recent: number) => void): Promise<void>;
  /** Spends the code if it is unused and not expired at `now`, and answers its user; null otherwise. Only one caller wins. */
  consumeCode(hash: string, now: Date): Promise<number | null>;
  links(user: number): Promise<MinecraftLink[]>;
  userOf(uuid: string): Promise<number | null>;
  /**
   * Links the UUID to the user, in one transaction for the user: `check` sees how many other UUIDs the user has linked
   * and who has this UUID now (or null), and throws to refuse. Answers false when the user already had it.
   */
  link(uuid: string, user: number, name: string | null, at: Date, check: (count: number, owner: number | null) => void): Promise<boolean>;
  /** Removes the link when it belongs to the user. */
  unlink(uuid: string, user: number): Promise<boolean>;
  /** Records the player's current name. */
  rename(uuid: string, name: string): Promise<void>;
}

export interface LinkDeps {
  store: LinkStore;
  roleOf(user: number, server: string): Promise<Role | null>;
  clock(): number;
  /** Where a player makes a code, for messages shown in game. */
  page: string;
}

export interface Player { uuid: string; name: string | null }

// ---- codes ----

export function newCode(random: (n: number) => Uint8Array = randomBytes): string {
  // 256 is a multiple of 32, so every symbol is equally likely.
  return [...random(CODE_LENGTH)].map(b => CODE_ALPHABET[b % CODE_ALPHABET.length]).join('');
}

/** ABCD-EFGH: easier to read and type. */
export const showCode = (code: string) => `${code.slice(0, 4)}-${code.slice(4)}`;

/** A code as typed: case, spaces and dashes do not matter. */
export function normalizeCode(v: unknown): string {
  const code = typeof v === 'string' ? v.replace(/[\s-]/g, '').toUpperCase() : '';
  if (code.length !== CODE_LENGTH || [...code].some(c => !CODE_ALPHABET.includes(c))) {
    throw new LinkError(400, 'that is not a link code; make one on the panel and type it as shown, for example /aibuilder link ABCD-EFGH');
  }
  return code;
}

/** Codes are credentials: only this is stored. */
export const hashCode = (code: string) => createHash('sha256').update('minecraft-link:' + code, 'utf8').digest('hex');

/** Makes a code for the signed-in user; their earlier unused codes stop working. */
export async function issueCode(deps: LinkDeps, user: number, random?: (n: number) => Uint8Array): Promise<{ code: string; expires: number }> {
  const now = deps.clock();
  const check = (recent: number) => {
    if (recent >= MAX_CODES) throw new LinkError(429, `you made ${MAX_CODES} codes in the last ${CODE_WINDOW_MS / 60_000} minutes; wait a few minutes and try again`);
  };
  // Two users drawing the same 40 bits is unlikely but not impossible: the hash is the key, so draw again.
  for (let attempt = 0; ; attempt++) {
    const code = newCode(random);
    try {
      await deps.store.issueCode(user, hashCode(code), new Date(now), new Date(now + CODE_TTL_MS), new Date(now - CODE_WINDOW_MS), check);
      return { code: showCode(code), expires: now + CODE_TTL_MS };
    } catch (e) {
      if ((e as { code?: unknown })?.code !== 'ER_DUP_ENTRY' || attempt >= 2) throw e;
    }
  }
}

// ---- links, from the panel ----

export function checkUuid(v: unknown): string {
  const uuid = typeof v === 'string' ? v.toLowerCase() : '';
  if (!UUID.test(uuid)) throw new LinkError(400, 'give a player UUID');
  return uuid;
}

export async function listLinks(deps: LinkDeps, user: number) {
  return { links: await deps.store.links(user), max: MAX_LINKS };
}

export async function unlinkByUser(deps: LinkDeps, user: number, uuid: unknown): Promise<void> {
  if (!await deps.store.unlink(checkUuid(uuid), user)) throw new LinkError(404, 'that Minecraft account is not linked to yours');
}

// ---- frames from a server's plugin ----

/** The player a frame is about: {uuid, name}. A name that does not look like one is dropped, not refused. */
export function checkPlayer(v: unknown): Player {
  const p = (v && typeof v === 'object' ? v : {}) as Json;
  const uuid = checkUuid(p.uuid);
  return { uuid, name: typeof p.name === 'string' && PLAYER_NAME.test(p.name) ? p.name : null };
}

/** Sliding-window limit on `link` frames, one per server. */
export class AttemptLimit {
  private times: number[] = [];
  private readonly max: number;
  private readonly windowMs: number;
  private readonly clock: () => number;
  constructor(max = LINK_ATTEMPTS, windowMs = LINK_ATTEMPT_WINDOW_MS, clock: () => number = Date.now) {
    this.max = max;
    this.windowMs = windowMs;
    this.clock = clock;
  }

  take(): void {
    const now = this.clock();
    this.times = this.times.filter(t => t > now - this.windowMs);
    if (this.times.length >= this.max) throw new LinkError(429, 'too many link attempts on this server; try again in a minute');
    this.times.push(now);
  }
}

/** {code, player:{uuid,name}} from `server`'s socket: spends the code, then links its user if they build there. */
export async function linkPlayer(deps: LinkDeps, server: string, frame: Json): Promise<Json> {
  const player = checkPlayer(frame.player);
  const code = normalizeCode(frame.code);
  // Spent before anything else is checked, so a code tells a server nothing twice.
  const user = await deps.store.consumeCode(hashCode(code), new Date(deps.clock()));
  if (user === null) throw new LinkError(400, 'this code is wrong, used or expired; make a new one on the panel');
  if (!atLeast(await deps.roleOf(user, server), LINK_ROLE)) {
    throw new LinkError(403, `the panel account that made this code is not a ${LINK_ROLE} on this server; `
      + 'ask an admin of the server to invite you, then make a new code');
  }
  const added = await deps.store.link(player.uuid, user, player.name, new Date(deps.clock()), (count, owner) => {
    if (owner !== null && owner !== user) {
      throw new LinkError(409, 'this Minecraft account is linked to another panel account; unlink it there first (or run /aibuilder unlink)');
    }
    if (owner === null && count >= MAX_LINKS) {
      throw new LinkError(409, `your panel account has ${MAX_LINKS} Minecraft accounts linked; unlink one on the panel first`);
    }
  });
  return { linked: true, already: !added };
}

const notLinkedHere = () => new LinkError(404, 'this Minecraft account is not linked to a panel account that is a builder on this server');

/** {player} from `server`'s socket: removes the player's link, if its account is a builder on that server. */
export async function unlinkPlayer(deps: LinkDeps, server: string, frame: Json): Promise<Json> {
  const player = checkPlayer(frame.player);
  const user = await deps.store.userOf(player.uuid);
  // Any server could send this for any UUID: one that is no business of this server answers as if there were no link.
  // Builder, as for link and save: a server where the account only views cannot remove its global link.
  if (user === null || !atLeast(await deps.roleOf(user, server), LINK_ROLE)) throw notLinkedHere();
  if (!await deps.store.unlink(player.uuid, user)) throw notLinkedHere();
  return { unlinked: true };
}

/** The account a player saves as: linked, and a builder on the server the frame came from. */
export async function linkedBuilder(deps: LinkDeps, server: string, player: Player): Promise<number> {
  const user = await deps.store.userOf(player.uuid);
  if (user === null) {
    throw new LinkError(403, `your Minecraft account is not linked to a panel account: make a code under Minecraft account on ${deps.page}, `
      + 'then run /aibuilder link <code> here');
  }
  const role = await deps.roleOf(user, server);
  if (!atLeast(role, LINK_ROLE)) {
    throw new LinkError(403, `your linked panel account needs the ${LINK_ROLE} role on this server to use its assets here; `
      + `yours is ${role ?? 'none'}, so ask the server's owner`);
  }
  return user;
}

/** What a player may do from the game: list (/aibuilder asset list), and check then save (/aibuilder asset save). */
const PLAYER_OPS = ['list', 'check', 'save'];

/**
 * An in-game asset request ({op: list|check|save, player, args}) from `server`'s socket. It is authorized by the
 * player's link and role on that server, not by a tool call's grant, and goes through the same rules as Claude's
 * calls: list answers what get_context lists (own assets, then those shared with this server), never a download.
 */
export async function playerAssetRequest(deps: LinkDeps, assets: (user: number) => AssetDeps, server: string,
  mc: string | null, frame: Json, sign: (key: string) => Promise<string>): Promise<Json> {
  if (!PLAYER_OPS.includes(frame.op)) throw new LinkError(400, `unknown in-game asset operation '${String(frame.op)}'`);
  const player = checkPlayer(frame.player);
  const user = await linkedBuilder(deps, server, player);
  if (player.name) await deps.store.rename(player.uuid, player.name).catch(() => {});
  try {
    return await assetRequest(assets(user), user, server, mc, { op: frame.op, args: frame.args ?? {} }, sign);
  } catch (e) {
    // Claude renames on its own; a player is told how.
    if (e instanceof AssetError && e.status === 409 && e.message.startsWith('you already have an asset named')) {
      throw new AssetError(409, `${e.message}; pick another name, or rename or delete that one on the panel`);
    }
    throw e;
  }
}

/** The status and body a link or in-game asset failure answers with; null for an unexpected error. */
export function failure(e: unknown): { status: number; error: string } | null {
  if (e instanceof LinkError || e instanceof AssetError) return { status: e.status, error: e.message };
  return null;
}
