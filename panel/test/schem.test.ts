import assert from 'node:assert/strict';
import { test } from 'node:test';
import { gunzipSync, gzipSync } from 'node:zlib';
import { AssetError, type AssetRow, decodeStates, type Deps, MAX_REGION_VOLUME } from '../lib/server/assets.ts';
import {
  DATA_VERSIONS, encodeStates, MAX_DEPTH, MAX_PALETTE, mcVersion, parseSchematic, readUpload, uploadSchematic,
} from '../lib/server/schem.ts';
import { decode } from '../lib/schematic.ts';
import { MAX_UPLOAD_BYTES, nameFromFile } from '../lib/schemUpload.ts';

// ---- a small NBT writer, so every schematic here is built in the test ----

interface Tag { type: number; payload: Buffer }
const u16 = (n: number) => { const b = Buffer.alloc(2); b.writeUInt16BE(n); return b; };
const i32 = (n: number) => { const b = Buffer.alloc(4); b.writeInt32BE(n); return b; };
const text = (s: string) => { const b = Buffer.from(s, 'utf8'); return Buffer.concat([u16(b.length), b]); };
const byte = (n: number): Tag => ({ type: 1, payload: Buffer.from([n & 0xff]) });
const short = (n: number): Tag => ({ type: 2, payload: u16(n & 0xffff) });
const int = (n: number): Tag => ({ type: 3, payload: i32(n) });
const str = (s: string): Tag => ({ type: 8, payload: text(s) });
const bytes = (b: Uint8Array): Tag => ({ type: 7, payload: Buffer.concat([i32(b.length), b]) });
const ints = (ns: number[]): Tag => ({ type: 11, payload: Buffer.concat([i32(ns.length), ...ns.map(i32)]) });
const list = (type: number, items: Tag[]): Tag => ({ type: 9, payload: Buffer.concat([Buffer.from([type]), i32(items.length), ...items.map(t => t.payload)]) });
/** Entries as a list, so a test can repeat a name. */
const compound = (fields: Record<string, Tag> | [string, Tag][]): Tag => ({
  type: 10,
  payload: Buffer.concat([
    ...(Array.isArray(fields) ? fields : Object.entries(fields)).map(([name, t]) => Buffer.concat([Buffer.from([t.type]), text(name), t.payload])),
    Buffer.from([0]),
  ]),
});
const nbt = (root: Tag, name = '') => Buffer.concat([Buffer.from([10]), text(name), root.payload]);

function varints(ns: number[]): Buffer {
  const out: number[] = [];
  for (let n of ns) {
    while (n >= 0x80) { out.push((n & 0x7f) | 0x80); n = Math.floor(n / 128); }
    out.push(n);
  }
  return Buffer.from(out);
}

interface Spec { size: [number, number, number]; palette: Record<string, number>; data: number[] | Buffer; dataVersion?: number | null }

/** Version 2, as WorldEdit 7.2 writes it: everything at the root, which is named Schematic. */
function v2(s: Spec, extra: Record<string, Tag> = {}): Buffer {
  const palette = compound(Object.fromEntries(Object.entries(s.palette).map(([k, v]) => [k, int(v)])));
  return gzipSync(nbt(compound({
    Version: int(2), ...(s.dataVersion === null ? {} : { DataVersion: int(s.dataVersion ?? 3953) }),
    Width: short(s.size[0]), Height: short(s.size[1]), Length: short(s.size[2]),
    Offset: ints([-5, 3, 7]), PaletteMax: int(Object.keys(s.palette).length), Palette: palette,
    BlockData: bytes(Array.isArray(s.data) ? varints(s.data) : s.data),
    BlockEntities: list(10, [compound({ Pos: ints([0, 0, 0]), Id: str('minecraft:sign'), Text1: str('{"text":"hi"}') })]),
    Entities: list(10, []),
    Metadata: compound({ WEOffsetX: int(-5), WEOffsetY: int(0), WEOffsetZ: int(1) }),
    ...extra,
  }), 'Schematic'));
}

