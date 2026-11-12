import assert from 'node:assert/strict';
import { test } from 'node:test';
import { gzipSync } from 'node:zlib';
import { AssetGrants, assetRequest } from '../lib/server/assetRelay.ts';
import { AssetError, MAX_ASSETS, MAX_REGION_BYTES, type AssetRow, type Deps, type SharedRow } from '../lib/server/assets.ts';
import { atLeast, type Role } from '../lib/server/roles.ts';

function region() {
  const raw = Buffer.alloc(12 + 2 + 5 + 8);
  raw.writeInt32BE(0x53413031); raw.writeInt32BE(1, 4); raw.writeInt32BE(1, 8);
  raw.writeUInt16BE(5, 12); raw.write('stone', 14); raw.writeInt32BE(0, 19); raw.writeInt32BE(1, 23);
  return gzipSync(raw);
}

/** `roles` is what each user is on each server ("server:user", null for none); anyone not in it is a builder on 's'. */
function fakes(roles: Record<string, Role | null> = {}) {
  const rows: AssetRow[] = [];
  const blobs = new Map<string, Uint8Array>();
  const shares: { asset: string; server: string }[] = [];
  const signed: string[] = [], touched: string[] = [];
  let allowed = true;
  const role = (server: string, user: number) => {
    const key = `${server}:${user}`;
    return key in roles ? roles[key] : server === 's' ? 'builder' : null;
  };
  const builds = (server: string, user: number) => atLeast(role(server, user), 'builder');
  const deps: Deps = {
    store: {
      list: async user => rows.filter(r => r.owner === user),
      get: async id => rows.find(r => r.id === id) ?? null,
      byName: async (user, name) => rows.find(r => r.owner === user && r.name === name) ?? null,
      usage: async user => ({ count: rows.filter(r => r.owner === user).length, bytes: 0 }),
      async insert(row, check) {
        check(await this.usage(row.owner));
        if (rows.some(r => r.owner === row.owner && r.name === row.name)) throw new AssetError(409, 'taken');
        rows.unshift(row);
      },
      async update() {},
      async remove(user, id) {
        const i = rows.findIndex(r => r.id === id && r.owner === user);
        if (i >= 0) rows.splice(i, 1);
        for (let j = shares.length - 1; j >= 0; j--) if (shares[j].asset === id) shares.splice(j, 1);
      },
      async touch(user, id) { touched.push(`${user}/${id}`); },
      shares: async user => shares.filter(s => rows.find(r => r.id === s.asset)?.owner === user)
        .map(s => ({ ...s, name: s.server, created: 0 })),
      async share(asset, server) { if (!shares.some(s => s.asset === asset && s.server === server)) shares.push({ asset, server }); },
      async unshare(asset, server) {
        const i = shares.findIndex(s => s.asset === asset && s.server === server);
        if (i >= 0) shares.splice(i, 1);
      },
      // The SQL's rule: both the caller and the owner build on the server it is shared with.
      async sharedWith(user, filter = {}) {
        const out = new Map<string, SharedRow>();
        for (const s of shares) {
          const r = rows.find(x => x.id === s.asset);
          if (!r || r.owner === user || !builds(s.server, user) || !builds(s.server, r.owner)) continue;
          if ((filter.server ?? s.server) !== s.server || (filter.name ?? r.name) !== r.name || (filter.id ?? r.id) !== r.id) continue;
          const seen = out.get(r.id);
          if (seen) seen.via.push(s.server);
          else out.set(r.id, { ...r, ownerName: `owner${r.owner}@example.com`, via: [s.server] });
        }
        return [...out.values()];
      },
    },
    files: {
      async write(user, id, name, bytes) { blobs.set(`${user}/${id}/${name}`, bytes); },
      read: async (user, id, name) => blobs.get(`${user}/${id}/${name}`) ?? null,
      async removeAll(user, id) { for (const key of blobs.keys()) if (key.startsWith(`${user}/${id}/`)) blobs.delete(key); },
    },
    async requireRole() { throw new Error('call() binds the caller'); },
    relay: async () => { throw new Error('must not ask the plugin to export again'); },
    mcVersion: async () => '1.21.4',
  };
  // As the hub does: requireRole is the grant's user on the socket's server.
  const call = (user: number, op: string, args = {}, server = 's') => assetRequest({
    ...deps,
    async requireRole(on, needed) {
      assert.equal(on, server); assert.equal(needed, 'builder');
      if (!allowed) throw new AssetError(403, 'membership revoked');
      if (!atLeast(role(on, user), needed)) throw new AssetError(403, 'asset access needs builder membership on this server');
    },
  }, user, server, '1.21.4', { op, args }, async key => {
    signed.push(key); return 'https://storage.example/short-lived-url';
  });
  const save = (user: number, name: string, extra = {}) => call(user, 'save', {
    name, size: [1, 1, 1], data: region().toString('base64'), ...extra,
  });
  const share = (user: number, name: string, server = 's') => {
    const r = rows.find(x => x.owner === user && x.name === name);
    assert.ok(r, `${user} owns ${name}`);
    return deps.store.share(r.id, server, user);
  };
  return { rows, deps, blobs, shares, signed, touched, call, save, share, roles, revoke: () => { allowed = false; } };
}

