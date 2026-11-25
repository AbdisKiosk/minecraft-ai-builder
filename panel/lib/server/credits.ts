import { chargeMicros, MARKUP, MAX_TOPUP, MIN_TOPUP, TOPUPS, type CreditEntry, type CreditsView, type RoundUsage } from '../credits';
import { exec, one, query } from './db';

// The credit ledger in MySQL (migration 014_credits.sql). An account's balance is the sum of its rows. Stripe adds to
// it (a top-up) and takes from it (a refund, a disputed payment); a chat on credits takes from it after every request
// to the model.

/**
 * Credits are sold when the panel sells through Stripe and has an Anthropic key of its own for them. This key is the
 * panel's and is used for chats on credits only; a chat on the user's own key never touches it.
 */
export const creditsEnabled = () => !!process.env.STRIPE_SECRET_KEY && !!process.env.CREDITS_ANTHROPIC_KEY;

export const creditsKey = (): string | null => process.env.CREDITS_ANTHROPIC_KEY || null;

/** What is left, in micro-dollars. Below zero when the last request cost more than was left. */
export async function balanceOf(userId: number): Promise<number> {
  const r = await one<{ sum: string | number | null }>('SELECT SUM(micros) AS sum FROM credit_ledger WHERE user_id = ?', [userId]);
  return Number(r?.sum ?? 0);
}

export async function creditsView(userId: number): Promise<CreditsView> {
  const rows = await query<{ id: number; kind: CreditEntry['kind']; micros: string | number; model: string | null; created_at: Date }>(
    'SELECT id, kind, micros, model, created_at FROM credit_ledger WHERE user_id = ? AND micros <> 0 ORDER BY id DESC LIMIT 30', [userId]);
  return {
    balance: await balanceOf(userId),
    markup: MARKUP,
    topups: TOPUPS,
    min: MIN_TOPUP,
    max: MAX_TOPUP,
    entries: rows.map(r => ({ id: Number(r.id), kind: r.kind, micros: Number(r.micros), at: r.created_at.getTime(), model: r.model })),
  };
}

// ---- usage ----

/** The tokens of one answer, as Anthropic reports and bills them. */
export function roundUsage(u: { input_tokens: number; output_tokens: number; cache_creation_input_tokens?: number | null; cache_read_input_tokens?: number | null }): RoundUsage {
  return { input: u.input_tokens, cacheWrite: u.cache_creation_input_tokens ?? 0, cacheRead: u.cache_read_input_tokens ?? 0, output: u.output_tokens };
}

/** Starts the ledger entry of one turn of a chat, at nothing. Answers its id. */
export async function openUsage(userId: number, chatId: string, model: string): Promise<number> {
  return (await exec("INSERT INTO credit_ledger (user_id, kind, micros, chat_id, model) VALUES (?, 'usage', 0, ?, ?)", [userId, chatId, model])).insertId;
}

/** Charges one request of the turn. Answers what it cost in micro-dollars. */
export async function chargeUsage(entry: number, model: string, u: RoundUsage): Promise<number> {
  const micros = chargeMicros(model, u);
  if (micros === null) throw new Error(`credits have no price for model '${model}'`);
  await exec('UPDATE credit_ledger SET micros = micros - ?, input_tokens = input_tokens + ?, output_tokens = output_tokens + ? WHERE id = ?',
    [micros, u.input + u.cacheWrite + u.cacheRead, u.output, entry]);
  return micros;
}

/** Ends the turn's entry: one that charged nothing (the turn failed before the model answered) is not kept. */
export async function closeUsage(entry: number): Promise<void> {
  await exec("DELETE FROM credit_ledger WHERE id = ? AND kind = 'usage' AND micros = 0", [entry]);
}

// ---- Stripe ----

/** What a Stripe event does to the ledger. */
export type LedgerAction =
  /** A paid top-up: `micros` for `user`, whose Stripe customer must be `customer`. `ref` is the payment intent. */
  | { do: 'topup'; user: number; customer: string; micros: number; ref: string }
  /** `micros` of the top-up paid by `payment` went back to the card, in all. */
  | { do: 'refund'; payment: string; micros: number; ref: string }
  /** The cardholder disputes the top-up paid by `payment`: Stripe has taken `micros` back. */
  | { do: 'dispute'; payment: string; micros: number; ref: string }
  /** A dispute was won, so its money is back. */
  | { do: 'undispute'; ref: string };