/** Version 3, as WorldEdit 7.3 writes it: an unnamed root holding a Schematic compound, blocks in Blocks. */
function v3(s: Spec): Buffer {
  const palette = compound(Object.fromEntries(Object.entries(s.palette).map(([k, v]) => [k, int(v)])));
  return gzipSync(nbt(compound({
    Schematic: compound({
      Version: int(3), DataVersion: int(s.dataVersion ?? 4189),
      Width: short(s.size[0]), Height: short(s.size[1]), Length: short(s.size[2]), Offset: ints([1, 2, 3]),
      Blocks: compound({
        Palette: palette, Data: bytes(Array.isArray(s.data) ? varints(s.data) : s.data),
        BlockEntities: list(10, [compound({ Pos: ints([1, 0, 0]), Id: str('minecraft:chest'), Data: compound({ Items: list(10, []) }) })]),
      }),
      Biomes: compound({ Palette: compound({ 'minecraft:plains': int(0) }), Data: bytes(Buffer.alloc(24)) }),
      Entities: list(10, []),
      Metadata: compound({ Date: { type: 4, payload: Buffer.alloc(8) }, WorldEdit: compound({ Version: str('7.3.19') }) }),
    }),
  })));
}

/** States of a parsed schematic, cell by cell, in y, z, x order. */
function cells(s: { palette: string[]; runs: [number, number][] }): string[] {
  return s.runs.flatMap(([p, run]) => Array<string>(run).fill(s.palette[p]));
}

const refuses = (file: Buffer, pattern: RegExp) =>
  assert.throws(() => parseSchematic(file), (e: unknown) => e instanceof AssetError && e.status === 400 && pattern.test(e.message), String(pattern));

// A 3 wide (x), 2 high (y), 4 long (z) region whose every cell has its own state, at palette indexes from 150 up, so
// each takes a two-byte varint. A wrong axis order or a misread varint shows up as the wrong state in some cell.
const W = 3, H = 2, L = 4;
const at = (x: number, y: number, z: number) => 150 + x * 100 + y * 10 + z;
function grid(): Spec {
  const palette: Record<string, number> = { 'minecraft:air': 0 };
  for (let i = 1; i < 400; i++) palette[`minecraft:b${i}`] = i;
  // Sponge's index: x + z * Width + y * Width * Length.
  const data: number[] = [];
  for (let y = 0; y < H; y++) for (let z = 0; z < L; z++) for (let x = 0; x < W; x++) data[x + z * W + y * W * L] = at(x, y, z);
  return { size: [W, H, L], palette, data };
}

function checkGrid(states: string[]) {
  assert.equal(states.length, W * H * L);
  // RegionCodec.index: ((y * sizeZ) + z) * sizeX + x, the order of a region saved from a server.
  for (let y = 0; y < H; y++) for (let z = 0; z < L; z++) for (let x = 0; x < W; x++) {
    assert.equal(states[(y * L + z) * W + x], `b${at(x, y, z)}`, `cell ${x} ${y} ${z}`);
  }
}

test('a version 2 schematic decodes cell by cell in y, z, x order', () => {
  const s = parseSchematic(v2(grid()));
  assert.equal(s.version, 2);
  assert.deepEqual(s.size, [3, 2, 4]);
  assert.equal(s.dataVersion, 3953);
  assert.equal(s.mc, '1.21');
  checkGrid(cells(s));
  // Only the states that are used, in order of first use.
  assert.equal(s.palette.length, 24);
});

test('a version 3 schematic decodes the same', () => {
  const s = parseSchematic(v3(grid()));
  assert.equal(s.version, 3);
  assert.equal(s.mc, '1.21.4');
  checkGrid(cells(s));
});

