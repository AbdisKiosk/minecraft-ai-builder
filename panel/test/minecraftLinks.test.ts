import assert from 'node:assert/strict';
import { test } from 'node:test';
import { gzipSync } from 'node:zlib';
import { AssetError, MAX_ASSETS, type AssetRow, type Deps } from '../lib/server/assets.ts';
import {
  AttemptLimit, CODE_TTL_MS, LinkError, MAX_CODES, MAX_LINKS, checkPlayer, failure, hashCode, issueCode, linkPlayer,
  listLinks, newCode, normalizeCode, playerAssetRequest, unlinkByUser, unlinkPlayer, type LinkDeps, type LinkStore,
} from '../lib/server/minecraftLinks.ts';
import type { Role } from '../lib/server/roles.ts';

const ALEX = '0f8b3c2e-1d4a-4b5c-9e6f-7a8b9c0d1e2f';
const STEVE = '11111111-2222-4333-8444-555555555555';

interface CodeRow { hash: string; user: number; created: number; expires: number; used: number | null }

/** The store's contract (minecraftLinkStore.ts) in memory, with every call recorded. */
function fakes() {
  let now = 1_000_000_000;
  const codes: CodeRow[] = [];
  const links = new Map<string, { user: number; name: string | null; linked: number }>();
  const roles = new Map<string, Role>(); // `${user}@${server}`
  const store: LinkStore = {
    async issueCode(user, hash, created, expires, since, check) {
      for (let i = codes.length - 1; i >= 0; i--) if (codes[i].created < since.getTime()) codes.splice(i, 1);
      check(codes.filter(c => c.user === user && c.created >= since.getTime()).length);
      for (const c of codes) if (c.user === user && c.used === null) c.used = created.getTime();
      if (codes.some(c => c.hash === hash)) throw Object.assign(new Error('dup'), { code: 'ER_DUP_ENTRY' });
      codes.push({ hash, user, created: created.getTime(), expires: expires.getTime(), used: null });
    },
    async consumeCode(hash, at) {
      const c = codes.find(r => r.hash === hash && r.used === null && r.expires > at.getTime());
      if (!c) return null;
      c.used = at.getTime();
      return c.user;
    },
    async links(user) {
      return [...links].filter(([, l]) => l.user === user).map(([uuid, l]) => ({ uuid, name: l.name, linked: l.linked }));
    },
    async userOf(uuid) { return links.get(uuid)?.user ?? null; },
    async link(uuid, user, name, at, check) {
      const owner = links.get(uuid)?.user ?? null;
      check([...links].filter(([u, l]) => l.user === user && u !== uuid).length, owner);
      if (owner !== null) { if (name) links.get(uuid)!.name = name; return false; }
      links.set(uuid, { user, name, linked: at.getTime() });
      return true;
    },
    async unlink(uuid, user) {
      if (links.get(uuid)?.user !== user) return false;
      return links.delete(uuid);
    },
    async rename(uuid, name) { const l = links.get(uuid); if (l) l.name = name; },
  };
  const deps: LinkDeps = {
    store,
    roleOf: async (user, server) => roles.get(`${user}@${server}`) ?? null,
    clock: () => now,
    page: 'https://panel.example/dashboard#/tokens',
  };
  const code = async (user: number) => (await issueCode(deps, user)).code;
  const link = (server: string, c: string, uuid = ALEX, name: unknown = 'Alex') => linkPlayer(deps, server, { code: c, player: { uuid, name } });
  return {
    deps, codes, links, roles, code, link,
    tick: (ms: number) => { now += ms; },
    role: (user: number, server: string, role: Role) => roles.set(`${user}@${server}`, role),
  };
}

