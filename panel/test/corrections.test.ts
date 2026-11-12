import assert from 'node:assert/strict';
import { test } from 'node:test';
import { correctionSeries, lessonFrom } from '../lib/corrections.ts';
import { lessonOp } from '../lib/server/lessons.ts';
import type { Corrections } from '../lib/types.ts';

const none: Corrections = { cells: 0, placed: 10, share: 0, substitutions: [], removed: [], states: [] };

test('the lesson starts from the largest row', () => {
  assert.equal(lessonFrom({
    ...none,
    substitutions: [{ from: 'oak_planks', to: 'spruce_planks', count: 212, at: [[1, 64, 2]] }],
    removed: [{ type: 'torch', count: 4 }],
    states: [{ type: 'oak_stairs', count: 18, props: ['facing'] }],
  }), 'Use spruce_planks, not oak_planks.');
  assert.equal(lessonFrom({ ...none, removed: [{ type: 'torch', count: 4 }], states: [{ type: 'oak_stairs', count: 18, props: ['facing', 'half'] }] }),
    'Check the facing and half of oak_stairs.');
  assert.equal(lessonFrom({ ...none, removed: [{ type: 'torch', count: 40 }] }), 'Leave out torch.');
  assert.equal(lessonFrom({ ...none, substitutions: [{ from: 'air', to: 'grass_block', count: 9, at: [] }] }), 'Include grass_block.');
  assert.equal(lessonFrom({ ...none, states: [{ type: 'oak_stairs', count: 3 }] }), 'Check the block states of oak_stairs.');
  assert.equal(lessonFrom(none), '');
});

test('correction rates exclude additions and unchecked builds, and weight by placed cells', () => {
  const input = [
    { id: 'h3', time: 3, placed: 1000 },
    { id: 'h2', time: 2, placed: 10, corrected: { cells: 20, additions: 20, share: 0, time: 10 } },
    { id: 'h1', time: 1, placed: 90, corrected: { cells: 9, share: 0.1, time: 10 } },
  ];
  const series = correctionSeries(input);
  assert.deepEqual(series.points.map(e => e.id), ['h1', 'h2', 'h3']);
  assert.equal(input[0].id, 'h3', 'does not reorder React state');
  assert.equal(series.checked, 2);
  assert.equal(series.rate, 0.09);
  assert.equal(correctionSeries([input[0]]).rate, null);
  assert.equal(correctionSeries([]).rate, null);
});

test('a lesson saved from a history entry is linked to it', () => {
  assert.deepEqual(lessonOp('POST', [], { text: 'Use spruce_planks, not oak_planks.', entry: 'hmhf0abc' }, 'panel: Ann').args,
    { text: 'Use spruce_planks, not oak_planks.', by: 'panel: Ann', entry: 'hmhf0abc' });
  assert.throws(() => lessonOp('POST', [], { text: 'x', entry: '../h1' }, 'panel: Ann'));
});
