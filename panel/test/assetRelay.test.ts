import assert from 'node:assert/strict';
import { test } from 'node:test';
import { gzipSync } from 'node:zlib';
import { AssetGrants, assetRequest } from '../lib/server/assetRelay.ts';
import { AssetError, MAX_ASSETS, MAX_REGION_BYTES, type AssetRow, type Deps } from '../lib/server/assets.ts';

function region() {
  const raw = Buffer.alloc(12 + 2 + 5 + 8);
  raw.writeInt32BE(0x53413031); raw.writeInt32BE(1, 4); raw.writeInt32BE(1, 8);
  raw.writeUInt16BE(5, 12); raw.write('stone', 14); raw.writeInt32BE(0, 19); raw.writeInt32BE(1, 23);
  return gzipSync(raw);
}

function fakes() {
  const rows: AssetRow[] = [];
  const blobs = new Map<string, Uint8Array>();
  const signed: string[] = [], touched: string[] = [];
  let allowed = true;
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
      async update() {}, async remove() {},
      async touch(user, id) { touched.push(`${user}/${id}`); },
    },
    files: {
      async write(user, id, name, bytes) { blobs.set(`${user}/${id}/${name}`, bytes); },
      read: async (user, id, name) => blobs.get(`${user}/${id}/${name}`) ?? null,
      async removeAll(user, id) { for (const key of blobs.keys()) if (key.startsWith(`${user}/${id}/`)) blobs.delete(key); },
    },
    async requireRole(server, role) {
      assert.equal(server, 's'); assert.equal(role, 'builder');
      if (!allowed) throw new AssetError(403, 'membership revoked');
    },
    relay: async () => { throw new Error('must not ask the plugin to export again'); },
    mcVersion: async () => '1.21.4',
  };
  const call = (user: number, op: string, args = {}) => assetRequest(deps, user, 's', '1.21.4', { op, args }, async key => {
    signed.push(key); return 'https://storage.example/short-lived-url';
  });
  const save = (user: number, name: string, extra = {}) => call(user, 'save', {
    name, size: [1, 1, 1], data: region().toString('base64'), ...extra,
  });
  return { rows, deps, blobs, signed, touched, call, save, revoke: () => { allowed = false; } };
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