test('states lose the minecraft namespace and air kinds; other namespaces and properties stay', () => {
  const s = parseSchematic(v2({
    size: [5, 1, 1], data: [0, 1, 2, 3, 4],
    palette: { 'minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]': 0, 'minecraft:cave_air': 1,
      'create:shaft[axis=y]': 2, 'minecraft:void_air': 3, stone: 4 },
  }));
  assert.deepEqual(cells(s), ['oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]', 'air', 'create:shaft[axis=y]', 'air', 'stone']);
  assert.deepEqual(s.palette, ['oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]', 'air', 'create:shaft[axis=y]', 'stone']);
});

test('the region data is what StateArrays writes, and decodes back', () => {
  const s = parseSchematic(v2(grid()));
  const back = decodeStates(encodeStates(s), W * H * L);
  assert.deepEqual(cells(back), cells(s));
  // Runs of one state are merged, as StateArrays does.
  const flat = parseSchematic(v2({ size: [4, 1, 1], palette: { stone: 0, dirt: 1 }, data: [0, 0, 0, 1] }));
  assert.deepEqual(decodeStates(encodeStates(flat), 4).runs, [[0, 3], [1, 1]]);
});

test('an upload is saved like any asset, with the 3D view in the same orientation', async () => {
  const { deps, rows, blobs } = fakes();
  const asset = await uploadSchematic(deps, 1, { name: 'grid', description: ' From a file ', tags: ['Test', ' x '] }, v3(grid()));
  assert.deepEqual(asset.size, [3, 2, 4]);
  assert.equal(asset.blocks, 24);
  assert.equal(asset.mc, '1.21.4');
  assert.equal(asset.description, 'From a file');
  assert.deepEqual(asset.tags, ['test', 'x']);
  assert.equal(rows.get(asset.id)!.owner, 1);
  checkGrid(cells(decodeStates(blobs.get(`1/${asset.id}/region`)!, 24)));
  const view = decode(JSON.parse(gunzipSync(blobs.get(`1/${asset.id}/blocks`)!).toString('utf8')));
  assert.deepEqual([view.width, view.height, view.length, ...view.origin], [3, 2, 4, 0, 0, 0]);
  for (let y = 0; y < H; y++) for (let z = 0; z < L; z++) for (let x = 0; x < W; x++) {
    assert.equal(view.palette[view.after[(y * L + z) * W + x]], `b${at(x, y, z)}`);
  }
  // The shared save path's rules apply: a taken name, a bad name, a region of only air.
  await assert.rejects(uploadSchematic(deps, 1, { name: 'grid' }, v3(grid())), (e: unknown) => e instanceof AssetError && e.status === 409);
  await assert.rejects(uploadSchematic(deps, 1, { name: 'Bad Name' }, v3(grid())), (e: unknown) => e instanceof AssetError && e.status === 400);
  await assert.rejects(uploadSchematic(deps, 1, { name: 'air' }, v2({ size: [1, 1, 1], palette: { 'minecraft:air': 0 }, data: [0] })),
    (e: unknown) => e instanceof AssetError && /only air/.test(e.message));
});

test('an unknown or missing data version is stored as unknown', () => {
  assert.equal(parseSchematic(v2({ size: [1, 1, 1], palette: { stone: 0 }, data: [0], dataVersion: 9999 })).mc, null);
  const none = parseSchematic(v2({ size: [1, 1, 1], palette: { stone: 0 }, data: [0], dataVersion: null }));
  assert.equal(none.dataVersion, null);
  assert.equal(none.mc, null);
});

test('the data version table covers releases 1.13 on, in order', () => {
  assert.equal(mcVersion(1519), '1.13');
  assert.equal(mcVersion(2586), '1.16.5');
  assert.equal(mcVersion(3465), '1.20.1');
  assert.equal(mcVersion(4082), '1.21.3');
  assert.equal(mcVersion(5023), '26.3');
  assert.equal(mcVersion(1520), null);
  assert.equal(mcVersion(null), null);
  const parts = (v: string) => v.split('.').map(Number);
  const versions = Object.keys(DATA_VERSIONS).map(Number).sort((a, b) => a - b).map(n => DATA_VERSIONS[n]);
  for (let i = 1; i < versions.length; i++) {
    const [a, b] = [parts(versions[i - 1]), parts(versions[i])];
    const cmp = a[0] - b[0] || a[1] - b[1] || (a[2] ?? 0) - (b[2] ?? 0);
    assert.ok(cmp < 0, `${versions[i - 1]} before ${versions[i]}`);
  }
});

