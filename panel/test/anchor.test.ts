import assert from 'node:assert/strict';
import { test } from 'node:test';
import { anchorInside, cornerFromAnchor, FACING_STEP, parseAnchor, parsePosition } from '../lib/anchor.ts';

test('positions are three whole numbers, negatives allowed', () => {
  assert.deepEqual(parsePosition('100 64 -20'), [100, 64, -20]);
  assert.deepEqual(parsePosition(' 1, 2,3 '), [1, 2, 3]);
  for (const bad of ['', '  ', '1 2', '1 2 3 4', '1.5 2 3', 'a b c', '1 2 x']) assert.equal(parsePosition(bad), null, bad);
});

test('an anchor is empty (none), or three whole numbers of 0 or more', () => {
  assert.equal(parseAnchor(''), null);
  assert.equal(parseAnchor('   '), null);
  assert.deepEqual(parseAnchor('1 0 2'), [1, 0, 2]);
  assert.equal(parseAnchor('-1 0 2'), undefined);
  assert.equal(parseAnchor('1 0'), undefined);
  assert.equal(parseAnchor('1 0 0.5'), undefined);
});

test('an anchor lies inside the asset', () => {
  assert.ok(anchorInside([0, 0, 0], [3, 2, 2]));
  assert.ok(anchorInside([2, 1, 1], [3, 2, 2]));
  assert.ok(!anchorInside([3, 0, 0], [3, 2, 2]));
  assert.ok(!anchorInside([0, 2, 0], [3, 2, 2]));
  assert.ok(!anchorInside([0, 0, -1], [3, 2, 2]));
});

test('the corner puts the anchor cell at the given spot, as AnchorTest does without a turn', () => {
  // AnchorTest: cell 2,1,0 at 10,64,10 facing its own way pastes from 8,63,10.
  assert.deepEqual(cornerFromAnchor([10, 64, 10], [2, 1, 0]), [8, 63, 10]);
  // Anchor 1,0,1 of an "up" asset at 10,64,10 pastes from 9,64,9.
  assert.deepEqual(cornerFromAnchor([10, 64, 10], [1, 0, 1]), [9, 64, 9]);
  assert.deepEqual(cornerFromAnchor([-5, 70, -3], [0, 0, 0]), [-5, 70, -3]);
  // Adding the anchor back gives the spot.
  const at: [number, number, number] = [-12, 5, 40], anchor: [number, number, number] = [4, 3, 1];
  assert.deepEqual(cornerFromAnchor(at, anchor).map((n, i) => n + anchor[i]), at);
});

test('facings point along Minecraft axes: north is -z, east +x, up +y', () => {
  assert.deepEqual(FACING_STEP.north, [0, 0, -1]);
  assert.deepEqual(FACING_STEP.south, [0, 0, 1]);
  assert.deepEqual(FACING_STEP.east, [1, 0, 0]);
  assert.deepEqual(FACING_STEP.west, [-1, 0, 0]);
  assert.deepEqual(FACING_STEP.up, [0, 1, 0]);
  assert.deepEqual(FACING_STEP.down, [0, -1, 0]);
});
