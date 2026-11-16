import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import { test } from 'node:test';
import Stripe from 'stripe';

// The modules import their neighbours without extensions, as the bundles resolve them; Node needs the extension.
// Nothing here reaches Stripe, Anthropic or MySQL.
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
const { ANTHROPIC_MODELS, CLAUDE, isSource, providerName, upstream } = await import('../lib/chat.ts');
const { chargeMicros, CREDIT_MODELS, MARKUP, MIN_TOPUP, providerMicros, topupRefusal, usd } = await import('../lib/credits.ts');
const { ledgerAction, roundUsage, topupOfSession } = await import('../lib/server/credits.ts');
const { metered } = await import('../lib/server/chat/meter.ts');
const { findModel } = await import('../lib/server/chat/models.ts');
const { providerFailure } = await import('../lib/server/chat/providers.ts');
const { handleWebhook } = await import('../lib/server/billing.ts');

const none = { input: 0, cacheWrite: 0, cacheRead: 0, output: 0 };

test('a request costs Anthropic’s price for each kind of token, plus the markup', () => {
  const u = { input: 1000, cacheWrite: 2000, cacheRead: 10_000, output: 500 };
  // $4, $5, $0.20 and $20 per million tokens are 4, 5, 0.2 and 20 micro-dollars a token.
  assert.equal(providerMicros('claude-opus-5-5', u), 1000 * 4 + 2000 * 5 + 10_000 * 0.2 + 500 * 20);
  assert.equal(chargeMicros('claude-opus-5-5', u), 26_000 * 1.6);
  assert.equal(chargeMicros('claude-sonnet-5-5', u), (2000 + 5000 + 1000 + 5000) * 1.6);
  assert.equal(chargeMicros('claude-fable-5-2', u), (8000 + 20_000 + 2000 + 20_000) * 1.6);
  assert.equal(MARKUP, 0.6);
});

test('a charge is a whole number of micro-dollars, rounded up, and nothing costs nothing', () => {
  assert.equal(chargeMicros('claude-opus-5-5', none), 0);
  assert.equal(chargeMicros('claude-haiku-5-5', { ...none, cacheRead: 1 }), 1);
  assert.equal(chargeMicros('claude-sonnet-5-5', { ...none, input: 3 }), 10);
  // 100 * 1.6 is 160.00000000000003 in floating point: still 160.
  assert.equal(chargeMicros('claude-opus-5-5', { ...none, output: 5 }), 160);
  for (const m of CLAUDE) {
    const micros = chargeMicros(m.anthropic, { input: 12_345, cacheWrite: 678, cacheRead: 91_011, output: 1213 })!;
    assert.ok(Number.isInteger(micros) && micros > 0, m.name);
  }
});

test('Haiku 5.5 costs five times as much once the prompt is over 100,000 tokens, cached tokens included', () => {
  assert.equal(chargeMicros('claude-haiku-5-5', { ...none, input: 50_000, output: 1000 }), (5000 + 500) * 1.6);
  assert.equal(chargeMicros('claude-haiku-5-5', { ...none, input: 100_000, output: 1000 }), (10_000 + 500) * 1.6);
  assert.equal(chargeMicros('claude-haiku-5-5', { input: 60_000, cacheWrite: 0, cacheRead: 50_000, output: 1000 }), 5 * (6000 + 500 + 500) * 1.6);
  // The other models cost the same per token at any length.
  assert.equal(chargeMicros('claude-opus-5-5', { ...none, input: 900_000 }), 900_000 * 4 * 1.6);
});

test('credits pay only for the Claude models, under Anthropic’s ids and at marked-up prices', async () => {
  assert.equal(chargeMicros('deepseek/deepseek-v4', { ...none, input: 10 }), null);
  assert.equal(chargeMicros('anthropic/claude-opus-5.5', { ...none, input: 10 }), null);
  assert.deepEqual(CREDIT_MODELS.map(m => m.id), ANTHROPIC_MODELS.map(m => m.id));
  for (const m of CREDIT_MODELS) {
    assert.equal(m.provider, 'credits');
    // What the page shows per million tokens is what a request is charged (for a prompt short enough for any model).
    assert.equal(chargeMicros(m.id, { ...none, input: 10_000 }), Math.round(m.price!.input * 10_000));
    assert.equal(chargeMicros(m.id, { ...none, output: 10_000 }), Math.round(m.price!.output * 10_000));
  }
  assert.deepEqual((await findModel('credits', 'claude-opus-5-5'))?.price, { input: 6.4, output: 32 });
  assert.equal(await findModel('credits', 'anthropic/claude-opus-5.5'), null);
  assert.deepEqual((await findModel('credits', 'claude-haiku-5-5'))?.long, { over: 100_000, times: 5 });
});