test('other formats are refused with a sentence each', () => {
  const nbtOnly = nbt(compound({ Version: int(2) }));
  refuses(nbtOnly, /not gzipped NBT/);
  refuses(Buffer.from('hello'), /not gzipped NBT/);
  refuses(Buffer.from([0x1f, 0x8b, 1, 2, 3, 4]), /gzip data does not unpack/);
  refuses(gzipSync(Buffer.from('{"json": true}')), /unknown NBT tag type|not NBT/);
  refuses(gzipSync(nbt(compound({
    Width: short(1), Height: short(1), Length: short(1), Materials: str('Alpha'), Blocks: bytes(Buffer.from([1])), Data: bytes(Buffer.from([0])),
  }), 'Schematic')), /old MCEdit \.schematic/);
  refuses(gzipSync(nbt(compound({ Version: int(1), Width: short(1), Height: short(1), Length: short(1),
    Palette: compound({ stone: int(0) }), BlockData: bytes(Buffer.from([0])) }), 'Schematic')), /version 1 Sponge/);
  refuses(gzipSync(nbt(compound({ Version: int(4) }))), /version 4 is not supported/);
  refuses(gzipSync(nbt(compound({ MinecraftDataVersion: int(3953), Version: int(6), Regions: compound({ house: compound({}) }) }))), /Litematica/);
  refuses(gzipSync(nbt(compound({ Width: short(1) }))), /no Version/);
});

test('palettes and block data that do not match are refused', () => {
  const one = (palette: Record<string, number>, data: number[] | Buffer, size: [number, number, number] = [2, 1, 1]) => v2({ size, palette, data });
  refuses(one({ stone: 0, dirt: 1 }, [0, 5]), /palette index 5, which the palette does not have/);
  refuses(one({ stone: 0, dirt: 2 }, [0, 1]), /palette index 1, which the palette does not have/);
  refuses(one({ stone: 0, dirt: 0 }, [0, 0]), /index 0 to more than one block/);
  refuses(one({ stone: -1 }, [0, 0]), /outside/);
  refuses(one({ stone: MAX_PALETTE }, [0, 0]), /outside/);
  refuses(one({ stone: 0 }, [0]), /ends after 1 of 2 blocks/);
  refuses(one({ stone: 0 }, [0, 0, 0]), /longer than 2 × 1 × 1/);
  refuses(one({ stone: 0 }, Buffer.from([0, 0x80])), /ends after 1 of 2/);
  refuses(one({ stone: 0 }, Buffer.from([0, 0x80, 0x80, 0x80, 0x80, 0x80, 0x00])), /longer than 5 bytes/);
  refuses(one({ 'minecraft:Stone': 0 }, [0, 0]), /not a block state/);
  refuses(one({ 'stone[facing=east': 0 }, [0, 0]), /not a block state/);
  refuses(one({ [`b${'x'.repeat(300)}`]: 0 }, [0, 0]), /not a block state/);
  refuses(one({}, []), /empty block palette/);
  refuses(v2({ size: [1, 1, 1], palette: { stone: 0 }, data: [0] }, { Palette: compound({ stone: str('0') }) }), /not a whole number/);
  refuses(v2({ size: [0, 1, 1], palette: { stone: 0 }, data: [] }), /Width is missing or not a positive/);
});

