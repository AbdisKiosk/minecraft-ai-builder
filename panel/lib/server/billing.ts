import Stripe from 'stripe';
import { customerOfEvent, isPlan, limitsOf, planState, UNLIMITED, type Limits, type PlanId } from '../plans';
import type { UserRow } from './access';
import { exec, one } from './db';

// Plans are sold through Stripe: hosted Checkout to subscribe, the Customer Portal to change or cancel. The panel keeps
// what the subscription grants on the user's row and reads it from there. Without STRIPE_SECRET_KEY the panel sells
// nothing and limits nothing, which is how a self-hosted panel runs.

export const billingEnabled = () => !!process.env.STRIPE_SECRET_KEY;

/** The HTTP status for "your plan does not include this". */
export const PAYMENT_REQUIRED = 402;

export interface Billing {
  plan: PlanId;
  /** Stripe's status of the subscription, or null without one. */
  status: string | null;
  /** When the paid period ends, in epoch milliseconds. */
  renews: number | null;
  /** The subscription ends at `renews` instead of renewing. */
  cancels: boolean;
}

interface BillingRow { plan: string; plan_status: string | null; plan_renews_at: Date | null; plan_cancels: number; stripe_customer: string | null }

const COLUMNS = 'plan, plan_status, plan_renews_at, plan_cancels, stripe_customer';

function view(r: BillingRow | null): Billing {
  return {
    plan: r && isPlan(r.plan) ? r.plan : 'free',
    status: r?.plan_status ?? null,
    renews: r?.plan_renews_at ? r.plan_renews_at.getTime() : null,
    cancels: !!r?.plan_cancels,
  };
}

export async function billingOf(userId: number): Promise<Billing> {
  return view(await one<BillingRow>(`SELECT ${COLUMNS} FROM users WHERE id = ?`, [userId]));
}

/** What the account's own plan allows: everything when the panel sells no plans. */
export async function limitsFor(userId: number): Promise<Limits> {
  if (!billingEnabled()) return UNLIMITED;
  return limitsOf((await billingOf(userId)).plan);
}

/** What a server's plan allows, which is its owner's. */
export async function serverLimits(serverId: string): Promise<Limits> {
  if (!billingEnabled()) return UNLIMITED;
  const r = await one<BillingRow>(
    `SELECT ${COLUMNS.split(', ').map(c => 'u.' + c).join(', ')} FROM servers s JOIN users u ON u.id = s.owner_id WHERE s.id = ?`, [serverId]);
  return limitsOf(view(r).plan);
}

// ---- Stripe ----

let client: Stripe | undefined;

function stripe(): Stripe {
  const key = process.env.STRIPE_SECRET_KEY;
  if (!key) throw new Error('STRIPE_SECRET_KEY is not set');
  return client ??= new Stripe(key);
}

let proPrice: Promise<string> | undefined;

/** The price Pro is sold at. STRIPE_PRICE_PRO is a price id, or a product id, whose default price is used. */
function proPriceId(): Promise<string> {
  return proPrice ??= (async () => {
    const id = process.env.STRIPE_PRICE_PRO;
    if (!id) throw new Error('STRIPE_PRICE_PRO is not set');
    if (!id.startsWith('prod_')) return id;
    const price = (await stripe().products.retrieve(id)).default_price;
    if (!price) throw new Error(`Stripe product ${id} has no default price`);
    return typeof price === 'string' ? price : price.id;
  })().catch(e => {
    proPrice = undefined;
    throw e;
  });
}

/** The account's Stripe customer, made on first use. */
async function customerFor(user: UserRow): Promise<string> {
  const saved = async () => (await one<{ stripe_customer: string | null }>('SELECT stripe_customer FROM users WHERE id = ?', [user.id]))?.stripe_customer;
  const have = await saved();
  if (have) return have;
  const made = await stripe().customers.create({
    email: user.email ?? undefined,
    name: user.name ?? undefined,
    metadata: { user: String(user.id) },
  });
  await exec('UPDATE users SET stripe_customer = ? WHERE id = ? AND stripe_customer IS NULL', [made.id, user.id]);
  // Two tabs at once: the first one's customer is the account's.
  return (await saved()) ?? made.id;
}

/** A Stripe Checkout page where the user subscribes to Pro; Stripe sends them to /api/billing/return afterwards. */
export async function checkoutUrl(user: UserRow, base: string): Promise<string> {
  const session = await stripe().checkout.sessions.create({
    mode: 'subscription',
    customer: await customerFor(user),
    client_reference_id: String(user.id),
    line_items: [{ price: await proPriceId(), quantity: 1 }],
    success_url: `${base}/api/billing/return?session_id={CHECKOUT_SESSION_ID}`,
    cancel_url: `${base}/pricing`,
  });
  if (!session.url) throw new Error('Stripe made a checkout session without a URL');
  return session.url;
}

/** Stripe's Customer Portal for the account: change the card, see invoices, cancel. Null when it never subscribed. */
export async function portalUrl(user: UserRow, base: string): Promise<string | null> {
  const customer = (await one<{ stripe_customer: string | null }>('SELECT stripe_customer FROM users WHERE id = ?', [user.id]))?.stripe_customer;
  if (!customer) return null;
  return (await stripe().billingPortal.sessions.create({ customer, return_url: `${base}/dashboard#/billing` })).url;
}

/** Whether a finished Checkout session is this user's. */
export async function ownsCheckout(user: UserRow, sessionId: string): Promise<boolean> {
  const session = await stripe().checkout.sessions.retrieve(sessionId);
  return session.client_reference_id === String(user.id);
}

/**
 * Reads the customer's subscriptions from Stripe and stores what they grant. It asks Stripe for the current state
 * rather than trusting one event, so webhooks may arrive late, twice or out of order.
 */
export async function syncCustomer(customer: string): Promise<void> {
  const subs = (await stripe().subscriptions.list({ customer, status: 'all', limit: 20 })).data;
  const s = planState(subs, process.env.STRIPE_PRICE_STUDIO);
  await exec(
    'UPDATE users SET plan = ?, stripe_subscription = ?, plan_status = ?, plan_renews_at = ?, plan_cancels = ? WHERE stripe_customer = ?',
    [s.plan, s.subscription, s.status, s.renews === null ? null : new Date(s.renews), s.cancels ? 1 : 0, customer]);
}

/** Brings the account's plan up to date with Stripe, e.g. when the user comes back from Checkout. */
export async function syncUser(userId: number): Promise<void> {
  const customer = (await one<{ stripe_customer: string | null }>('SELECT stripe_customer FROM users WHERE id = ?', [userId]))?.stripe_customer;
  if (customer) await syncCustomer(customer);
}

/**
 * Handles a webhook delivery: checks Stripe's signature, then syncs the customer the event is about.
 * @param sync how a customer is synced; tests replace it
 * @returns false when the signature is wrong
 */
export async function handleWebhook(payload: string, signature: string | null,
    sync: (customer: string) => Promise<void> = syncCustomer): Promise<boolean> {
  const secret = process.env.STRIPE_WEBHOOK_SECRET;
  if (!secret) throw new Error('STRIPE_WEBHOOK_SECRET is not set');
  let event: Stripe.Event;
  try {
    event = stripe().webhooks.constructEvent(payload, signature ?? '', secret);
  } catch {
    return false;
  }
  const customer = customerOfEvent(event);
  if (customer) await sync(customer);
  return true;
}
