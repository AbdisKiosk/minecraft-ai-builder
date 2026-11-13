import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { registerHooks } from 'node:module';
import { mock, test } from 'node:test';

// hub.ts imports its neighbours without extensions, as the Next and esbuild bundles resolve them; Node needs the
// extension. Nothing here touches MySQL: the hub's database writes fail quietly without DATABASE_URL.
registerHooks({
  resolve(specifier, context, next) {
    try {
      return next(specifier, context);
    } catch (e) {
      if (specifier.startsWith('.') && !/\.[cm]?[jt]s$/.test(specifier)) return next(`${specifier}.ts`, context);
      throw e;
    }
  },
});
const { RelayHub } = await import('../lib/server/hub.ts');

const R2 = ['R2_ACCOUNT_ID', 'R2_ACCESS_KEY_ID', 'R2_SECRET_ACCESS_KEY', 'R2_BUCKET'];
const storage = (on: boolean) => { for (const k of R2) if (on) process.env[k] = 'test'; else delete process.env[k]; };
delete process.env.DATABASE_URL;
for (const level of ['info', 'warn', 'debug'] as const) mock.method(console, level, () => {});

/** A plugin's end of a relay socket: what the hub sends it, and frames it sends the hub. */
class FakeSocket extends EventEmitter {
  readyState = 1;
  sent: any[] = [];
  send(data: string, cb?: (e?: Error) => void) { this.sent.push(JSON.parse(data)); cb?.(); }
  close() { this.readyState = 3; }
  terminate() { this.readyState = 3; }
  frame(f: unknown) { this.emit('message', Buffer.from(JSON.stringify(f)), false); }
  last(t: string) { return [...this.sent].reverse().find(f => f.t === t); }
  /** Sends an asset frame and waits for the panel's answer to it. */
  async asset(id: number, assetKey: unknown) {
    this.frame({ t: 'asset', id, assetKey, op: 'list', args: {} });
    for (let i = 0; i < 50; i++) {
      const answer = this.sent.find(f => f.t === 'answer' && f.id === id);
      if (answer) return answer;
      await new Promise(r => setTimeout(r, 5));
    }
    return null;
  }
}

function connect(hub: InstanceType<typeof RelayHub>, server: string) {
  const socket = new FakeSocket();
  (hub as any).accept(server, socket);
  socket.frame({ t: 'hello', protocol: 2, mc: '1.21.4' });
  return socket;
}

/** A tool request through the hub as the proxy makes it; returns the frame the plugin got and the pending reply. */
function call(hub: InstanceType<typeof RelayHub>, socket: FakeSocket, server: string, timeoutMs = 10_000) {
  const before = socket.sent.length;
  const reply = hub.requestAsUser(server, { t: 'api', tool: 'build', args: {} }, 7, timeoutMs);
  const frame = socket.sent[before];
  return { frame, reply, answer: (body: unknown = {}) => socket.frame({ t: 'reply', id: frame.id, status: 200, body }) };
}

test('without object storage a call carries no grant and the plugin is told there is no library', async () => {
  storage(false);
  const hub = new RelayHub();
  try {
    const s = connect(hub, 'a');
    assert.equal(s.last('welcome').assets, false);
    const c = call(hub, s, 'a');
    assert.ok(!('user' in c.frame) && !('assetKey' in c.frame), JSON.stringify(c.frame));
    c.answer({ ok: true });
    assert.deepEqual(await c.reply, { status: 200, body: { ok: true } });
  } finally {
    hub.closeAll();
  }
});

test('a grant works only on its socket, only while its call is open, and never without its key', async () => {
  storage(true);
  const hub = new RelayHub();
  try {
    const a = connect(hub, 'a'), b = connect(hub, 'b');
    assert.equal(a.last('welcome').assets, true);
    const c = call(hub, a, 'a');
    assert.equal(c.frame.user, 7);
    assert.match(c.frame.assetKey, /^[a-f0-9]{64}$/);

    // An accepted grant gets past the grant check to storage; with storage switched off for this, it stops there.
    storage(false);
    assert.equal((await a.asset(101, c.frame.assetKey)).status, 503);
    storage(true);
    for (const [socket, key] of [[a, undefined], [a, 'f'.repeat(64)], [a, 7], [b, c.frame.assetKey]] as const) {
      const answer = await socket.asset(102, key);
      assert.equal(answer.status, 403, `${String(key)}`);
      assert.match(answer.body.error, /active authenticated tool request/);
      socket.sent = [];
    }

    c.answer();
    await c.reply;
    assert.equal((await a.asset(103, c.frame.assetKey)).status, 403, 'revoked when the call is answered');
  } finally {
    hub.closeAll();
  }
});