test('sizes are bounded before the block data is read', () => {
  // 2,000,000 blocks is allowed, one row more is not.
  const most = parseSchematic(v2({ size: [200, 100, 100], palette: { stone: 0 }, data: Buffer.alloc(MAX_REGION_VOLUME) }));
  assert.equal(most.length, MAX_REGION_VOLUME);
  assert.deepEqual(most.runs, [[0, MAX_REGION_VOLUME]]);
  refuses(v2({ size: [200, 100, 101], palette: { stone: 0 }, data: [] }), /an asset is at most 2,000,000 blocks/);
  // Unsigned shorts: 65535 wide is not -1 wide.
  refuses(v2({ size: [65535, 65535, 65535], palette: { stone: 0 }, data: [] }), /65535 × 65535 × 65535/);
  // A palette of more than 65,536 entries.
  const huge: Record<string, number> = {};
  for (let i = 0; i <= MAX_PALETTE; i++) huge[`b${i}`] = i % MAX_PALETTE;
  refuses(v2({ size: [1, 1, 1], palette: huge, data: [0] }), /more than 65,536 entries/);
});

test('hostile NBT is refused without allocating from its lengths', () => {
  const base = nbt(compound({ Version: int(2) }), 'Schematic');
  refuses(gzipSync(base.subarray(0, base.length - 3)), /ends in the middle/);
  refuses(gzipSync(Buffer.concat([base, Buffer.from([0])])), /data after its end/);
  const withExtra = (t: Tag) => gzipSync(nbt(compound({ Junk: t, Version: int(2) })));
  // Lengths far beyond the file: a 2 GB byte array, int array and list, refused before anything is read.
  refuses(withExtra({ type: 7, payload: i32(0x7fffffff) }), /array longer than the file/);
  refuses(withExtra({ type: 11, payload: i32(0x7fffffff) }), /array longer than the file/);
  refuses(withExtra({ type: 9, payload: Buffer.concat([Buffer.from([10]), i32(0x7fffffff)]) }), /array longer than the file/);
  refuses(withExtra({ type: 9, payload: Buffer.concat([Buffer.from([0]), i32(1_000_000)]) }), /list of nothing/);
  refuses(withExtra({ type: 7, payload: i32(-1) }), /negative length/);
  refuses(withExtra({ type: 8, payload: u16(60000) }), /ends in the middle/);
  refuses(withExtra({ type: 13, payload: Buffer.alloc(0) }), /unknown NBT tag type 13/);
  // Nesting, in a field that is skipped and in one that is kept.
  let deep: Tag = compound({});
  for (let i = 0; i < MAX_DEPTH + 5; i++) deep = compound({ a: deep });
  refuses(withExtra(deep), /nests its data more than 64 deep/);
  let deepList: Tag = list(10, []);
  for (let i = 0; i < MAX_DEPTH + 5; i++) deepList = list(9, [deepList]);
  refuses(withExtra(deepList), /nests/);
  refuses(gzipSync(nbt(compound([['Version', int(2)], ['Version', int(3)]]))), /repeats the field 'Version'/);
  // Nesting that stays under the bound is fine where nothing reads it.
  let ok: Tag = compound({});
  for (let i = 0; i < 40; i++) ok = compound({ a: ok });
  assert.equal(parseSchematic(v2({ size: [1, 1, 1], palette: { stone: 0 }, data: [0] }, { Metadata: ok })).palette[0], 'stone');
});

test('a file that unpacks to more than 64 MB is refused', () => {
  refuses(gzipSync(Buffer.alloc(64 * 1024 * 1024 + 1)), /unpacks to more than 64 MB/);
});

test('names from file names are cleaned to the asset rule', () => {
  assert.equal(nameFromFile('My House (2).schem'), 'my-house-2');
  assert.equal(nameFromFile('C:\\builds\\Tower_v2.SCHEM'), 'tower_v2');
  assert.equal(nameFromFile('/home/me/oak-window.schem'), 'oak-window');
  assert.equal(nameFromFile('Æble træ.schem'), 'ble-tr');
  assert.equal(nameFromFile('...schem'), '');
  assert.equal(nameFromFile('.schem'), '');
  const long = nameFromFile(`${'a'.repeat(39)} b.schem`);
  assert.equal(long, 'a'.repeat(39));
  assert.match(nameFromFile(`${'word '.repeat(20)}.schem`), /^[a-z0-9_-]{1,40}$/);
});

