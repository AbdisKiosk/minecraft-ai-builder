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
const { handleWebhook, saleTerms, taxEnabled, topupFields } = await import('../lib/server/billing.ts');

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

test('every checkout asks the buyer to accept the terms and an immediate start, linking both pages', () => {
  const t = saleTerms('https://panel.example', true);
  assert.equal(t.consent_collection.terms_of_service, 'required');
  const text = t.custom_text.terms_of_service_acceptance.message;
  assert.match(text, /\(https:\/\/panel\.example\/terms\)/);
  assert.match(text, /\(https:\/\/panel\.example\/refunds\)/);
  assert.match(text, /start now/);
  assert.ok(text.length <= 1200, 'Stripe takes at most 1200 characters');
});

test('with tax on, Stripe Tax works out VAT, takes VAT numbers and saves the address; off, none of that', () => {
  const on = saleTerms('https://panel.example', true);
  assert.deepEqual('automatic_tax' in on && on.automatic_tax, { enabled: true });
  assert.deepEqual('tax_id_collection' in on && on.tax_id_collection, { enabled: true });
  assert.deepEqual('customer_update' in on && on.customer_update, { address: 'auto', name: 'auto' });
  const off = saleTerms('https://panel.example', false);
  assert.ok(!('automatic_tax' in off) && !('tax_id_collection' in off) && !('customer_update' in off));
});

test('tax is on unless STRIPE_TAX is off', () => {
  const was = process.env.STRIPE_TAX;
  try {
    delete process.env.STRIPE_TAX;
    assert.equal(taxEnabled(), true);
    process.env.STRIPE_TAX = 'off';
    assert.equal(taxEnabled(), false);
  } finally {
    if (was === undefined) delete process.env.STRIPE_TAX;
    else process.env.STRIPE_TAX = was;
  }
});

test('a top-up is priced with VAT included, marked as credits, invoiced and asks for 3D Secure', () => {
  const f = topupFields(25, 7);
  assert.equal(f.line_items[0].price_data.unit_amount, 2500);
  assert.equal(f.line_items[0].price_data.tax_behavior, 'inclusive');
  assert.deepEqual(f.metadata, { credits: '25', user: '7' });
  assert.deepEqual(f.payment_intent_data.metadata, f.metadata);
  assert.equal(f.payment_method_options.card.request_three_d_secure, 'any');
  assert.deepEqual(f.invoice_creation, { enabled: true });
});
