import type { Metadata } from 'next';
import { LandingFooter, LandingNav, SIGN_UP_HREF } from '../../components/Landing';
import { auth0 } from '../../lib/server/auth0';

export const dynamic = 'force-dynamic';

export const metadata: Metadata = { title: 'Pricing · AIBuilder' };

interface Plan {
  name: string;
  /** US dollars a month. */
  price: number;
  blurb: string;
  /** The line above the feature list, e.g. what the plan builds on. */
  builtOn?: string;
  features: string[];
  /** What the plan leaves out, shown struck through. */
  missing?: string[];
  featured?: boolean;
}

// The plans on sale now. Nothing here is enforced by the panel yet.
const PLANS: Plan[] = [
  {
    name: 'Free',
    price: 0,
    blurb: 'Try it on one server.',
    features: ['1 server', '2 members', 'Unlimited builds', '7 days of history and undo'],
    missing: ['Asset library'],
  },
  {
    name: 'Pro',
    price: 10,
    blurb: 'For a server you build on every week.',
    builtOn: 'Everything in Free, and:',
    features: ['3 servers', '10 members', 'Full history and undo', 'Asset library', 'Review mode with drafts', 'REST API for build scripts'],
    featured: true,
  },
  {
    name: 'Studio',
    price: 29,
    blurb: 'For build teams and server networks.',
    builtOn: 'Everything in Pro, and:',
    features: ['Up to 10 servers', 'Unlimited members', 'Promote builds between servers', 'WorldGuard protection', 'Priority support'],
  },
];

export default async function Pricing() {
  const signedIn = !!(await auth0.getSession());
  const startHref = signedIn ? '/dashboard' : SIGN_UP_HREF;
  return (
    <div className="landing">
      <LandingNav signedIn={signedIn} />

      <header className="pricing-head">
        <h1>Pricing</h1>
        <p className="lead">You bring your own Claude. AIBuilder connects it to your server and keeps every edit
          reviewable and undoable.</p>
      </header>

      <p className="pricing-offer"><b>Temporary discount: Pro includes everything in Studio.</b> For a limited time,
        $10 a month gets you the whole product.</p>

      <section className="plans">
        {PLANS.map(plan => (
          <div key={plan.name} className={plan.featured ? 'card plan featured' : 'card plan'}>
            <h2>{plan.name}{plan.featured && <span className="badge running">Discount</span>}</h2>
            <p className="plan-price"><b>${plan.price}</b>{plan.price > 0 && <span> / month</span>}</p>
            <p className="muted">{plan.blurb}</p>
            {plan.name === 'Studio'
              ? <span className="button ghost disabled" aria-disabled="true">Included in Pro for now</span>
              : <a className={plan.featured ? 'button' : 'button ghost'} href={startHref}>
                {plan.price ? 'Get Pro' : 'Get started free'}</a>}
            {plan.builtOn && <p className="plan-built-on">{plan.builtOn}</p>}
            <ul>
              {plan.features.map(feature => <li key={feature}>{feature}</li>)}
              {plan.featured && <li className="extra">Everything in Studio, for a limited time</li>}
              {plan.missing?.map(feature => <li key={feature} className="missing"><s>{feature}</s></li>)}
            </ul>
          </div>
        ))}
      </section>

      <p className="pricing-note muted">Prices are in US dollars. Claude itself is not included: you connect your own
        Claude Code.</p>

      <LandingFooter />
    </div>
  );
}
