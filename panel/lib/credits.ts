import { CLAUDE, type ChatModel } from './chat';

// Credits: a prepaid balance in dollars that pays for chats on the panel's own Anthropic key. A request costs what
// Anthropic charges the panel for it plus MARKUP. Amounts are whole micro-dollars (millionths of a dollar), which is
// also Anthropic's price per token when the price is in dollars per million tokens. The browser and the server both
// use this file; lib/server/credits.ts keeps the ledger.

/** What the panel adds to Anthropic's price: 0.6 is 60%. */
export const MARKUP = 0.6;
export const MICROS_PER_DOLLAR = 1_000_000;

/** The least and the most one top-up can be, in dollars, and the amounts the Billing page offers. */
export const MIN_TOPUP = 10;
export const MAX_TOPUP = 500;
export const TOPUPS: readonly number[] = [10, 25, 50, 100];

/** The tokens of one request to the model, as Anthropic bills them. */
export interface RoundUsage {
  /** Prompt tokens that were neither read from nor written to the cache. */
  input: number;
  cacheWrite: number;
  cacheRead: number;
  output: number;
}

type Claude = (typeof CLAUDE)[number];
const claude = (id: string): Claude | undefined => CLAUDE.find(m => m.anthropic === id);

/** What Anthropic charges for one request, in micro-dollars (not rounded); null for a model credits cannot pay for. */
export function providerMicros(model: string, u: RoundUsage): number | null {
  const m = claude(model);
  if (!m) return null;
  // The prompt's length decides the price of the whole request, cached tokens and the answer included.
  const times = 'long' in m && u.input + u.cacheWrite + u.cacheRead > m.long.over ? m.long.times : 1;
  return times * (u.input * m.input + u.cacheWrite * m.cacheWrite + u.cacheRead * m.cacheRead + u.output * m.output);
}

/** What one request costs in credits: Anthropic's price plus the markup, rounded up to a whole micro-dollar. */
export function chargeMicros(model: string, u: RoundUsage): number | null {
  const cost = providerMicros(model, u);
  // 1e-6 below, so a product that is a whole number but for floating point does not round up.
  return cost === null ? null : Math.max(0, Math.ceil(cost * (1 + MARKUP) - 1e-6));
}

const marked = (dollars: number) => +(dollars * (1 + MARKUP)).toFixed(6);

/** The models a chat on credits can use, with the prices credits pay. */
export const CREDIT_MODELS: readonly ChatModel[] = CLAUDE.map(m => ({
  provider: 'credits', id: m.anthropic, name: m.name, price: { input: marked(m.input), output: marked(m.output) },
  context: 1_000_000, maxOutput: 128_000, images: true, claude: true, adaptive: true,
  ...('long' in m ? { long: m.long } : {}),
}));

/** "$12.34"; an amount under a cent that is not zero gets four decimals ("$0.0042"), and a debt is "-$1.20". */
export function usd(micros: number): string {
  const dollars = Math.abs(micros) / MICROS_PER_DOLLAR;
  const sign = micros < 0 ? '-' : '';
  // Never "$0.0000" for something that cost.
  if (dollars > 0 && dollars < 0.01) return `${sign}$${Math.max(dollars, 0.0001).toFixed(4)}`;
  return `${sign}$${dollars.toFixed(2)}`;
}

/** Why `amount` dollars cannot be bought, or null when it can. */
export function topupRefusal(amount: number): string | null {
  if (!Number.isInteger(amount)) return 'a top-up is a whole number of dollars';
  if (amount < MIN_TOPUP) return `the smallest top-up is $${MIN_TOPUP}`;
  if (amount > MAX_TOPUP) return `the largest top-up is $${MAX_TOPUP}`;
  return null;
}

/** One line of an account's credit history. */
export interface CreditEntry {
  id: number;
  /** topup: bought. usage: one turn of a chat. refund, dispute: money that went back. grant: given by the panel. */
  kind: 'topup' | 'usage' | 'refund' | 'dispute' | 'grant';
  /** Micro-dollars added to the balance; negative when it took credits. */
  micros: number;
  /** When, in epoch milliseconds. */
  at: number;
  /** The model a turn used. */
  model: string | null;
}

/** What GET /api/billing says about credits; null there when the panel sells none. */
export interface CreditsView {
  /** Micro-dollars left; below zero when the last request cost more than was left. */
  balance: number;
  markup: number;
  topups: readonly number[];
  min: number;
  max: number;
  /** The newest entries first. */
  entries: CreditEntry[];
}
