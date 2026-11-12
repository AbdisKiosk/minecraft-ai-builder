import assert from 'node:assert/strict';
import { test } from 'node:test';
import { gzipSync } from 'node:zlib';
import {
  AssetError, type AssetRow, checkAnchor, checkFacing, checkName, checkQuota, checkTags, createAsset, decodeStates, deleteAsset, type Deps, getAsset,
  listAssets, MAX_ASSETS, MAX_BYTES, MAX_REGION_BYTES, placeAsset, solidBlocks, assetBlocks, updateAsset, viewerBlocks,
} from '../lib/server/assets.ts';
import { decode } from '../lib/schematic.ts';
import { anchorColumns, row as fromDb } from '../lib/server/assetRow.ts';

/** What StateArrays.encode writes (palette + runs, gzipped), for a list of states in y, z, x order. */
function encode(states: string[]): Buffer {
  const palette = new Map<string, number>();
  for (const s of states) if (!palette.has(s)) palette.set(s, palette.size);
  const parts: Buffer[] = [];
  const int = (n: number) => { const b = Buffer.alloc(4); b.writeInt32BE(n); parts.push(b); };
  int(0x53413031);
  int(states.length);
  int(palette.size);
  for (const s of palette.keys()) {
    const bytes = Buffer.from(s, 'utf8');
    const len = Buffer.alloc(2);
    len.writeUInt16BE(bytes.length);
    parts.push(len, bytes);
  }
  for (let i = 0; i < states.length;) {
    let run = 1;
    while (i + run < states.length && states[i + run] === states[i]) run++;
    int(palette.get(states[i])!);
    int(run);
    i += run;
  }
  return gzipSync(Buffer.concat(parts));
}

// A 2x2x2 region: a stone floor and one oak stair on top.
const STATES = ['stone', 'stone', 'stone', 'stone', 'air', 'oak_stairs[facing=east]', 'air', 'air'];
const BOX = [10, 64, 20, 11, 65, 21];

const user = (id: number) => id;

/** In-memory MySQL, R2 and relay; `roles` is what each user is on each server. */
function fakes(roles: Record<string, string> = { 'a:1': 'builder', 'b:1': 'builder', 'a:2': 'builder' }) {
  const rows = new Map<string, AssetRow>();
  const blobs = new Map<string, Uint8Array>();
  const frames: { server: string; frame: any }[] = [];
  const checked: string[] = [];
  let me = 1;
  const usage = (owner: number) => {
    const mine = [...rows.values()].filter(r => r.owner === owner);
    return { count: mine.length, bytes: mine.reduce((n, r) => n + r.bytes, 0) };
  };
  const deps: Deps = {
    store: {
      list: async owner => [...rows.values()].filter(r => r.owner === owner),
      get: async id => rows.get(id) ?? null,
      byName: async (owner, name) => [...rows.values()].find(r => r.owner === owner && r.name === name) ?? null,
      usage: async owner => usage(owner),
      async insert(row, check) {
        check(usage(row.owner));
        if ([...rows.values()].some(r => r.owner === row.owner && r.name === row.name)) throw new AssetError(409, 'taken');
        rows.set(row.id, row);
      },
      async update(owner, id, changes) {
        const r = rows.get(id);
        if (r && r.owner === owner) rows.set(id, { ...r, ...changes });
      },
      async remove(owner, id) {
        if (rows.get(id)?.owner === owner) rows.delete(id);
      },
      async touch() {},
    },
    files: {
      write: async (owner, id, name, bytes) => { blobs.set(`${owner}/${id}/${name}`, bytes); },
      read: async (owner, id, name) => blobs.get(`${owner}/${id}/${name}`) ?? null,
      removeAll: async (owner, id) => { for (const k of [...blobs.keys()]) if (k.startsWith(`${owner}/${id}/`)) blobs.delete(k); },
    },
    async requireRole(server, role) {
      checked.push(`${server}:${role}`);
      const mine = roles[`${server}:${me}`];
      if (!mine) throw new AssetError(404, `unknown server '${server}'`);
      if (role === 'builder' && mine === 'viewer') throw new AssetError(403, 'needs builder');
    },
    async relay(server, frame) {
      frames.push({ server, frame });
      if (frame.op === 'export') return { status: 200, body: { world: frame.args.world, box: frame.args.box, data: encode(STATES).toString('base64') } };
      return { status: 200, body: { job: 'j1', status: 'done', draft: 'd1', skipped: [] } };
    },
    mcVersion: async () => '1.21.4',
  };
  return { deps, rows, blobs, frames, checked, as: (id: number) => { me = id; } };
}

