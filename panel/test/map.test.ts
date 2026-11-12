import assert from 'node:assert/strict';
import { test } from 'node:test';
import { levelFor, minScale, tileVersions } from '../lib/mapLayer.ts';
import { TileCache } from '../lib/server/mapTiles.ts';

test('the tile level gives about one tile pixel per screen pixel', () => {
  assert.equal(levelFor(1, 6), 0);
  assert.equal(levelFor(4, 6), 0);
  assert.equal(levelFor(0.5, 6), 1);
  assert.equal(levelFor(0.25, 6), 2);
  assert.equal(levelFor(1 / 1024, 6), 6);
  assert.ok(minScale(6) < 1 / 64);
});

test('tiles above zoom 0 exist where regions do, and change version only with them', () => {
  const regions: [number, number, string][] = [[0, 0, 'a'], [1, 0, 'b'], [-1, -1, 'c'], [5, 2, 'd']];
  const levels = tileVersions(regions, 3);
  assert.deepEqual([...levels[0].keys()].sort(), ['-1,-1', '0,0', '1,0', '5,2']);
  assert.deepEqual([...levels[1].keys()].sort(), ['-1,-1', '0,0', '2,1']);
  assert.deepEqual([...levels[3].keys()].sort(), ['-1,-1', '0,0']);
  const again = tileVersions([...regions].reverse(), 3);
  assert.equal(again[1].get('0,0'), levels[1].get('0,0'), 'order does not matter');
  const changed = tileVersions([[0, 0, 'a'], [1, 0, 'b2'], [-1, -1, 'c'], [5, 2, 'd']], 3);
  assert.notEqual(changed[1].get('0,0'), levels[1].get('0,0'));
  assert.equal(changed[1].get('-1,-1'), levels[1].get('-1,-1'));
});

test('the tile cache keeps the newest version per tile within its size', () => {
  const cache = new TileCache(10);
  cache.put('s|w|0|0|0', 'v1', new Uint8Array(4));
  cache.put('s|w|0|0|0', 'v2', new Uint8Array(4));
  assert.equal(cache.get('s|w|0|0|0')?.v, 'v2');
  cache.put('s|w|0|1|0', 'v1', new Uint8Array(4));
  cache.get('s|w|0|0|0'); // recently used
  cache.put('s|w|0|2|0', 'v1', new Uint8Array(4));
  assert.equal(cache.get('s|w|0|1|0'), null, 'least recently used goes first');
  assert.ok(cache.get('s|w|0|0|0'));
  assert.deepEqual(cache.size, { tiles: 2, bytes: 8 });
  cache.setIndex('s|w', { world: 'w' });
  cache.drop('s');
  assert.deepEqual(cache.size, { tiles: 0, bytes: 0 });
  assert.equal(cache.index('s|w'), undefined);
});

test('concurrent loads of one tile share one request', async () => {
  const cache = new TileCache(100);
  let calls = 0;
  const load = async () => { calls++; return new Uint8Array(1); };
  await Promise.all([cache.once('k', load), cache.once('k', load)]);
  assert.equal(calls, 1);
  await cache.once('k', load);
  assert.equal(calls, 2);
});