test('asset grants are opaque, socket scoped, expiring and revocable', () => {
  let now = 100;
  const a = new AssetGrants(() => now), b = new AssetGrants(() => now);
  const key = a.issue(7, 1000), other = a.issue(8, 1000);
  assert.match(key, /^[a-f0-9]{64}$/);
  assert.notEqual(key, other);
  assert.equal(a.owner(key), 7);
  assert.equal(a.owner(other), 8);
  assert.throws(() => b.owner(key), /active authenticated/);
  assert.throws(() => a.owner(7), /active authenticated/);
  a.revoke(key);
  assert.throws(() => a.owner(key), /active authenticated/);
  now = 1100;
  assert.throws(() => a.owner(other), /active authenticated/);
  const fresh = a.issue(7, 1000);
  a.clear();
  assert.throws(() => a.owner(fresh), /active authenticated/);
  assert.throws(() => a.issue(-1, 1000), /invalid asset caller/);
});

test('relay saves and downloads are account-owned even when names collide', async () => {
  const f = fakes();
  const one = await f.save(1, 'window', { description: 'Oak window' });
  await f.save(2, 'window', { description: 'Spruce window' });
  assert.equal(one.library, true);
  assert.equal(f.rows.length, 2);
  assert.equal(f.blobs.size, 4);
  const a = await f.call(1, 'get', { name: 'window', user: 2 });
  assert.equal(a.id, f.rows.find(r => r.owner === 1)!.id);
  assert.deepEqual(a.size, [1, 1, 1]);
  assert.match(a.sha256, /^[a-f0-9]{64}$/);
  assert.equal(a.bytes, region().length);
  assert.deepEqual(f.signed, [`users/1/assets/${a.id}/region.bin`]);
  assert.deepEqual(f.touched, [`1/${a.id}`]);
  await assert.rejects(f.call(3, 'get', { name: 'window' }), /no asset/);
  assert.deepEqual((await f.call(3, 'list')).assets, []);
  await assert.rejects(f.save(1, 'window'), /already have an asset/);
  const before = f.signed.length;
  f.revoke();
  for (const op of ['list', 'check', 'get', 'save']) await assert.rejects(f.call(1, op, { name: 'window' }), /membership revoked/);
  assert.equal(f.signed.length, before);
});

test('Claude listing is bounded, descriptive and does not leak another owner', async () => {
  const f = fakes();
  const saved = await f.save(1, 'window', { description: ' x\n'.repeat(300) });
  const row = f.rows[0];
  for (let i = 0; i < 40; i++) f.rows.push({ ...row, id: `id${i}`, name: `window${i}` });
  const list = await f.call(1, 'list');
  assert.equal(list.assets.length, 30);
  assert.equal(list.assets[0].name, saved.saved);
  assert.equal(list.assets[0].description.length, 80);
  assert.deepEqual(Object.keys(list.assets[0]).sort(), ['description', 'name', 'size']);
  f.deps.store.list = async () => [{ ...row, owner: 2 }];
  assert.deepEqual((await f.call(1, 'list')).assets, []);
  f.deps.store.byName = async () => ({ ...row, owner: 2 });
  await assert.rejects(f.call(1, 'get', { name: 'window' }), /no asset/);
  assert.equal(f.signed.length, 0, 'even a faulty store does not authorize another owner');
});

