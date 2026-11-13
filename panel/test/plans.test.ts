import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  customerOfEvent, planState, type Subscription,
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

// ---- what a customer's subscriptions grant ----

const PERIOD_END = 1_800_000_000;
const sub = (over: Partial<Subscription> & { price?: string; product?: string } = {}): Subscription => ({
  id: 'sub_1', status: 'active', created: 100, cancel_at_period_end: false, cancel_at: null,
  items: { data: [{ current_period_end: PERIOD_END, price: { id: over.price ?? 'price_pro', product: over.product ?? 'prod_pro' } }] },
  ...over,
});

test('no subscription is Free with nothing to show', () => {
  assert.deepEqual(planState([], undefined), { plan: 'free', subscription: null, status: null, renews: null, cancels: false });
});

test('an active subscription grants Pro until its period ends', () => {
  assert.deepEqual(planState([sub()], undefined),
    { plan: 'pro', subscription: 'sub_1', status: 'active', renews: PERIOD_END * 1000, cancels: false });
});

test('cancelling in the portal keeps the plan to the end of the period and says so', () => {
  const atEnd = planState([sub({ cancel_at_period_end: true })], undefined);
  assert.equal(atEnd.plan, 'pro');
  assert.equal(atEnd.cancels, true);
  assert.equal(planState([sub({ cancel_at: PERIOD_END })], undefined).cancels, true);
});

test('a failed payment keeps the plan while Stripe retries, and an ended subscription is Free', () => {
  assert.equal(planState([sub({ status: 'past_due' })], undefined).plan, 'pro');
  for (const status of ['canceled', 'unpaid', 'incomplete', 'incomplete_expired', 'paused']) {
    const s = planState([sub({ status })], undefined);
    assert.equal(s.plan, 'free', status);
    assert.equal(s.status, status);
  }
});

test('a subscription that grants wins over a newer one that ended', () => {
  const s = planState([sub({ id: 'sub_new', status: 'canceled', created: 300 }), sub({ id: 'sub_old', created: 100 })], undefined);
  assert.equal(s.plan, 'pro');
  assert.equal(s.subscription, 'sub_old');
});

test('with only ended subscriptions the newest one is shown, and the list is left as it was', () => {
  const subs = [sub({ id: 'a', status: 'canceled', created: 100 }), sub({ id: 'b', status: 'incomplete_expired', created: 300 })];
  assert.equal(planState(subs, undefined).subscription, 'b');
  assert.deepEqual(subs.map(s => s.id), ['a', 'b']);
});

test('the Studio price or product makes the subscription Studio', () => {
  assert.equal(planState([sub({ price: 'price_studio' })], 'price_studio').plan, 'studio');
  assert.equal(planState([sub({ product: 'prod_studio' })], 'prod_studio').plan, 'studio');
});

test('only checkout and subscription events name a customer to sync', () => {
  const event = (type: string, object: unknown) => ({ type, data: { object } });
  assert.equal(customerOfEvent(event('customer.subscription.deleted', { customer: 'cus_1' })), 'cus_1');
  assert.equal(customerOfEvent(event('customer.subscription.updated', { customer: { id: 'cus_2' } })), 'cus_2');
  assert.equal(customerOfEvent(event('checkout.session.completed', { customer: 'cus_3' })), 'cus_3');
  assert.equal(customerOfEvent(event('checkout.session.completed', { customer: null })), null);
  assert.equal(customerOfEvent(event('invoice.paid', { customer: 'cus_1' })), null);
  assert.equal(customerOfEvent(event('customer.subscription.updated', null)), null);
});

test('a member who is not the owner is pointed to the owner, not to their own Billing page', () => {
  for (const feature of ['restApi', 'promote'] as const) {
    const said = featureRefusal(free, feature, 'Survival')!;
    assert.match(said, /which the owner of 'Survival' is not on\. Only the owner can upgrade it/);
    assert.doesNotMatch(said, /\. Upgrade on/);
  }
  assert.equal(featureRefusal(limitsOf('pro'), 'restApi', 'Survival'), null);
});