const save = (deps: Deps, owner: number, name: string, extra: Record<string, unknown> = {}) =>
  createAsset(deps, owner, { server: 'a', world: 'world', box: BOX, name, ...extra });

const rejects = (p: Promise<unknown>, status: number) =>
  assert.rejects(p, (e: unknown) => e instanceof AssetError && e.status === status);

test('names follow the saved blueprint rule', () => {
  for (const ok of ['oak-window', 'pillar_2', 'a', 'x'.repeat(40)]) assert.equal(checkName(ok), ok);
  for (const bad of ['', 'Oak', 'oak window', 'x'.repeat(41), 'ö', '../x', 7, null, undefined]) {
    assert.throws(() => checkName(bad), (e: unknown) => e instanceof AssetError && e.status === 400, String(bad));
  }
});

test('tags are trimmed, lowercased, unique and limited', () => {
  assert.deepEqual(checkTags([' Window', 'oak', 'window', '']), ['window', 'oak']);
  assert.deepEqual(checkTags(undefined), []);
  assert.throws(() => checkTags('window'), AssetError);
  assert.throws(() => checkTags(['-x']), AssetError);
  assert.throws(() => checkTags(Array.from({ length: 11 }, (_, i) => `t${i}`)), AssetError);
});

test('the quota counts assets and bytes', () => {
  checkQuota({ count: MAX_ASSETS - 1, bytes: 0 }, 1, 0);
  assert.throws(() => checkQuota({ count: MAX_ASSETS, bytes: 0 }, 1, 0), /library is full/);
  checkQuota({ count: 0, bytes: MAX_BYTES - 10 }, 1, 10);
  assert.throws(() => checkQuota({ count: 0, bytes: MAX_BYTES - 10 }, 1, 11), /MB left/);
});

test('the export data turns into the 3D view blocks, in the asset\'s own coordinates', () => {
  const states = decodeStates(encode(STATES), 8);
  assert.deepEqual(states.palette, ['stone', 'air', 'oak_stairs[facing=east]']);
  assert.deepEqual(states.runs, [[0, 4], [1, 1], [2, 1], [1, 2]]);
  assert.equal(solidBlocks(states), 5);
  const blocks = viewerBlocks([2, 2, 2], states);
  const diff = decode({ ...blocks, models: blocks.palette });
  assert.deepEqual(diff.origin, [0, 0, 0]);
  assert.deepEqual([diff.width, diff.height, diff.length], [2, 2, 2]);
  assert.deepEqual([...diff.after].map(i => diff.palette[i]), STATES);
  assert.throws(() => decodeStates(encode(STATES), 7), /8 blocks/);
  assert.throws(() => decodeStates(gzipSync(Buffer.from('nope')), 8), AssetError);
});

