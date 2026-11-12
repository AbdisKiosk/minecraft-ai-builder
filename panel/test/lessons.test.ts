import assert from 'node:assert/strict';
import { test } from 'node:test';
import { LessonError, lessonOp, requireLessons, TOO_OLD } from '../lib/server/lessons.ts';
import { atLeast, ROLES, type Role } from '../lib/server/roles.ts';

const BY = 'panel: Ann';

function status(fn: () => unknown): number {
  try {
    fn();
  } catch (e) {
    assert.ok(e instanceof LessonError, String(e));
    return e.status;
  }
  return 0;
}

/** Which roles the server lets through for a request: route.ts's requireRole is atLeast(role, needs). */
function allowed(method: string, path: string[], body = {}): Role[] {
  const { needs } = lessonOp(method, path, body, BY);
  return ROLES.filter(r => atLeast(r, needs));
}

test('every member sees lessons and marks them seen; admins and owners change them', () => {
  assert.deepEqual(allowed('GET', []), ['owner', 'admin', 'builder', 'viewer']);
  assert.deepEqual(allowed('POST', ['seen']), ['owner', 'admin', 'builder', 'viewer']);
  assert.deepEqual(allowed('POST', [], { text: 'Use spruce floors' }), ['owner', 'admin']);
  assert.deepEqual(allowed('PATCH', ['l1a'], { text: 'Use oak floors' }), ['owner', 'admin']);
  assert.deepEqual(allowed('DELETE', ['l1a']), ['owner', 'admin']);
});

test('feedback needs the role that may reject drafts and revert edits', () => {
  assert.deepEqual(allowed('POST', ['feedback'], { id: 'd1x', text: 'too dark' }), ['owner', 'admin']);
});

test('requests become lessons frames with who wrote them', () => {
  assert.deepEqual(lessonOp('GET', [], {}, BY), { op: 'list', args: {}, needs: 'viewer' });
  assert.deepEqual(lessonOp('POST', [], { text: 'Roofs are dark_oak', origin: 'claude' }, BY).args,
    { text: 'Roofs are dark_oak', by: BY });
  assert.deepEqual(lessonOp('PATCH', ['l1a'], { text: 'x' }, BY).args, { id: 'l1a', text: 'x' });
  assert.deepEqual(lessonOp('DELETE', ['l1a'], {}, BY).args, { id: 'l1a' });
  assert.deepEqual(lessonOp('POST', ['feedback'], { id: 'h2y', text: 'wrong place', remember: true }, BY).args,
    { id: 'h2y', feedback: 'wrong place', remember: true, by: BY });
  // Only a literal true remembers.
  assert.equal(lessonOp('POST', ['feedback'], { id: 'h2y', text: 'x', remember: 'yes' }, BY).args.remember, false);
  assert.equal(lessonOp('POST', [], { text: 'y'.repeat(5000) }, BY).args.text.length, 2000);
});

test('bad requests are refused before they reach the plugin', () => {
  assert.equal(status(() => lessonOp('POST', [], { text: '  ' }, BY)), 400);
  assert.equal(status(() => lessonOp('POST', [], { text: 7 }, BY)), 400);
  assert.equal(status(() => lessonOp('POST', ['feedback'], { id: '../x', text: 'a' }, BY)), 400);
  assert.equal(status(() => lessonOp('POST', ['feedback'], { id: 'd1' }, BY)), 400);
  assert.equal(status(() => lessonOp('PATCH', ['bad id'], { text: 'a' }, BY)), 400);
  assert.equal(status(() => lessonOp('PATCH', [], { text: 'a' }, BY)), 404);
  assert.equal(status(() => lessonOp('DELETE', [], {}, BY)), 404);
  assert.equal(status(() => lessonOp('GET', ['l1a', 'x'], {}, BY)), 404);
  assert.equal(status(() => lessonOp('PUT', [], {}, BY)), 404);
});

test('a plugin from before lessons gets a clear answer instead of a relay error', () => {
  assert.throws(() => requireLessons(true, { mc: '1.21.11' }), (e: unknown) => e instanceof LessonError && e.status === 501 && e.message === TOO_OLD);
  requireLessons(true, { lessons: true });
  requireLessons(false, {}); // offline: the relay says so
});
