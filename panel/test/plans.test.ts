import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  featureRefusal, grants, historyCutoff, historyRefusal, limitsOf, memberRefusal, planOfPrice, PLANS, PRO_INCLUDES_STUDIO,
  serverRefusal, UNLIMITED,
} from '../lib/plans.ts';

const DAY = 86_400_000;
const free = PLANS.free.limits;

test('Free allows one server, two members, a week of history and no extras', () => {
  assert.equal(serverRefusal(free, 0), null);
  assert.match(serverRefusal(free, 1)!, /allows 1 server and you own 1/);
  assert.equal(memberRefusal(free, 1), null);
  assert.match(memberRefusal(free, 2)!, /allows 2 members/);
  for (const feature of ['assets', 'restApi', 'promote'] as const) assert.match(featureRefusal(free, feature)!, /plan\. Upgrade/);
});

test('history older than the window is closed, newer is open', () => {
  const now = 100 * DAY;
  assert.equal(historyCutoff(free, now), 93 * DAY);
  assert.equal(historyRefusal(free, 93 * DAY, now), null);
  assert.match(historyRefusal(free, 93 * DAY - 1, now)!, /7 days of history/);
  assert.equal(historyCutoff(UNLIMITED, now), null);
  assert.equal(historyRefusal(UNLIMITED, 0, now), null);
});

test('an account over a limit keeps what it has and is refused only more', () => {
  assert.match(serverRefusal(free, 4)!, /you own 4/);
  assert.match(memberRefusal(free, 9)!, /has 9/);
});

test('the discount gives Pro what Studio allows', () => {
  assert.equal(PRO_INCLUDES_STUDIO, true);
  assert.deepEqual(limitsOf('pro'), PLANS.studio.limits);
  assert.deepEqual(limitsOf('free'), free);
  assert.equal(memberRefusal(limitsOf('pro'), 500), null);
  assert.equal(featureRefusal(limitsOf('pro'), 'promote'), null);
});

test('a refusal names the cheapest plan that has the feature', () => {
  assert.match(featureRefusal(free, 'assets')!, /^The asset library is part of the Pro plan/);
});

test('a subscription grants its plan while active, trialing or retrying a payment', () => {
  for (const s of ['active', 'trialing', 'past_due']) assert.equal(grants(s), true, s);
  for (const s of ['canceled', 'unpaid', 'incomplete', 'incomplete_expired', 'paused']) assert.equal(grants(s), false, s);
});

test('a price is Pro unless it is the configured Studio price or product', () => {
  assert.equal(planOfPrice({ id: 'price_a', product: 'prod_a' }, undefined), 'pro');
  assert.equal(planOfPrice({ id: 'price_s', product: 'prod_x' }, 'price_s'), 'studio');
  assert.equal(planOfPrice({ id: 'price_b', product: { id: 'prod_s' } }, 'prod_s'), 'studio');
});
