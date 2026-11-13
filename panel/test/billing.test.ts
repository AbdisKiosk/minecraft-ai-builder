import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import { test } from 'node:test';
import Stripe from 'stripe';

// billing.ts imports its neighbours without extensions, as the Next and esbuild bundles resolve them; Node needs the
// extension. Nothing here reaches Stripe or MySQL: the signature check is local and the sync is replaced.
registerHooks({
  resolve(specifier, context, next) {
    try {
      return next(specifier, context);
    } catch (e) {
      if (specifier.startsWith('.') && !/\.[cm]?[jt]s$/.test(specifier)) return next(`${specifier}.ts`, context);
      throw e;
    }
  },
});
const { handleWebhook } = await import('../lib/server/billing.ts');

const SECRET = 'whsec_test';
process.env.STRIPE_SECRET_KEY = 'sk_test_unused';
process.env.STRIPE_WEBHOOK_SECRET = SECRET;
delete process.env.DATABASE_URL;

const body = (type: string, object: unknown) => JSON.stringify({ id: 'evt_1', object: 'event', type, data: { object } });
const sign = (payload: string, secret = SECRET, timestamp?: number) =>
  new Stripe('sk_test_unused').webhooks.generateTestHeaderString({ payload, secret, timestamp });

/** Delivers a payload and returns the answer with the customers that were synced. */
async function deliver(payload: string, signature: string | null) {
  const synced: string[] = [];
  const ok = await handleWebhook(payload, signature, async c => { synced.push(c); });
  return { ok, synced };
}

test('a signed subscription event syncs its customer', async () => {
  const payload = body('customer.subscription.updated', { id: 'sub_1', customer: 'cus_1' });
  assert.deepEqual(await deliver(payload, sign(payload)), { ok: true, synced: ['cus_1'] });
});

test('a finished checkout syncs its customer', async () => {
  const payload = body('checkout.session.completed', { id: 'cs_1', customer: 'cus_2' });
  assert.deepEqual(await deliver(payload, sign(payload)), { ok: true, synced: ['cus_2'] });
});

test('an event about something else is accepted and syncs nothing', async () => {
  const payload = body('invoice.paid', { id: 'in_1', customer: 'cus_1' });
  assert.deepEqual(await deliver(payload, sign(payload)), { ok: true, synced: [] });
});

test('an unsigned, wrongly signed, altered or old delivery is refused and syncs nothing', async () => {
  const payload = body('customer.subscription.deleted', { id: 'sub_1', customer: 'cus_1' });
  const refused = { ok: false, synced: [] };
  assert.deepEqual(await deliver(payload, null), refused);
  assert.deepEqual(await deliver(payload, sign(payload, 'whsec_other')), refused);
  assert.deepEqual(await deliver(payload.replace('cus_1', 'cus_9'), sign(payload)), refused);
  assert.deepEqual(await deliver(payload, sign(payload, SECRET, Math.floor(Date.now() / 1000) - 3600)), refused);
});

test('a failing sync is thrown, so the route answers 500 and Stripe delivers again', async () => {
  const payload = body('customer.subscription.updated', { id: 'sub_1', customer: 'cus_1' });
  await assert.rejects(handleWebhook(payload, sign(payload), async () => { throw new Error('db down'); }), /db down/);
});
