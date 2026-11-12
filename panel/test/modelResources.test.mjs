import assert from 'node:assert/strict';
import { test } from 'node:test';
import JSZip from 'jszip';
import { modelOverrides } from '../lib/modelResources.ts';

test('leaves older string-only texture models alone', async () => {
  const jar = new JSZip();
  jar.file('assets/minecraft/models/block/stone.json', JSON.stringify({ textures: { all: 'minecraft:block/stone' } }));
  assert.equal(await modelOverrides(await jar.generateAsync({ type: 'arraybuffer' })), null);
});

test('normalises modern sprite objects in an override pack without copying jar assets', async () => {
  const jar = new JSZip();
  const path = 'assets/minecraft/models/block/black_stained_glass.json';
  jar.file(path, JSON.stringify({
    parent: 'minecraft:block/cube_all',
    textures: { all: { sprite: 'minecraft:block/black_stained_glass', force_translucent: true }, particle: '#all' },
  }));
  jar.file('assets/minecraft/textures/block/black_stained_glass.png', 'fake png');
  jar.file('assets/minecraft/models/block/stone.json', JSON.stringify({ textures: { all: 'minecraft:block/stone' } }));
  const result = await modelOverrides(await jar.generateAsync({ type: 'arraybuffer' }));
  assert.ok(result);
  const overrides = await JSZip.loadAsync(await result.arrayBuffer());
  assert.deepEqual(Object.values(overrides.files).filter(file => !file.dir).map(file => file.name), [path]);
  assert.deepEqual(JSON.parse(await overrides.file(path).async('string')), {
    parent: 'minecraft:block/cube_all', textures: { all: 'minecraft:block/black_stained_glass', particle: '#all' },
  });
});