test('codes are short, unambiguous, typed loosely, and only their hash is stored', async () => {
  const f = fakes();
  const shown = await f.code(1);
  assert.match(shown, /^[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$/);
  const plain = shown.replace('-', '');
  assert.equal(normalizeCode(` ${plain.toLowerCase().slice(0, 4)} - ${plain.slice(4)} `), plain);
  assert.equal(f.codes.length, 1);
  assert.equal(f.codes[0].hash, hashCode(plain));
  assert.match(f.codes[0].hash, /^[a-f0-9]{64}$/);
  assert.ok(!JSON.stringify(f.codes).includes(plain), 'the code itself is not stored');
  assert.equal(f.codes[0].expires - f.codes[0].created, CODE_TTL_MS);
  for (const bad of ['ABCDEFG', 'ABCDEFGHJ', 'ABCD-EFG0', 'ABCDEFGI', 42, null]) assert.throws(() => normalizeCode(bad), /not a link code/);
  // Every byte maps to a symbol, so no symbol is more likely than another.
  assert.equal(newCode(n => new Uint8Array(n).fill(255)), '99999999');
  assert.equal(newCode(n => new Uint8Array(n).fill(32)), 'AAAAAAAA');
});

test('a code links once, from a server where its user builds, and expires after ten minutes', async () => {
  const f = fakes();
  f.role(1, 'srv', 'builder');
  const c = await f.code(1);
  assert.deepEqual(await f.link('srv', c), { linked: true, already: false });
  assert.deepEqual(await listLinks(f.deps, 1), { links: [{ uuid: ALEX, name: 'Alex', linked: 1_000_000_000 }], max: MAX_LINKS });
  await assert.rejects(f.link('srv', c, STEVE), /wrong, used or expired/, 'a code is single use');
  assert.equal(await f.deps.store.userOf(STEVE), null);

  const late = await f.code(1);
  f.tick(CODE_TTL_MS);
  await assert.rejects(f.link('srv', late, STEVE), /wrong, used or expired/);
  await assert.rejects(f.link('srv', 'ABCD-EFGH', STEVE), /wrong, used or expired/);
});

test('a new code cancels the unused one, and at most five are made per ten minutes', async () => {
  const f = fakes();
  f.role(1, 'srv', 'owner');
  const first = await f.code(1);
  const second = await f.code(1);
  await assert.rejects(f.link('srv', first), /wrong, used or expired/);
  await f.link('srv', second);
  for (let i = 2; i < MAX_CODES; i++) await f.code(1);
  await assert.rejects(f.code(1), (e: unknown) => e instanceof LinkError && e.status === 429);
  assert.ok((await f.code(2)).length, 'the limit is per user');
  f.tick(10 * 60_000 + 1);
  assert.ok((await f.code(1)).length, 'the window slides');
  assert.deepEqual(f.codes.map(c => c.created), [1_000_000_000 + 10 * 60_000 + 1], 'codes older than the window are deleted');
});

test('a colliding code is drawn again rather than failing', async () => {
  const f = fakes();
  let calls = 0;
  const random = (n: number) => new Uint8Array(n).fill(calls++ < 2 ? 0 : 1);
  assert.equal((await issueCode(f.deps, 1, random)).code, 'AAAA-AAAA');
  assert.equal((await issueCode(f.deps, 2, random)).code, 'BBBB-BBBB');
});

test('linking needs the builder role on the server the frame came from', async () => {
  const f = fakes();
  f.role(1, 'mine', 'viewer');
  f.role(1, 'other', 'builder');
  // The frame names no server and no user; the socket's server decides.
  const c = await f.code(1);
  await assert.rejects(linkPlayer(f.deps, 'mine', { code: c, player: { uuid: ALEX }, server: 'other', user: 1 }), /not a builder on this server/);
  await assert.rejects(f.link('other', c), /wrong, used or expired/, 'a refused code is spent');
  await assert.rejects(f.link('nowhere', await f.code(1)), /not a builder/);
  assert.equal(f.links.size, 0);
});

test('a UUID belongs to one account; relinking it to the same account is fine; five per account', async () => {
  const f = fakes();
  f.role(1, 'srv', 'builder');
  f.role(2, 'srv', 'builder');
  await f.link('srv', await f.code(1));
  await assert.rejects(f.link('srv', await f.code(2)), /linked to another panel account/);
  assert.equal(await f.deps.store.userOf(ALEX), 1);
  assert.deepEqual(await f.link('srv', await f.code(1), ALEX, 'AlexRenamed'), { linked: true, already: true });
  assert.equal(f.links.get(ALEX)!.name, 'AlexRenamed');
  for (let i = 1; i < MAX_LINKS; i++) {
    f.tick(10 * 60_000);
    await f.link('srv', await f.code(1), `00000000-0000-4000-8000-00000000000${i}`);
  }
  await assert.rejects(f.link('srv', await f.code(1), STEVE), new RegExp(`${MAX_LINKS} Minecraft accounts linked`));
});

test('players and their names are checked; a bad name is dropped, a bad UUID refused', () => {
  assert.deepEqual(checkPlayer({ uuid: ALEX.toUpperCase(), name: 'Alex_01' }), { uuid: ALEX, name: 'Alex_01' });
  assert.deepEqual(checkPlayer({ uuid: ALEX, name: '.BedrockGuy' }), { uuid: ALEX, name: '.BedrockGuy' });
  assert.equal(checkPlayer({ uuid: ALEX, name: '<script>' }).name, null);
  assert.equal(checkPlayer({ uuid: ALEX, name: 'x'.repeat(40) }).name, null);
  for (const bad of [undefined, null, {}, { uuid: 'nope' }, { uuid: ALEX + '0' }, 'string']) assert.throws(() => checkPlayer(bad), /player UUID/);
});

test('unlink: the user on the panel, or the player on a server where the account is a member', async () => {
  const f = fakes();
  f.role(1, 'srv', 'builder');
  f.role(3, 'evil', 'owner');
  await f.link('srv', await f.code(1));
  await assert.rejects(unlinkByUser(f.deps, 2, ALEX), /not linked to yours/);
  await assert.rejects(unlinkPlayer(f.deps, 'evil', { player: { uuid: ALEX } }), /not linked to a panel account that is a member of this server/,
    'a server the account has nothing to do with cannot remove the link');
  assert.equal(await f.deps.store.userOf(ALEX), 1);
  assert.deepEqual(await unlinkPlayer(f.deps, 'srv', { player: { uuid: ALEX } }), { unlinked: true });
  await assert.rejects(unlinkPlayer(f.deps, 'srv', { player: { uuid: ALEX } }), /not linked/);
  await f.link('srv', await f.code(1));
  await unlinkByUser(f.deps, 1, ALEX.toUpperCase());
  assert.equal(f.links.size, 0);
  await assert.rejects(unlinkByUser(f.deps, 1, 'not-a-uuid'), /player UUID/);
});

test('link attempts are limited per window', () => {
  let now = 0;
  const limit = new AttemptLimit(3, 1000, () => now);
  limit.take(); limit.take(); limit.take();
  assert.throws(() => limit.take(), /too many link attempts/);
  now = 1001;
  limit.take();
});

// ---- in-game saves ----

function region() {
  const raw = Buffer.alloc(12 + 2 + 5 + 8);
  raw.writeInt32BE(0x53413031); raw.writeInt32BE(1, 4); raw.writeInt32BE(1, 8);
  raw.writeUInt16BE(5, 12); raw.write('stone', 14); raw.writeInt32BE(0, 19); raw.writeInt32BE(1, 23);
  return gzipSync(raw);
}

function library(f: ReturnType<typeof fakes>) {
  const rows: AssetRow[] = [];
  const blobs = new Map<string, Uint8Array>();
  const owners: number[] = [];
  const assets = (user: number): Deps => {
    owners.push(user);
    return {
      store: {
        list: async owner => rows.filter(r => r.owner === owner),
        get: async id => rows.find(r => r.id === id) ?? null,
        byName: async (owner, name) => rows.find(r => r.owner === owner && r.name === name) ?? null,
        usage: async owner => ({ count: rows.filter(r => r.owner === owner).length, bytes: 0 }),
        async insert(row, check) { check(await this.usage(row.owner)); rows.push(row); },
        async update() {}, async remove() {}, async touch() {},
      },
      files: {
        async write(owner, id, name, bytes) { blobs.set(`${owner}/${id}/${name}`, bytes); },
        read: async (owner, id, name) => blobs.get(`${owner}/${id}/${name}`) ?? null,
        async removeAll() {},
      },
      // As hub.ts wires it: the linked user's role on the frame's server, checked again.
      async requireRole(server, needed) {
        if (needed !== 'builder' || !['builder', 'admin', 'owner'].includes(f.roles.get(`${user}@${server}`) ?? '')) {
          throw new AssetError(403, 'asset access needs builder membership on this server');
        }
      },
      relay: async () => { throw new Error('no export'); },
      mcVersion: async () => null,
    };
  };
  const call = (server: string, op: string, args: object, player: object = { uuid: ALEX, name: 'Alex' }) =>
    playerAssetRequest(f.deps, assets, server, '1.21.4', { op, player, args }, async () => 'https://storage.example/x');
  const save = (server: string, name: string, extra = {}) =>
    call(server, 'save', { name, size: [1, 1, 1], data: region().toString('base64'), ...extra });
  return { rows, owners, call, save };
}

test('an in-game save without a link fails and names the page and the command', async () => {
  const f = fakes();
  const lib = library(f);
  await assert.rejects(lib.call('srv', 'check', { name: 'window' }), (e: unknown) => {
    assert.ok(e instanceof LinkError && e.status === 403);
    assert.match(e.message, /not linked to a panel account/);
    assert.match(e.message, /https:\/\/panel\.example\/dashboard#\/tokens/);
    assert.match(e.message, /\/aibuilder link <code>/);
    return true;
  });
  await assert.rejects(lib.save('srv', 'window'), /not linked/);
  assert.equal(lib.rows.length, 0);
  assert.deepEqual(lib.owners, [], 'the library is never opened without a link');
});

test('an in-game save goes to the linked account, on a server where it builds, with quota and name checks', async () => {
  const f = fakes();
  const lib = library(f);
  f.role(1, 'srv', 'builder');
  f.role(1, 'watch', 'viewer');
  await f.link('srv', await f.code(1));
  assert.deepEqual(await lib.call('srv', 'check', { name: 'window' }), {});
  const saved = await lib.save('srv', 'window', { description: 'oak window', user: 2 });
  assert.deepEqual(saved, { saved: 'window', size: [1, 1, 1], blocks: 1, library: true });
  assert.equal(lib.rows[0].owner, 1, 'ownership comes from the link, never a frame field');
  assert.equal(lib.rows[0].mc, '1.21.4');
  await assert.rejects(lib.call('srv', 'check', { name: 'window' }), /already have an asset named 'window'/);
  await assert.rejects(lib.save('srv', 'window'), /already have an asset named 'window'/);
  await assert.rejects(lib.save('watch', 'door'), /needs the builder role on this server to save assets; yours is viewer/);
  await assert.rejects(lib.save('elsewhere', 'door'), /yours is none/);
  await assert.rejects(lib.call('srv', 'get', { name: 'window' }), /unknown in-game asset operation 'get'/, 'in game only checks and saves');
  await assert.rejects(lib.call('srv', 'list', {}), /unknown in-game asset operation/);
  for (let i = 1; i < MAX_ASSETS; i++) lib.rows.push({ ...lib.rows[0], id: `fill${i}`, name: `fill${i}` });
  await assert.rejects(lib.call('srv', 'check', { name: 'more' }), /library is full/);
  await assert.rejects(lib.save('srv', 'more'), /library is full/);
  // The role is checked on every save, not only when linking.
  f.roles.delete('1@srv');
  await assert.rejects(lib.save('srv', 'later'), /needs the builder role/);
});

test('the player name a server reports is kept up to date for the panel', async () => {
  const f = fakes();
  const lib = library(f);
  f.role(1, 'srv', 'builder');
  await f.link('srv', await f.code(1), ALEX, null);
  assert.equal(f.links.get(ALEX)!.name, null);
  await lib.call('srv', 'check', { name: 'window' }, { uuid: ALEX, name: 'Alex' });
  assert.equal(f.links.get(ALEX)!.name, 'Alex');
});

test('failures map to statuses; anything else is an unexpected error', () => {
  assert.deepEqual(failure(new LinkError(409, 'taken')), { status: 409, error: 'taken' });
  assert.deepEqual(failure(new AssetError(503, 'no storage')), { status: 503, error: 'no storage' });
  assert.equal(failure(new Error('db down')), null);
});