test('credits are a source of their own that Anthropic answers', () => {
  assert.ok(isSource('credits') && isSource('anthropic') && isSource('openrouter'));
  assert.ok(!isSource('openai') && !isSource(undefined));
  assert.equal(upstream('credits'), 'anthropic');
  assert.equal(upstream('openrouter'), 'openrouter');
  assert.equal(providerName('credits'), 'AI Builder credits');
});

test('amounts read as dollars', () => {
  assert.equal(usd(12_340_000), '$12.34');
  assert.equal(usd(0), '$0.00');
  assert.equal(usd(4200), '<$0.01');
  assert.equal(usd(-1_200_000), '-$1.20');
  assert.equal(usd(-300), '-<$0.01');
});

test('a top-up is a whole number of dollars from the minimum up', () => {
  assert.equal(MIN_TOPUP, 10);
  assert.equal(topupRefusal(10), null);
  assert.equal(topupRefusal(500), null);
  assert.match(topupRefusal(9)!, /smallest top-up is \$10/);
  assert.match(topupRefusal(501)!, /largest/);
  assert.match(topupRefusal(10.5)!, /whole number/);
  assert.match(topupRefusal(NaN)!, /whole number/);
});

test('the tokens of an answer are split as Anthropic bills them', () => {
  assert.deepEqual(roundUsage({ input_tokens: 10, output_tokens: 5, cache_creation_input_tokens: 7, cache_read_input_tokens: 90 }),
    { input: 10, cacheWrite: 7, cacheRead: 90, output: 5 });
  assert.deepEqual(roundUsage({ input_tokens: 10, output_tokens: 5, cache_creation_input_tokens: null }), { input: 10, cacheWrite: 0, cacheRead: 0, output: 5 });
});

// ---- metering a request ----

const usage = (output: number) => ({ input_tokens: 100, output_tokens: output }) as any;
const stream = (final: () => Promise<any>, partial?: any) => ({ finalMessage: final, currentMessage: partial });

test('a finished request is charged once, for what its final message says', async () => {
  const charged: any[] = [];
  const message = { usage: usage(50) };
  const got = await metered(stream(async () => message, { usage: usage(1) }), async u => { charged.push(u); });
  assert.equal(got, message);
  assert.deepEqual(charged, [usage(50)]);
});

test('a request that stops early is charged for what had arrived, and still fails', async () => {
  const charged: any[] = [];
  const stopped = new Error('aborted');
  await assert.rejects(metered(stream(async () => { throw stopped; }, { usage: usage(12) }), async u => { charged.push(u); }), e => e === stopped);
  assert.deepEqual(charged, [usage(12)]);
});

test('a request that fails before anything arrived is charged nothing', async () => {
  const charged: any[] = [];
  await assert.rejects(metered(stream(async () => { throw new Error('401'); }), async u => { charged.push(u); }), /401/);
  assert.deepEqual(charged, []);
});

test('a charge that fails makes the request fail, so the turn does not go on unpaid', async () => {
  await assert.rejects(metered(stream(async () => ({ usage: usage(50) })), async () => { throw new Error('db down'); }), /db down/);
});

// ---- Stripe ----

const session = (over: Record<string, unknown> = {}) => ({
  id: 'cs_1', mode: 'payment', payment_status: 'paid', currency: 'usd', amount_subtotal: 2500, amount_total: 3125,
  client_reference_id: '7', customer: 'cus_7', payment_intent: 'pi_7', metadata: { credits: '25', user: '7' }, ...over,
});
const event = (type: string, object: unknown) => ({ type, data: { object } });

test('a paid top-up credits what was paid before tax, to the buyer', () => {
  const topup = { do: 'topup', user: 7, customer: 'cus_7', micros: 25_000_000, ref: 'pi_7' };
  assert.deepEqual(topupOfSession(session()), topup);
  assert.deepEqual(ledgerAction(event('checkout.session.completed', session())), topup);
  assert.deepEqual(ledgerAction(event('checkout.session.async_payment_succeeded', session({ payment_intent: { id: 'pi_7' } }))), topup);
});

