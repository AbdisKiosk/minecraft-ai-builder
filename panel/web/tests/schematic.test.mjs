import assert from 'node:assert/strict';
import { test } from 'node:test';
import { gunzipSync } from 'node:zlib';
import { decode as readNbt } from '@enginehub/nbt-ts';
import { cells, decode, toSchematic } from '../lib/schematic.ts';

function fixture() {
  return decode({
    box: [-2, 70, 10, -1, 71, 11],
    palette: ['air', 'stone', 'oak_planks', 'dirt'],
    before: [[1, 2], [0, 1], [1, 5]],
    after: [[0, 1], [2, 2], [1, 4], [3, 1]],
    changes: [[0, 'REMOVED'], [1, 'CHANGED'], [2, 'ADDED'], [7, 'CHANGED']],
  });
}

const view = (mode, overrides = {}) => ({ mode, cut: 1, onlyChanged: false, ...overrides });

test('decodes Sponge y/z/x order and world bounds without changing the response palette', () => {
  const diff = fixture();
  assert.deepEqual(diff.origin, [-2, 70, 10]);
  assert.deepEqual([diff.width, diff.height, diff.length, diff.topChange], [2, 2, 2, 1]);
  assert.deepEqual([...diff.before], [1, 1, 0, 1, 1, 1, 1, 1]);
  assert.deepEqual([...diff.after], [0, 2, 2, 1, 1, 1, 1, 3]);
  const data = { box: [0, 0, 0, 0, 0, 0], palette: ['stone'], before: [[0, 1]], after: [[0, 1]], changes: [] };
  assert.equal(decode(data).air, 1);
  assert.deepEqual(data.palette, ['stone']);
});

test('rejects runs that do not cover the region', () => {
  assert.throws(() => decode({ box: [0, 0, 0, 1, 1, 1], palette: ['air'], before: [[0, 7]], after: [[0, 8]], changes: [] }), /7 of 8 cells/);
});

test('decodes the palette objects sent by older plugins', () => {
  const diff = decode({
    box: [0, 0, 0, 0, 0, 0], palette: [{ state: 'stone', rgb: 0x888888, transparent: false }],
    before: [[0, 1]], after: [[0, 1]], changes: [],
  });
  assert.deepEqual(diff.palette, ['stone', 'air']);
});

test('before and after show their own blocks; changes retain removed blocks', () => {
  const diff = fixture();
  assert.deepEqual(cells(diff, view('before')), diff.before);
  assert.deepEqual(cells(diff, view('after')), diff.after);
  assert.deepEqual([...cells(diff, view('changes'))], [1, 2, 2, 1, 1, 1, 1, 3]);
  assert.equal(diff.after[0], 0, 'views must not mutate the decoded data');
});

test('only-changed applies in changes mode, and cut height removes complete upper layers', () => {
  const diff = fixture();
  assert.deepEqual([...cells(diff, view('changes', { onlyChanged: true }))], [1, 2, 2, 0, 0, 0, 0, 3]);
  assert.deepEqual(cells(diff, view('before', { onlyChanged: true })), diff.before);
  assert.deepEqual([...cells(diff, view('changes', { cut: 0 }))], [1, 2, 2, 1, 0, 0, 0, 0]);
});

test('writes gzipped Sponge v2 NBT with a compact palette and the selected cells', async () => {
  const diff = fixture();
  const base64 = await toSchematic(diff, cells(diff, view('changes', { cut: 0 })));
  const { name, value: nbt } = readNbt(gunzipSync(Buffer.from(base64, 'base64')));
  assert.equal(name, 'Schematic');
  assert.equal(Number(nbt.Version), 2);
  assert.deepEqual([nbt.Width, nbt.Height, nbt.Length].map(Number), [2, 2, 2]);
  assert.equal(Number(nbt.PaletteMax), 3);
  assert.deepEqual(Object.keys(nbt.Palette), ['stone', 'oak_planks', 'air']);
  assert.deepEqual([...nbt.BlockData], [0, 1, 1, 0, 2, 2, 2, 2]);
});

test('encodes palette indexes above 127 as unsigned varints', async () => {
  const count = 260;
  const diff = decode({
    box: [0, 0, 0, count - 1, 0, 0], palette: Array.from({ length: count }, (_, i) => `block_${i}`),
    before: [[0, count]], after: Array.from({ length: count }, (_, i) => [i, 1]), changes: [],
  });
  const { value: nbt } = readNbt(gunzipSync(Buffer.from(await toSchematic(diff, diff.after), 'base64')));
  const bytes = [...nbt.BlockData], indexes = [];
  for (let offset = 0; offset < bytes.length;) {
    let value = 0, shift = 0, byte;
    do { byte = bytes[offset++]; value |= (byte & 127) << shift; shift += 7; } while (byte & 128);
    indexes.push(value);
  }
  assert.deepEqual(indexes, Array.from({ length: count }, (_, i) => i));
});

test('uses complete model states in the schematic, leaving compact inspection labels alone', async () => {
  const state = 'oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]';
  const diff = decode({
    box: [0, 0, 0, 0, 0, 0], palette: ['oak_stairs[facing=east]'], models: [state],
    before: [[0, 1]], after: [[0, 1]], changes: [],
  });
  assert.deepEqual(diff.palette, ['oak_stairs[facing=east]', 'air']);
  assert.deepEqual(diff.models, [state, 'air']);
  const { value: nbt } = readNbt(gunzipSync(Buffer.from(await toSchematic(diff, diff.after), 'base64')));
  assert.deepEqual(Object.keys(nbt.Palette), [state]);
});

test('deduplicates compact labels that expand to the same model state', async () => {
  const state = 'oak_log[axis=y]';
  const diff = decode({
    box: [0, 0, 0, 1, 0, 0], palette: ['oak_log', state], models: [state, state],
    before: [[0, 2]], after: [[0, 1], [1, 1]], changes: [],
  });
  const { value: nbt } = readNbt(gunzipSync(Buffer.from(await toSchematic(diff, diff.after), 'base64')));
  assert.equal(Number(nbt.PaletteMax), 1);
  assert.deepEqual([...nbt.BlockData], [0, 0]);
});