test('saving reads the region from the server and stores both files', async () => {
  const f = fakes();
  const a = await save(f.deps, user(1), 'stair', { description: ' A stair ', tags: ['Oak'] });
  assert.deepEqual(f.checked, ['a:builder']);
  assert.deepEqual(f.frames, [{ server: 'a', frame: { t: 'draft', op: 'export', args: { world: 'world', box: BOX } } }]);
  assert.deepEqual([a.name, a.description, a.tags, a.size, a.blocks, a.mc], ['stair', 'A stair', ['oak'], [2, 2, 2], 5, '1.21.4']);
  assert.deepEqual([...f.blobs.keys()].sort(), [`1/${a.id}/blocks`, `1/${a.id}/region`]);
  assert.equal(a.bytes, [...f.blobs.values()].reduce((n, b) => n + b.byteLength, 0));
  assert.deepEqual(Buffer.from(f.blobs.get(`1/${a.id}/region`)!), encode(STATES), 'the region is kept as the export sent it');
  const blocks = await assetBlocks(f.deps, 1, a.id) as { palette: string[] };
  assert.deepEqual(blocks.palette, ['stone', 'air', 'oak_stairs[facing=east]']);
});

test('saving needs builder on the source server, a free name and room in the library', async () => {
  const f = fakes({ 'a:1': 'viewer' });
  await rejects(save(f.deps, 1, 'x'), 403);
  const g = fakes({});
  await rejects(save(g.deps, 1, 'x'), 404);
  assert.equal(f.frames.length + g.frames.length, 0, 'the server is not asked');

  const h = fakes();
  await save(h.deps, 1, 'stair');
  await rejects(save(h.deps, 1, 'stair'), 409);
  for (let i = 1; i < MAX_ASSETS; i++) h.rows.set(`fill${i}`, { ...[...h.rows.values()][0], id: `fill${i}`, name: `n${i}` });
  const before = h.frames.length;
  await rejects(save(h.deps, 1, 'one-more'), 409);
  assert.equal(h.frames.length, before, 'a full library refuses before reading the region');
  await rejects(save(h.deps, 1, 'Bad Name'), 400);
});

test('a region that is only air is not saved', async () => {
  const f = fakes();
  f.deps.relay = async () => ({ status: 200, body: { data: encode(Array(8).fill('air')).toString('base64') } });
  await rejects(save(f.deps, 1, 'nothing'), 400);
  assert.equal(f.blobs.size, 0);
});

test('a server error is passed on, and nothing is stored', async () => {
  const f = fakes();
  f.deps.relay = async () => ({ status: 400, body: { error: 'region is 9000000 blocks; promote is limited to 2000000' } });
  await assert.rejects(save(f.deps, 1, 'big'), /promote is limited/);
  assert.equal(f.rows.size + f.blobs.size, 0);
});

test('nobody reads, changes, deletes or places another user\'s asset', async () => {
  const f = fakes();
  const a = await save(f.deps, 1, 'stair');
  f.as(2);
  f.frames.length = 0;
  await rejects(getAsset(f.deps, 2, a.id), 404);
  await rejects(assetBlocks(f.deps, 2, a.id), 404);
  await rejects(updateAsset(f.deps, 2, a.id, { name: 'mine' }), 404);
  await rejects(deleteAsset(f.deps, 2, a.id), 404);
  await rejects(placeAsset(f.deps, 2, a.id, { server: 'a', world: 'world', origin: [0, 64, 0] }), 404);
  assert.deepEqual((await listAssets(f.deps, 2)).assets, []);
  assert.equal(f.frames.length, 0);
  assert.equal(f.rows.get(a.id)?.name, 'stair');
  assert.equal(f.blobs.size, 2);
  await rejects(getAsset(f.deps, 1, '../../x'), 404);
});

