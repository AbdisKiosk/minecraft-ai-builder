import assert from 'node:assert/strict';
import { test } from 'node:test';
import { bearer, newToken, sha256, shortId } from '../lib/server/tokens.ts';

test('tokens carry their prefix and are unique', () => {
  const a = newToken('aibs_'), b = newToken('aibs_');
  assert.match(a, /^aibs_[A-Za-z0-9_-]{43}$/);
  assert.notEqual(a, b);
});

test('sha256 is hex, as the Java panel stored it', () => {
  assert.equal(sha256('abc'), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
});

test('short ids fit the /s/<id> pattern', () => {
  for (let n = 0; n < 50; n++) assert.match(shortId(), /^[a-z0-9]{10}$/);
});

test('bearer reads the token from an Authorization header', () => {
  assert.equal(bearer('Bearer aibc_x'), 'aibc_x');
  assert.equal(bearer('bearer  aibc_x '), 'aibc_x');
  assert.equal(bearer('Basic abc'), null);
  assert.equal(bearer(undefined), null);
});