test('grants end on timeout, on expiry and on disconnect, and do not carry over to a reconnect', async () => {
  storage(true);
  const hub = new RelayHub();
  try {
    let a = connect(hub, 'a');
    const late = call(hub, a, 'a', 30);
    await assert.rejects(late.reply, (e: any) => e.code === 'TIMEOUT');
    assert.equal((await a.asset(201, late.frame.assetKey)).status, 403, 'revoked on timeout');

    // A grant's own expiry, should a reply never come: the socket's clock is the mocked Date from its start.
    mock.timers.enable({ apis: ['Date'], now: Date.now() });
    try {
      const b = connect(hub, 'b');
      const open = call(hub, b, 'b', 1_000);
      mock.timers.setTime(Date.now() + 1_001);
      const answer = await b.asset(202, open.frame.assetKey);
      open.answer();
      await open.reply;
      assert.equal(answer.status, 403, 'expired while the call was still open');
    } finally {
      mock.timers.reset();
    }

    const cut = call(hub, a, 'a');
    a.emit('close', 1006, Buffer.from(''));
    await assert.rejects(cut.reply, (e: any) => e.code === 'OFFLINE');
    a = connect(hub, 'a');
    assert.equal((await a.asset(203, cut.frame.assetKey)).status, 403, 'a reconnect has its own grants');
  } finally {
    hub.closeAll();
  }
});

test('with every grant of a socket in use, an ordinary call still runs, with the account and no grant', async () => {
  storage(true);
  const hub = new RelayHub();
  try {
    const a = connect(hub, 'a');
    const open = Array.from({ length: 128 }, () => call(hub, a, 'a'));
    assert.ok(open.every(c => typeof c.frame.assetKey === 'string'));
    const extra = call(hub, a, 'a');
    assert.equal(extra.frame.user, 7);
    assert.ok(!('assetKey' in extra.frame));
    extra.answer({ done: true });
    assert.deepEqual(await extra.reply, { status: 200, body: { done: true } });
    for (const c of open) c.answer();
    await Promise.all(open.map(c => c.reply));
    const again = call(hub, a, 'a');
    assert.match(again.frame.assetKey, /^[a-f0-9]{64}$/, 'grants are free again once calls end');
    again.answer();
    await again.reply;
  } finally {
    hub.closeAll();
    storage(false);
  }
});

/** Sends a frame and waits for the panel's answer to its id. */
async function answerTo(socket: FakeSocket, frame: Record<string, unknown>) {
  socket.frame(frame);
  for (let i = 0; i < 50; i++) {
    const answer = socket.sent.find(f => f.t === 'answer' && f.id === frame.id);
    if (answer) return answer;
    await new Promise(r => setTimeout(r, 5));
  }
  return null;
}

test('the welcome offers account links; link frames are checked before any lookup and limited per server', async () => {
  storage(false);
  const hub = new RelayHub();
  try {
    const a = connect(hub, 'a');
    assert.equal(a.last('welcome').links, true);
    const player = { uuid: '0f8b3c2e-1d4a-4b5c-9e6f-7a8b9c0d1e2f', name: 'Alex' };
    const bad = await answerTo(a, { t: 'link', id: 1, code: 'not a code!', player });
    assert.equal(bad.status, 400);
    assert.match(bad.body.error, /not a link code/);
    assert.equal((await answerTo(a, { t: 'link', id: 2, code: 'ABCD-EFGH', player: { uuid: 'x' } })).status, 400);
    assert.equal((await answerTo(a, { t: 'unlink', id: 3, player: {} })).status, 400);
    // Without object storage there is no library to save into; said before the link is looked up.
    const save = await answerTo(a, { t: 'player_asset', id: 4, op: 'check', player, args: { name: 'window' } });
    assert.equal(save.status, 503);
    assert.equal((await answerTo(a, { t: 'player_asset', id: 100, op: 'list', player, args: {} })).status, 503);
    for (let id = 5; id < 23; id++) await answerTo(a, { t: 'link', id, code: '?', player });
    const limited = await answerTo(a, { t: 'link', id: 23, code: '?', player });
    assert.equal(limited.status, 429, 'twenty link attempts a minute per server');
    // The limit belongs to the server, not to its socket.
    const again = connect(hub, 'a');
    assert.equal((await answerTo(again, { t: 'link', id: 24, code: '?', player })).status, 429);
    assert.equal((await answerTo(connect(hub, 'b'), { t: 'link', id: 25, code: '?', player })).status, 400);
    // Frames from a socket that never said hello, or was replaced, get no answer.
    const fresh = new FakeSocket();
    (hub as any).accept('c', fresh);
    fresh.frame({ t: 'link', id: 26, code: '?', player });
    assert.equal(await answerTo(a, { t: 'unlink', id: 27, player }), null);
    assert.equal(fresh.sent.length, 0);
  } finally {
    hub.closeAll();
  }
});
