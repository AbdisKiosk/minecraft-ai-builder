import type { Metadata } from 'next';
import { LandingFooter, LandingNav, SIGN_UP_HREF } from '../../components/Landing';
import { PLANS, PRO_INCLUDES_STUDIO, type Plan } from '../../lib/plans';
import { auth0 } from '../../lib/server/auth0';
import { billingEnabled } from '../../lib/server/billing';

export const dynamic = 'force-dynamic';

export const metadata: Metadata = { title: 'Pricing · AIBuilder' };

interface Card {
  plan: Plan;
  blurb: string;
  /** The line above the feature list, e.g. what the plan builds on. */
  builtOn?: string;
  features: string[];
  /** What the plan leaves out, shown struck through. */
  missing?: string[];
  featured?: boolean;
}

const servers = (n: number) => `${n === 1 ? '1 server' : `${n} servers`}`;
const members = (n: number | null) => (n === null ? 'Unlimited members' : `${n} members`);
const history = (days: number | null) => (days === null ? 'Full history and undo' : `${days} days of history and undo`);

// The numbers come from lib/plans.ts, which the panel enforces.
const { free, pro, studio } = PLANS;
const CARDS: Card[] = [
  {
    plan: free,
    blurb: 'Try it on one server.',
    features: [servers(free.limits.servers), members(free.limits.members), 'Unlimited builds', history(free.limits.historyDays)],
    missing: ['Asset library'],
  },
  {
    plan: pro,
    blurb: 'For a server you build on every week.',
    builtOn: 'Everything in Free, and:',
    features: [servers(pro.limits.servers), members(pro.limits.members), history(pro.limits.historyDays), 'Asset library',
      'Review mode with drafts', 'REST API for build scripts'],
    featured: true,
  },
  {
    plan: studio,
    blurb: 'For build teams and server networks.',
    builtOn: 'Everything in Pro, and:',
    features: [`Up to ${servers(studio.limits.servers)}`, members(studio.limits.members), 'Promote builds between servers',
      'WorldGuard protection', 'Priority support'],
  },
];

export default async function Pricing() {
  const signedIn = !!(await auth0.getSession());
  const startHref = signedIn ? '/dashboard' : SIGN_UP_HREF;
  // Without Stripe there is nothing to pay for: the Pro button is a plain sign-up, as for Free.
  const proHref = billingEnabled() ? '/api/billing/checkout' : startHref;
  return (
    <div className="landing">
      <LandingNav signedIn={signedIn} />

      <header className="pricing-head">
        <h1>Pricing</h1>
        <p className="lead">You bring your own AI. AIBuilder connects it to your server and keeps every edit
          reviewable and undoable.</p>
      </header>

      {PRO_INCLUDES_STUDIO && (
        <p className="pricing-offer"><b>Temporary discount: Pro includes everything in Studio.</b> For a limited time,
          ${pro.price} a month gets you the whole product.</p>
      )}

      <section className="plans">
        {CARDS.map(({ plan, ...card }) => (
          <div key={plan.id} className={card.featured ? 'card plan featured' : 'card plan'}>
            <h2>{plan.name}{card.featured && PRO_INCLUDES_STUDIO && <span className="badge running">Discount</span>}</h2>
            <p className="plan-price"><b>${plan.price}</b>{plan.price > 0 && <span> / month</span>}</p>
            <p className="muted">{card.blurb}</p>
            {plan.id === 'studio'
              ? <span className="button ghost disabled" aria-disabled="true">{PRO_INCLUDES_STUDIO ? 'Included in Pro for now' : 'Coming soon'}</span>
              : plan.id === 'pro' ? <a className="button" href={proHref}>Get Pro</a>
              : <a className="button ghost" href={startHref}>Get started free</a>}
            {card.builtOn && <p className="plan-built-on">{card.builtOn}</p>}
            <ul>
              {card.features.map(feature => <li key={feature}>{feature}</li>)}
              {card.featured && PRO_INCLUDES_STUDIO && <li className="extra">Everything in Studio, for a limited time</li>}
              {card.missing?.map(feature => <li key={feature} className="missing"><s>{feature}</s></li>)}
            </ul>
          </div>
        ))}
      </section>

      <p className="pricing-note muted">Prices are in US dollars. The AI itself is not included: you connect your
        own assistant.</p>

      <LandingFooter />
    </div>
  );
}
