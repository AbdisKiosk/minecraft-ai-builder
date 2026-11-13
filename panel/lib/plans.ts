// The plans, their prices and what each allows. The pricing page shows these and lib/server/billing.ts enforces them.

export type PlanId = 'free' | 'pro' | 'studio';

export interface Limits {
  /** Servers an account may own. */
  servers: number;
  /** Members of a server, its owner included; null is no limit. */
  members: number | null;
  /** How far back a server's history can be opened and undone; null is all of it. */
  historyDays: number | null;
  /** Saving, uploading and sharing personal assets. */
  assets: boolean;
  /** The REST endpoints under /s/<server>/api/ that build scripts use. MCP is in every plan. */
  restApi: boolean;
  /** Copying a region from one server into a draft on another. */
  promote: boolean;
}

export interface Plan { id: PlanId; name: string; /** US dollars a month. */ price: number; limits: Limits }

/**
 * A temporary discount: while this is true, Pro allows everything Studio does. Turning it off gives Pro its own
 * limits again, for new and existing subscribers alike.
 */
export const PRO_INCLUDES_STUDIO = true;

export const PLANS: Record<PlanId, Plan> = {
  free: { id: 'free', name: 'Free', price: 0, limits: { servers: 1, members: 2, historyDays: 7, assets: false, restApi: false, promote: false } },
  pro: { id: 'pro', name: 'Pro', price: 10, limits: { servers: 3, members: 10, historyDays: null, assets: true, restApi: true, promote: false } },
  studio: { id: 'studio', name: 'Studio', price: 29, limits: { servers: 10, members: null, historyDays: null, assets: true, restApi: true, promote: true } },
};

/** Everything allowed: what an account has on a panel that does not sell plans. */
export const UNLIMITED: Limits = { servers: 10, members: null, historyDays: null, assets: true, restApi: true, promote: true };

export function isPlan(v: unknown): v is PlanId {
  return v === 'free' || v === 'pro' || v === 'studio';
}

/** What a plan allows today, with the discount applied. */
export function limitsOf(plan: PlanId): Limits {
  return plan === 'pro' && PRO_INCLUDES_STUDIO ? PLANS.studio.limits : PLANS[plan].limits;
}

// ---- Stripe subscriptions ----

/** The Stripe statuses during which a subscription still grants its plan; past_due is Stripe retrying the card. */
export function grants(status: string): boolean {
  return status === 'active' || status === 'trialing' || status === 'past_due';
}

/** The plan a subscription's price stands for: Pro, unless `studio` (STRIPE_PRICE_STUDIO) names the price or its product. */
export function planOfPrice(price: { id: string; product: unknown }, studio: string | undefined): PlanId {
  const product = typeof price.product === 'string' ? price.product : (price.product as { id?: string } | null)?.id;
  return studio && (studio === price.id || studio === product) ? 'studio' : 'pro';
}

// ---- what a plan refuses, in the words the panel shows ----

const UPGRADE = 'Upgrade on the Billing page of the panel.';

/** Why an account that owns `owned` servers may not add one, or null when it may. */
export function serverRefusal(limits: Limits, owned: number): string | null {
  if (owned < limits.servers) return null;
  return `your plan allows ${count(limits.servers, 'server')} and you own ${owned}. ${UPGRADE}`;
}

/** Why a server with `members` members may not take one more, or null when it may. */
export function memberRefusal(limits: Limits, members: number): string | null {
  if (limits.members === null || members < limits.members) return null;
  return `the owner's plan allows ${count(limits.members, 'member')} on a server and this one has ${members}. `
    + 'The owner can upgrade on the Billing page of the panel.';
}

const FEATURES = {
  assets: 'The asset library',
  restApi: 'The REST API for build scripts',
  promote: 'Promoting builds between servers',
} as const;
export type Feature = keyof typeof FEATURES;

/** Why the plan does not allow a feature, or null when it does. */
export function featureRefusal(limits: Limits, feature: Feature): string | null {
  if (limits[feature]) return null;
  const plan = (['pro', 'studio'] as const).find(p => limitsOf(p)[feature]);
  return `${FEATURES[feature]} is part of the ${PLANS[plan ?? 'studio'].name} plan. ${UPGRADE}`;
}

/** The time before which history is closed on this plan, in epoch milliseconds, or null when all of it is open. */
export function historyCutoff(limits: Limits, now: number): number | null {
  return limits.historyDays === null ? null : now - limits.historyDays * 86_400_000;
}

/** Why a history entry from `time` cannot be opened, or null when it can. */
export function historyRefusal(limits: Limits, time: number, now: number): string | null {
  const cutoff = historyCutoff(limits, now);
  if (cutoff === null || time >= cutoff) return null;
  return `the owner's plan keeps ${count(limits.historyDays!, 'day')} of history open and this edit is older. `
    + 'It is still stored: the owner can upgrade on the Billing page of the panel to open it.';
}

function count(n: number, word: string): string {
  return `${n} ${word}${n === 1 ? '' : 's'}`;
}
