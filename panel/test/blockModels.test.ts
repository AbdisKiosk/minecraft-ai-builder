import assert from 'node:assert/strict';
import { test } from 'node:test';
import JSZip from 'jszip';
import { blockProperties, completeState, defaultValue, modelStates } from '../lib/blockModels.ts';

const STAIRS = {
  variants: Object.fromEntries(['east', 'north', 'south', 'west'].flatMap(facing => ['bottom', 'top'].flatMap(half =>
    ['inner_left', 'outer_right', 'straight'].map(shape => [`facing=${facing},half=${half},shape=${shape}`, { model: 'x' }])))),
};
const FENCE = {
  multipart: [
    { apply: { model: 'post' } },
    { when: { north: 'true' }, apply: { model: 'side' } },
    { when: { OR: [{ east: 'true' }, { west: 'true|false' }] }, apply: { model: 'side' } },
  ],
};

test('reads properties from variants and multipart', () => {
  assert.deepEqual(blockProperties(STAIRS).get('shape'), ['inner_left', 'outer_right', 'straight']);
  const fence = blockProperties(FENCE);
  assert.deepEqual([...fence.keys()], ['north', 'east', 'west']);
  assert.deepEqual(fence.get('north'), ['true', 'false'], 'the value that adds no part is implied');
  assert.deepEqual(fence.get('west'), ['true', 'false']);
  const wall = blockProperties({ multipart: [{ when: { north: 'low|tall' } }] });
  assert.deepEqual(wall.get('north'), ['low', 'tall', 'none']);
  assert.equal(blockProperties({ variants: { '': { model: 'stone' } } }).size, 0);
});

test('fills left-out properties with Minecraft\'s defaults, sorted like the plugin\'s model states', () => {
  assert.equal(completeState('oak_stairs[facing=east]', blockProperties(STAIRS)), 'oak_stairs[facing=east,half=bottom,shape=straight]');
  assert.equal(completeState('oak_fence', blockProperties(FENCE)), 'oak_fence[east=false,north=false,west=false]');
  assert.equal(completeState('stone', undefined), 'stone');
  assert.equal(defaultValue('oak_log', 'axis', ['x', 'y', 'z']), 'y');
  assert.equal(defaultValue('oak_door', 'half', ['upper', 'lower']), 'lower');
  assert.equal(defaultValue('hopper', 'facing', ['down', 'north', 'south']), 'down');
  assert.equal(defaultValue('cobblestone_wall', 'up', ['true', 'false']), 'true');
  assert.equal(defaultValue('snow', 'layers', ['1', '2', '8']), '1');
  assert.equal(defaultValue('nether_portal', 'axis', ['x', 'z']), 'x');
});

test('reads blockstates from the client jar', async () => {
  const zip = new JSZip();
  zip.file('assets/minecraft/blockstates/oak_stairs.json', JSON.stringify(STAIRS));
  const jar = await zip.generateAsync({ type: 'arraybuffer' });
  assert.deepEqual(await modelStates(['oak_stairs[half=top]', 'air', 'mod:thing'], jar),
    ['oak_stairs[facing=north,half=top,shape=straight]', 'air', 'mod:thing']);
});