test('the owner renames, describes, tags, places and deletes', async () => {
  const f = fakes();
  const a = await save(f.deps, 1, 'stair');
  await save(f.deps, 1, 'other');
  await rejects(updateAsset(f.deps, 1, a.id, { name: 'other' }), 409);
  const b = await updateAsset(f.deps, 1, a.id, { name: 'oak-stair', description: 'east', tags: ['oak'] });
  assert.deepEqual([b.name, b.description, b.tags], ['oak-stair', 'east', ['oak']]);
  await rejects(updateAsset(f.deps, 1, a.id, {}), 400);

  f.checked.length = 0;
  const reply = await placeAsset(f.deps, 1, a.id, { server: 'b', world: 'world_nether', origin: [5, 70, -3], air: false });
  assert.deepEqual(f.checked, ['b:builder'], 'placing needs builder on the target');
  assert.deepEqual(reply.body, { job: 'j1', status: 'done', draft: 'd1', skipped: [] });
  const { server, frame } = f.frames.at(-1)!;
  assert.equal(server, 'b');
  assert.deepEqual({ ...frame.args, data: undefined }, {
    world: 'world_nether', origin: [5, 70, -3], size: [2, 2, 2], data: undefined, air: false, skipUnknown: false, label: 'asset oak-stair',
  });
  assert.equal(frame.op, 'import');
  assert.deepEqual(Buffer.from(frame.args.data, 'base64'), encode(STATES));

  const viewer = fakes({ 'a:1': 'builder', 'c:1': 'viewer' });
  const c = await save(viewer.deps, 1, 'stair');
  await rejects(placeAsset(viewer.deps, 1, c.id, { server: 'c', world: 'world', origin: [0, 0, 0] }), 403);
  await rejects(placeAsset(viewer.deps, 1, c.id, { server: 'c', world: 'world', origin: [0, 0] }), 400);

  await deleteAsset(f.deps, 1, a.id);
  assert.equal(f.rows.has(a.id), false);
  assert.deepEqual([...f.blobs.keys()].filter(k => k.includes(a.id)), []);
  assert.equal((await listAssets(f.deps, 1)).quota.count, 1);
});

