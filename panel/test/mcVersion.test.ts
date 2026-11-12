import assert from 'node:assert/strict';
import { test } from 'node:test';
import { capped, compareVersions, placeWarning, versionParts } from '../lib/mcVersion.ts';

test('versions compare part by part, across the 26.x numbering', () => {
  assert.equal(compareVersions('1.21.4', '1.8.8'), 1);
  assert.equal(compareVersions('1.8.8', '1.21.4'), -1);
  assert.equal(compareVersions('1.21.10', '1.21.9'), 1, 'numbers, not strings');
  assert.equal(compareVersions('1.21', '1.21.0'), 0);
  assert.equal(compareVersions('26.3', '1.21.11'), 1);
  assert.equal(compareVersions('1.21.11', '26.3'), -1);
  assert.equal(compareVersions('26.3', '26.3'), 0);
});

test('unknown or odd versions compare as unknown', () => {
  for (const v of [null, undefined, '', 'unknown', '1.21-pre1', '24w14a', '1..2', '1.2.3.4.5']) {
    assert.equal(versionParts(v), null, String(v));
    assert.equal(compareVersions(v, '1.21'), null, String(v));
    assert.equal(compareVersions('1.21', v), null, String(v));
  }
  assert.deepEqual(versionParts(' 1.20.1 '), [1, 20, 1]);
});

test('placing warns only for a newer asset or an unknown version', () => {
  assert.equal(placeWarning('1.21.4', '1.21.4'), null);
  assert.equal(placeWarning('1.8.8', '1.21.11'), null, 'an older asset is left to the import');
  assert.equal(placeWarning('1.21.11', '26.3'), null);
  assert.match(placeWarning('1.21.4', '1.8.8')!, /from Minecraft 1\.21\.4, newer than that server's 1\.8\.8/);
  assert.match(placeWarning('26.3', '1.21.11')!, /newer/);
  assert.match(placeWarning(null, '1.21.4')!, /saved on is not known/);
  assert.match(placeWarning('1.21.4', undefined)!, /server's version is not known/);
  assert.match(placeWarning('1.21.4', '1.8.8')!, /leave them out below/, 'points at the existing choice');
});

test('a long skipped list is capped with a count', () => {
  const states = Array.from({ length: 25 }, (_, i) => 'block_' + i);
  assert.deepEqual(capped(states, 20), { shown: states.slice(0, 20), more: 5 });
  assert.deepEqual(capped(['a'], 20), { shown: ['a'], more: 0 });
  assert.deepEqual(capped(undefined, 20), { shown: [], more: 0 });
});
