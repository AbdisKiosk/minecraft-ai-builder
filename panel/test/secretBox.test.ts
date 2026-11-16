import assert from 'node:assert/strict';
import { test } from 'node:test';
import { seal, unseal } from '../lib/server/secretBox.ts';

test('a sealed secret opens with the same secret only', () => {
  const sealed = seal('sk-ant-api03-abc', 'one');
  assert.ok(!sealed.includes('sk-ant'));
  assert.equal(unseal(sealed, 'one'), 'sk-ant-api03-abc');
  assert.equal(unseal(sealed, 'two'), null);
});

test('sealing twice gives different text', () => {
  assert.notEqual(seal('x', 'one'), seal('x', 'one'));
});

test('altered or malformed text does not open', () => {
  const parts = seal('secret', 'one').split('.');
  parts[2] = Buffer.from('other!').toString('base64url');
  assert.equal(unseal(parts.join('.'), 'one'), null);
  assert.equal(unseal('nonsense', 'one'), null);
  assert.equal(unseal('v2.a.b.c', 'one'), null);
});
