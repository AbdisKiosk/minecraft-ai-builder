import assert from 'node:assert/strict';
import { test } from 'node:test';
import { nextTip, parseState } from '../lib/tips.ts';

test('a missing or broken stored value is a new visitor', () => {
  for (const raw of [null, '', 'nope', '[]', '3', '{"done":"x"}']) {
    assert.deepEqual(parseState(raw), { welcomed: false, done: [] }, String(raw));
  }
});

test('stored state keeps the tips that still exist', () => {
  assert.deepEqual(parseState('{"welcomed":true,"done":["map","gone",4,"toString"]}'), { welcomed: true, done: ['map'] });
});

test('the first tip on screen that is not done shows', () => {
  const state = { welcomed: true, done: [] };
  assert.equal(nextTip(['lessons', 'map', 'connect'], state, false), 'connect');
  assert.equal(nextTip(['lessons', 'map', 'connect'], { welcomed: true, done: ['connect'] }, false), 'map');
  assert.equal(nextTip(['map'], { welcomed: true, done: ['map'] }, false), null);
  assert.equal(nextTip([], state, false), null);
});

test('no tip shows under the welcome guide or right after one was closed', () => {
  assert.equal(nextTip(['connect'], { welcomed: false, done: [] }, false), null);
  assert.equal(nextTip(['connect'], { welcomed: true, done: [] }, true), null);
});
