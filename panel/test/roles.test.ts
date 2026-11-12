import assert from 'node:assert/strict';
import { test } from 'node:test';
import { atLeast, canManage, grantable } from '../lib/server/roles.ts';

test('atLeast orders owner > admin > builder > viewer', () => {
  assert.ok(atLeast('owner', 'admin'));
  assert.ok(atLeast('builder', 'builder'));
  assert.ok(!atLeast('viewer', 'builder'));
  assert.ok(!atLeast(null, 'viewer'));
});

test('nobody can hand out owner; admins only builder and viewer', () => {
  assert.deepEqual(grantable('owner'), ['admin', 'builder', 'viewer']);
  assert.deepEqual(grantable('admin'), ['builder', 'viewer']);
  assert.deepEqual(grantable('builder'), []);
  assert.deepEqual(grantable('viewer'), []);
});

test('members can only manage those ranked strictly below them', () => {
  assert.ok(canManage('owner', 'admin'));
  assert.ok(canManage('admin', 'builder'));
  assert.ok(!canManage('admin', 'admin'));
  assert.ok(!canManage('admin', 'owner'));
  assert.ok(!canManage('builder', 'viewer'));
});