test('relay saves enforce quotas, bounded dimensions, descriptions and compressed payloads', async () => {
  const f = fakes();
  await assert.rejects(f.save(1, 'bad', { size: [2_000_001, 1, 1] }), /2,000,001 blocks; an asset is at most 2,000,000 blocks/);
  await assert.rejects(f.save(1, 'bad', { size: [-1, 1, 1] }), /dimensions/);
  await assert.rejects(f.save(1, 'bad', { size: [2, 1, 1] }), /sent 1 blocks/);
  await assert.rejects(f.save(1, 'bad', { data: 'not base64!' }), /base64/);
  await assert.rejects(f.save(1, 'bad', { data: Buffer.alloc(MAX_REGION_BYTES + 1).toString('base64') }), /5 MB/);
  await assert.rejects(f.save(1, 'bad', { description: 'x'.repeat(1001) }), /description/);
  assert.equal(f.rows.length + f.blobs.size, 0);
  f.deps.store.usage = async () => ({ count: MAX_ASSETS, bytes: 0 });
  await assert.rejects(f.call(1, 'check', { name: 'full' }), /library is full/);
  await assert.rejects(f.save(1, 'full'), /library is full/);
});

test('partial file writes are cleaned up and failed saves never create a row', async () => {
  const f = fakes();
  const write = f.deps.files.write;
  f.deps.files.write = async (user, id, name, bytes) => {
    if (name === 'blocks') throw new Error('storage unavailable');
    await write(user, id, name, bytes);
  };
  await assert.rejects(f.save(1, 'window'), /storage unavailable/);
  assert.equal(f.rows.length, 0);
  assert.equal(f.blobs.size, 0);
});

test('a failing usage update never fails a download', async () => {
  const f = fakes();
  await f.save(1, 'window');
  f.deps.store.touch = async () => { throw new Error('database is read-only'); };
  const got = await f.call(1, 'get', { name: 'window' });
  assert.match(got.sha256, /^[a-f0-9]{64}$/);
});

test('two saves racing for the last free asset slot: the locked store check decides, the loser leaves nothing', async () => {
  const f = fakes();
  await f.save(1, 'first');
  for (let i = 1; i < MAX_ASSETS - 1; i++) f.rows.push({ ...f.rows[0], id: `fill${i}`, name: `fill${i}` });
  assert.equal(f.rows.length, MAX_ASSETS - 1);
  // Both preflights see one free slot; inserts then run one at a time, as the store's user-row lock makes them.
  const insert = f.deps.store.insert;
  let queue = Promise.resolve();
  f.deps.store.insert = (row, check) => {
    const run = queue.then(() => insert.call(f.deps.store, row, check));
    queue = run.catch(() => {});
    return run;
  };
  const results = await Promise.allSettled([f.save(1, 'left'), f.save(1, 'right')]);
  assert.deepEqual(results.map(r => r.status).sort(), ['fulfilled', 'rejected']);
  const lost = results.find(r => r.status === 'rejected') as PromiseRejectedResult;
  assert.match(String(lost.reason?.message), /library is full/);
  assert.equal(f.rows.length, MAX_ASSETS);
  const kept = new Set(f.rows.map(r => r.id));
  for (const key of f.blobs.keys()) assert.ok(kept.has(key.split('/')[1]), `files of the refused save were removed: ${key}`);
});

test('get carries an anchor and facing only when set, the listing neither', async () => {
  const f = fakes();
  await f.save(1, 'legacy');
  await f.save(1, 'window', { size: [1, 1, 1], anchor: [0, 0, 0], facing: 'south' });
  const legacy = await f.call(1, 'get', { name: 'legacy' });
  assert.equal('anchor' in legacy || 'facing' in legacy, false, 'an old asset sends neither');
  const window = await f.call(1, 'get', { name: 'window' });
  assert.deepEqual([window.anchor, window.facing], [[0, 0, 0], 'south']);
  // Not in the listing: get_context's 30 assets are capped at 5,500 characters (ToolsTest#largestAssetListingStaysSmall).
  for (const a of (await f.call(1, 'list')).assets) assert.deepEqual(Object.keys(a).sort(), ['description', 'name', 'size']);
  await assert.rejects(f.save(1, 'bad', { anchor: [1, 0, 0] }), /anchor must be/);
  await assert.rejects(f.save(1, 'bad', { facing: 'sideways' }), /facing must be/);
});