/** Stripe counts in cents. */
const MICROS_PER_CENT = 10_000;

type Obj = Record<string, unknown>;
const idOf = (v: unknown): string | null => (typeof v === 'string' && v ? v : typeof (v as { id?: unknown } | null)?.id === 'string' ? (v as { id: string }).id : null);
const cents = (v: unknown): number | null => (typeof v === 'number' && Number.isInteger(v) && v > 0 ? v : null);

/**
 * The top-up a Checkout session paid for, or null when it is not a paid top-up of ours: the panel marks its sessions
 * with metadata.credits and the buyer as client_reference_id, and sells credits in US dollars.
 */
export function topupOfSession(session: unknown): Extract<LedgerAction, { do: 'topup' }> | null {
  const s = session as Obj | null;
  if (!s || s.mode !== 'payment' || s.payment_status !== 'paid' || s.currency !== 'usd') return null;
  if (!(s.metadata as Obj | null)?.credits) return null;
  const user = Number(s.client_reference_id);
  const customer = idOf(s.customer);
  const ref = idOf(s.payment_intent);
  // The price includes VAT (billing.ts topupFields), so the subtotal is what was paid: a dollar paid is a dollar of
  // credits. A price that added tax on top would leave that tax out.
  const paid = cents(s.amount_subtotal);
  if (!Number.isInteger(user) || user <= 0 || !customer || !ref || paid === null) return null;
  return { do: 'topup', user, customer, micros: paid * MICROS_PER_CENT, ref };
}

/** What a webhook event does to the ledger, or null when it does nothing to it. */
export function ledgerAction(event: { type: string; data: { object: unknown } }): LedgerAction | null {
  const o = event.data.object as Obj | null;
  if (!o) return null;
  switch (event.type) {
    case 'checkout.session.completed':
    case 'checkout.session.async_payment_succeeded':
      return topupOfSession(o);
    case 'charge.refunded': {
      const charge = idOf(o.id), payment = idOf(o.payment_intent), refunded = cents(o.amount_refunded);
      return charge && payment && refunded !== null ? { do: 'refund', payment, micros: refunded * MICROS_PER_CENT, ref: `refund:${charge}` } : null;
    }
    case 'charge.dispute.created': {
      const dispute = idOf(o.id), payment = idOf(o.payment_intent), amount = cents(o.amount);
      return dispute && payment && amount !== null ? { do: 'dispute', payment, micros: amount * MICROS_PER_CENT, ref: `dispute:${dispute}` } : null;
    }
    case 'charge.dispute.closed': {
      const dispute = idOf(o.id);
      return dispute && o.status === 'won' ? { do: 'undispute', ref: `dispute:${dispute}` } : null;
    }
    default:
      return null;
  }
}

/**
 * Writes what a Stripe event does to the ledger. Every action can be applied again without changing anything, so
 * webhooks may arrive twice, and the page a buyer returns to may apply a top-up before its webhook does.
 */
export async function applyLedger(a: LedgerAction): Promise<void> {
  if (a.do === 'topup') {
    await exec(`INSERT IGNORE INTO credit_ledger (user_id, kind, micros, ref)
                SELECT id, 'topup', ?, ? FROM users WHERE id = ? AND stripe_customer = ?`, [a.micros, a.ref, a.user, a.customer]);
    return;
  }
  if (a.do === 'undispute') {
    await exec("DELETE FROM credit_ledger WHERE ref = ? AND kind = 'dispute'", [a.ref]);
    return;
  }
  // A payment that is not a top-up (a subscription's invoice) has no entry and takes no credits back.
  const top = await one<{ user_id: number; micros: string | number }>("SELECT user_id, micros FROM credit_ledger WHERE ref = ? AND kind = 'topup'", [a.payment]);
  if (!top) return;
  // Never more than the top-up gave: a refund can include tax that bought no credits.
  const back = -Math.min(a.micros, Number(top.micros));
  if (a.do === 'refund') {
    // Stripe reports the charge's refunds in all, so a second partial refund replaces the first one's entry.
    await exec(`INSERT INTO credit_ledger (user_id, kind, micros, ref) VALUES (?, 'refund', ?, ?)
                ON DUPLICATE KEY UPDATE micros = VALUES(micros)`, [top.user_id, back, a.ref]);
  } else {
    await exec("INSERT IGNORE INTO credit_ledger (user_id, kind, micros, ref) VALUES (?, 'dispute', ?, ?)", [top.user_id, back, a.ref]);
  }
}