test('Save as asset names the 2,000,000-block and 5 MB limits and asks for a smaller region', async () => {
  const f = fakes();
  // 201 x 100 x 100: refused before the server is asked to read anything.
  await assert.rejects(save(f.deps, user(1), 'huge', { box: [0, 0, 0, 200, 99, 99] }), (e: unknown) => e instanceof AssetError
    && e.status === 400 && e.message === 'this region is 2,010,000 blocks; an asset is at most 2,000,000 blocks. Select a smaller region.');
  assert.deepEqual(f.frames, []);
  // Exactly 2,000,000 blocks is allowed past that check.
  f.deps.relay = async () => ({ status: 200, body: { data: Buffer.alloc(MAX_REGION_BYTES + 1).toString('base64') } });
  await assert.rejects(save(f.deps, user(1), 'dense', { box: [0, 0, 0, 199, 99, 99] }), (e: unknown) => e instanceof AssetError
    && e.status === 400 && /^the region's block data is 5\.00 MB compressed; an asset is at most 5 MB\. Select a smaller region\.$/.test(e.message));
  assert.equal(f.rows.size + f.blobs.size, 0);
});

test('a failing usage update does not fail a place that happened', async () => {
  const f = fakes();
  const a = await save(f.deps, user(1), 'stair');
  f.deps.store.touch = async () => { throw new Error('database is read-only'); };
  const reply = await placeAsset(f.deps, user(1), a.id, { server: 'a', world: 'world', origin: [0, 64, 0] });
  assert.equal(reply.status, 200);
});

test('an anchor lies inside the asset and a facing is one of six; empty means none', () => {
  assert.deepEqual(checkAnchor([0, 0, 0], [3, 2, 1]), [0, 0, 0]);
  assert.deepEqual(checkAnchor([2, 1, 0], [3, 2, 1]), [2, 1, 0]);
  for (const none of [undefined, null, '']) assert.equal(checkAnchor(none, [3, 2, 1]), null);
  for (const bad of [[3, 0, 0], [0, 2, 0], [0, 0, 1], [-1, 0, 0], [0.5, 0, 0], [0, 0], '0 0 0', [0, 0, '0']]) {
    assert.throws(() => checkAnchor(bad, [3, 2, 1]), (e: unknown) => e instanceof AssetError && e.status === 400
      && e.message === 'anchor must be [x,y,z] inside the asset, from 0 0 0 to 2 1 0', JSON.stringify(bad));
  }
  for (const f of ['north', 'south', 'east', 'west', 'up', 'down']) assert.equal(checkFacing(f), f);
  for (const none of [undefined, null, '']) assert.equal(checkFacing(none), null);
  for (const bad of ['North', 'n', 'sideways', 90]) {
    assert.throws(() => checkFacing(bad), /facing must be one of north, south, east, west, up, down/);
  }
});

test('Save as asset stores an anchor and facing, refused before the server reads when out of bounds', async () => {
  const f = fakes();
  const plain = await save(f.deps, 1, 'plain');
  assert.deepEqual([plain.anchor, plain.facing], [null, null], 'none unless given');
  const a = await save(f.deps, 1, 'stair', { anchor: [1, 0, 1], facing: 'east' });
  assert.deepEqual([a.anchor, a.facing], [[1, 0, 1], 'east']);
  assert.deepEqual([f.rows.get(a.id)!.anchor, f.rows.get(a.id)!.facing], [[1, 0, 1], 'east']);
  const before = f.frames.length;
  await rejects(save(f.deps, 1, 'out', { anchor: [2, 0, 0] }), 400);
  await rejects(save(f.deps, 1, 'tilted', { facing: 'northeast' }), 400);
  assert.equal(f.frames.length, before);
});

test('Edit sets, changes and clears an anchor and facing on any asset', async () => {
  const f = fakes();
  const a = await save(f.deps, 1, 'stair');
  const b = await updateAsset(f.deps, 1, a.id, { anchor: [1, 1, 0], facing: 'up' });
  assert.deepEqual([b.anchor, b.facing, b.name], [[1, 1, 0], 'up', 'stair']);
  const c = await updateAsset(f.deps, 1, a.id, { facing: null });
  assert.deepEqual([c.anchor, c.facing], [[1, 1, 0], null], 'each is cleared on its own');
  const d = await updateAsset(f.deps, 1, a.id, { anchor: null });
  assert.deepEqual([d.anchor, d.facing], [null, null]);
  await rejects(updateAsset(f.deps, 1, a.id, { anchor: [0, 2, 0] }), 400);
  await rejects(updateAsset(f.deps, 1, a.id, { facing: 'left' }), 400);
  f.as(2);
  await rejects(updateAsset(f.deps, 2, a.id, { anchor: [0, 0, 0] }), 404);
});

test('a MySQL row reads its anchor only when all three cells are set, and writes NULL for none', () => {
  const db = {
    id: 'abcdefghijkm', user_id: 1, name: 'w', description: '', tags: '["oak"]', size_x: 3, size_y: 2, size_z: 1,
    block_count: 4, mc_version: '1.21.4', bytes: '10', created_at: new Date(1), updated_at: new Date(2),
    anchor_x: null, anchor_y: null, anchor_z: null, facing: null,
  };
  const legacy = fromDb(db);
  assert.deepEqual([legacy.anchor, legacy.facing, legacy.tags, legacy.bytes], [null, null, ['oak'], 10]);
  const set = fromDb({ ...db, anchor_x: 2, anchor_y: 1, anchor_z: 0, facing: 'west' });
  assert.deepEqual([set.anchor, set.facing], [[2, 1, 0], 'west']);
  assert.equal(fromDb({ ...db, anchor_x: 2, anchor_y: null, anchor_z: 0 }).anchor, null);
  assert.equal(fromDb({ ...db, facing: 'sideways' }).facing, null);
  assert.deepEqual(anchorColumns({ anchor: null, facing: null }), [null, null, null, null]);
  assert.deepEqual(anchorColumns({ anchor: [2, 1, 0], facing: 'down' }), [2, 1, 0, 'down']);
});