test('builders on the server an asset is shared with list and paste it; nobody else does', async () => {
  const f = fakes({ 's:3': 'viewer', 's:4': null, 't:1': 'builder', 't:2': 'builder' });
  await f.save(1, 'window', { description: 'Oak window' });
  const id = f.rows[0].id;
  assert.deepEqual((await f.call(2, 'list')).assets, [], 'not shared yet');
  await assert.rejects(f.call(2, 'get', { name: 'window' }), /no asset 'window' in your library or shared with this server/);
  await f.share(1, 'window');

  assert.deepEqual((await f.call(2, 'list')).assets, [{ name: 'window', size: [1, 1, 1], description: 'Oak window', by: 'owner1' }]);
  const got = await f.call(2, 'get', { name: 'window' });
  assert.equal(got.id, id);
  assert.deepEqual(f.signed, [`users/1/assets/${id}/region.bin`], "the owner's file is signed");
  assert.deepEqual(f.touched, [], "a recipient's paste does not reorder the owner's list");
  assert.deepEqual((await f.call(1, 'list')).assets.map((a: { by?: string }) => a.by), [undefined], 'the owner sees it as their own');

  // A viewer and a non-member are refused; a builder on a server it is not shared with does not find it.
  for (const user of [3, 4]) {
    await assert.rejects(f.call(user, 'list'), /builder membership/);
    await assert.rejects(f.call(user, 'get', { name: 'window' }), /builder membership/);
  }
  await assert.rejects(f.call(2, 'get', { name: 'window' }, 't'), /no asset/);
  assert.deepEqual((await f.call(2, 'list', {}, 't')).assets, []);
  assert.equal(f.signed.length, 1);

  // Check (and save) stay in the caller's own library: the recipient's quota and names are untouched.
  await f.call(2, 'check', { name: 'window' });
  assert.deepEqual(await f.deps.store.usage(2), { count: 0, bytes: 0 });

  // An owner who is no longer a builder there no longer shares it.
  f.roles['s:1'] = 'viewer';
  await assert.rejects(f.call(2, 'get', { name: 'window' }), /no asset/);
  f.roles['s:1'] = 'builder';
  await f.call(2, 'get', { name: 'window' });

  await f.deps.store.unshare(id, 's');
  await assert.rejects(f.call(2, 'get', { name: 'window' }), /no asset/);
  assert.deepEqual((await f.call(2, 'list')).assets, []);
  await f.share(1, 'window');
  await f.deps.store.remove(1, id);
  await assert.rejects(f.call(2, 'get', { name: 'window' }), /no asset/);
  assert.deepEqual(f.shares, [], 'deleting the asset removes its shares');
});

test('paste name=: an own asset wins, and two shared ones of one name are an error naming the owners', async () => {
  const f = fakes();
  await f.save(1, 'window', { description: 'Oak' });
  await f.save(3, 'window', { description: 'Spruce' });
  await f.save(1, 'door');
  await f.share(1, 'window');
  await f.share(3, 'window');
  await f.share(1, 'door');
  await assert.rejects(f.call(2, 'get', { name: 'window' }), (e: unknown) => e instanceof AssetError && e.status === 409
    && /^'window' is shared with this server by owner[13], owner[13]; ask one of them to rename theirs$/.test(e.message));
  assert.equal(f.signed.length, 0);
  const listed = (await f.call(2, 'list')).assets as { name: string; by?: string }[];
  assert.deepEqual(listed.map(a => `${a.name}:${a.by}`).sort(), ['door:owner1', 'window:owner1', 'window:owner3']);

  // Each owner gets their own; a user with an own 'window' gets theirs and no longer sees the shared ones of that name.
  assert.equal((await f.call(1, 'get', { name: 'window' })).id, f.rows.find(r => r.owner === 1 && r.name === 'window')!.id);
  assert.equal((await f.call(3, 'get', { name: 'window' })).id, f.rows.find(r => r.owner === 3 && r.name === 'window')!.id);
  await f.save(2, 'window', { description: 'Birch' });
  assert.equal((await f.call(2, 'get', { name: 'window' })).id, f.rows.find(r => r.owner === 2)!.id);
  assert.deepEqual(((await f.call(2, 'list')).assets as { name: string; by?: string }[]).map(a => `${a.name}:${a.by}`),
    ['window:undefined', 'door:owner1']);
  const door = await f.call(2, 'get', { name: 'door' });
  assert.equal(f.signed.at(-1), `users/1/assets/${door.id}/region.bin`);
});

test('the listing stays at 30 with shared assets, own ones first', async () => {
  const f = fakes();
  await f.save(1, 'mine');
  await f.save(2, 'theirs');
  await f.share(2, 'theirs');
  const row = f.rows.find(r => r.owner === 1)!;
  for (let i = 0; i < 28; i++) f.rows.push({ ...row, id: `own${i}`, name: `own${i}` });
  let list = (await f.call(1, 'list')).assets as { name: string; by?: string }[];
  assert.equal(list.length, 30);
  assert.deepEqual(list.at(-1), { name: 'theirs', size: [1, 1, 1], description: '', by: 'owner2' });
  f.rows.push({ ...row, id: 'own28', name: 'own28' });
  list = (await f.call(1, 'list')).assets;
  assert.equal(list.length, 30);
  assert.ok(list.every(a => a.by === undefined));
});