// ---- the upload's form ----

function form(file: Blob | null, fields: Record<string, string> = { name: 'tower' }) {
  const f = new FormData();
  if (file) f.set('file', file, 'tower.schem');
  for (const [k, v] of Object.entries(fields)) f.set(k, v);
  return new Request('http://panel.test/api/assets/upload', { method: 'POST', body: f });
}

test('the form gives the file and its fields', async () => {
  const file = v2(grid());
  const r = await readUpload(form(new Blob([new Uint8Array(file)]), { name: 'tower', description: 'tall', tags: 'stone, Tall' }));
  assert.deepEqual(Buffer.from(r.file), file);
  assert.deepEqual(r.fields, { name: 'tower', description: 'tall', tags: ['stone', ' Tall'] });
});

test('uploads are bounded at 8 MB', async () => {
  const status = (p: Promise<unknown>, s: number) => assert.rejects(p, (e: unknown) => e instanceof AssetError && e.status === s);
  // A file of exactly 8 MB is read; one byte more is not.
  assert.equal((await readUpload(form(new Blob([new Uint8Array(MAX_UPLOAD_BYTES)])))).file.byteLength, MAX_UPLOAD_BYTES);
  await status(readUpload(form(new Blob([new Uint8Array(MAX_UPLOAD_BYTES + 1)]))), 413);
  // A declared length too large is refused before the body is read.
  let pulled = false;
  const unread = new ReadableStream({ pull() { pulled = true; throw new Error('read'); } }, { highWaterMark: 0 });
  await status(readUpload(new Request('http://panel.test/', {
    method: 'POST', body: unread, duplex: 'half',
    headers: { 'content-type': 'multipart/form-data; boundary=x', 'content-length': String(20 * 1024 * 1024) },
  } as RequestInit)), 413);
  assert.equal(pulled, false);
  // A body that goes on without a length is cut off while it is read.
  let sent = 0;
  const endless = new ReadableStream({ pull(c) { sent += 1024 * 1024; c.enqueue(new Uint8Array(1024 * 1024)); } });
  await status(readUpload(new Request('http://panel.test/', {
    method: 'POST', body: endless, duplex: 'half', headers: { 'content-type': 'multipart/form-data; boundary=x' },
  } as RequestInit)), 413);
  assert.ok(sent <= MAX_UPLOAD_BYTES + 2 * 1024 * 1024, `read ${sent} bytes`);
  await status(readUpload(new Request('http://panel.test/', { method: 'POST', body: 'x', headers: { 'content-type': 'text/plain' } })), 415);
  await status(readUpload(form(null)), 400);
  await status(readUpload(form(new Blob([]))), 400);
  await status(uploadSchematic(fakes().deps, 1, { name: 'big' }, new Uint8Array(MAX_UPLOAD_BYTES + 1)), 413);
});

/** In-memory MySQL and R2; the upload needs no relay or server role. */
function fakes() {
  const rows = new Map<string, AssetRow>();
  const blobs = new Map<string, Uint8Array>();
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
      async insert(row, check) { check(usage(row.owner)); rows.set(row.id, row); },
      async update() {},
      async remove() {},
      async touch() {},
    },
    files: {
      write: async (owner, id, name, b) => { blobs.set(`${owner}/${id}/${name}`, b); },
      read: async (owner, id, name) => blobs.get(`${owner}/${id}/${name}`) ?? null,
      removeAll: async (owner, id) => { for (const k of [...blobs.keys()]) if (k.startsWith(`${owner}/${id}/`)) blobs.delete(k); },
    },
    requireRole: async () => { throw new Error('an upload needs no server role'); },
    relay: async () => { throw new Error('an upload does not use the relay'); },
    mcVersion: async () => { throw new Error('an upload takes its version from the file'); },
  };
  return { deps, rows, blobs };
}
