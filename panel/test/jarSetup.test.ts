import assert from 'node:assert/strict';
import { test } from 'node:test';
import JSZip from 'jszip';
import { addJarEntry, SETUP_ENTRY, setupProperties } from '../lib/server/jarSetup.ts';
import { deriveRelayToken } from '../lib/server/tokens.ts';

test('adds the setup file to a jar and keeps every other entry', async () => {
  const zip = new JSZip();
  zip.file('plugin.yml', 'name: AIBuilder\n');
  zip.file('me/Main.class', Buffer.alloc(5000, 7));
  zip.file('config.yml', 'relay:\n  url: ""\n');
  const jar = await zip.generateAsync({ type: 'nodebuffer', compression: 'DEFLATE', comment: 'built by gradle' });

  const props = setupProperties('wss://panel.example.com', 'aibs_abc');
  const out = addJarEntry(jar, SETUP_ENTRY, Buffer.from(props));
  const read = await JSZip.loadAsync(out, { checkCRC32: true });
  assert.deepEqual(Object.keys(read.files).sort(), ['config.yml', 'me/', 'me/Main.class', 'plugin.yml', SETUP_ENTRY].sort());
  assert.equal(await read.file(SETUP_ENTRY)!.async('string'), props);
  assert.equal(await read.file('plugin.yml')!.async('string'), 'name: AIBuilder\n');
  assert.deepEqual(await read.file('me/Main.class')!.async('nodebuffer'), Buffer.alloc(5000, 7));
});

test('refuses what is not a zip', () => {
  assert.throws(() => addJarEntry(Buffer.from('not a jar at all, just some text here'), 'x', Buffer.from('y')), /not a zip/);
});

test('relay tokens are rebuilt from their seed and differ by seed and key', () => {
  const a = deriveRelayToken('seed-1', 'key');
  assert.match(a, /^aibs_[A-Za-z0-9_-]{43}$/);
  assert.equal(deriveRelayToken('seed-1', 'key'), a);
  assert.notEqual(deriveRelayToken('seed-2', 'key'), a);
  assert.notEqual(deriveRelayToken('seed-1', 'other'), a);
});