test('a session that is not a paid top-up of the panel credits nothing', () => {
  for (const over of [
    { payment_status: 'unpaid' },
    { mode: 'subscription' },
    { metadata: {} },
    { currency: 'eur' },
    { client_reference_id: null },
    { client_reference_id: 'abc' },
    { customer: null },
    { payment_intent: null },
    { amount_subtotal: 0 },
  ]) assert.equal(topupOfSession(session(over)), null, JSON.stringify(over));
  assert.equal(topupOfSession(null), null);
  assert.equal(ledgerAction(event('checkout.session.expired', session())), null);
});

test('refunds and disputes take credits back, and a dispute that is won gives them again', () => {
  assert.deepEqual(ledgerAction(event('charge.refunded', { id: 'ch_1', payment_intent: 'pi_7', amount: 2500, amount_refunded: 1000 })),
    { do: 'refund', payment: 'pi_7', micros: 10_000_000, ref: 'refund:ch_1' });
  assert.deepEqual(ledgerAction(event('charge.dispute.created', { id: 'dp_1', payment_intent: 'pi_7', amount: 2500, status: 'needs_response' })),
    { do: 'dispute', payment: 'pi_7', micros: 25_000_000, ref: 'dispute:dp_1' });
  assert.deepEqual(ledgerAction(event('charge.dispute.closed', { id: 'dp_1', payment_intent: 'pi_7', amount: 2500, status: 'won' })),
    { do: 'undispute', ref: 'dispute:dp_1' });
  assert.equal(ledgerAction(event('charge.dispute.closed', { id: 'dp_1', payment_intent: 'pi_7', amount: 2500, status: 'lost' })), null);
  // A charge that no payment intent made (an old-style charge) cannot be a top-up.
  assert.equal(ledgerAction(event('charge.refunded', { id: 'ch_2', payment_intent: null, amount_refunded: 1000 })), null);
  assert.equal(ledgerAction(event('customer.subscription.updated', { id: 'sub_1', customer: 'cus_7' })), null);
});

test('a signed webhook writes its event to the ledger, and an unsigned one does not', async () => {
  process.env.STRIPE_SECRET_KEY = 'sk_test_unused';
  process.env.STRIPE_WEBHOOK_SECRET = 'whsec_test';
  const payload = JSON.stringify({ id: 'evt_1', object: 'event', ...event('checkout.session.completed', session()) });
  const signature = new Stripe('sk_test_unused').webhooks.generateTestHeaderString({ payload, secret: 'whsec_test' });
  const synced: string[] = [], applied: unknown[] = [];
  const deliver = (sig: string | null) => handleWebhook(payload, sig, async c => { synced.push(c); }, async a => { applied.push(a); });
  assert.equal(await deliver(null), false);
  assert.deepEqual(applied, []);
  assert.equal(await deliver(signature), true);
  assert.deepEqual(applied, [{ do: 'topup', user: 7, customer: 'cus_7', micros: 25_000_000, ref: 'pi_7' }]);
  assert.deepEqual(synced, ['cus_7']);
  await assert.rejects(handleWebhook(payload, signature, async () => {}, async () => { throw new Error('db down'); }), /db down/);
});

// ---- failures on the panel's key ----

test('a failure on the panel’s key never tells the user to fix a key that is not theirs', async () => {
  const Anthropic = (await import('@anthropic-ai/sdk')).default;
  const err = (status: number) => Anthropic.APIError.generate(status, { error: { message: 'no' } }, 'no', new Headers());
  const quiet = console.error;
  console.error = () => {};
  try {
    for (const status of [401, 403, 402]) {
      const said = providerFailure('credits', err(status))!;
      assert.match(said, /problem on the panel’s side/, String(status));
      assert.doesNotMatch(said, /your (API )?key\b(?! )|Replace it|Anthropic/, String(status));
    }
  } finally {
    console.error = quiet;
  }
  assert.match(providerFailure('credits', err(429))!, /busy/);
  assert.match(providerFailure('credits', err(529))!, /error \(529\)/);
  assert.equal(providerFailure('credits', new Error('other')), null);
  // A user's own key keeps its own words.
  assert.match(providerFailure('anthropic', err(401))!, /no longer accepts your API key/);
});
