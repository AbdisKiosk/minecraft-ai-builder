import type { Limits, PlanId } from '../lib/plans';
import { PLANS } from '../lib/plans';
import { useFetched } from '../lib/useFetched';

interface Billing {
  enabled: boolean;
  plan: PlanId;
  status: string | null;
  renews: number | null;
  cancels: boolean;
  limits: Limits;
  usage: { servers: number };
  pro?: { price: number; includesStudio: boolean };
}

const day = (ms: number) => new Date(ms).toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' });

/** The account's plan, what it allows, and the way to Stripe to subscribe or manage the subscription. */
export default function BillingPage() {
  const { data: b, error } = useFetched<Billing>('/api/billing');
  if (error) return <div className="card"><p className="error">{error}</p></div>;
  if (!b) return <p className="muted">Loading…</p>;
  if (!b.enabled) {
    return (
      <>
        <div className="page-head"><div><h1>Billing</h1></div></div>
        <section className="card"><p style={{ margin: 0 }}>This panel doesn&apos;t sell plans: everything is included.</p></section>
      </>
    );
  }
  const l = b.limits;
  const paid = b.plan !== 'free';
  return (
    <>
      <div className="page-head"><div><h1>Billing</h1>
        <div className="muted">Your plan covers the servers you own. Servers you were invited to use their owner&apos;s plan.</div></div></div>
      <section className="card billing-plan">
        <div>
          <h2>{PLANS[b.plan].name} plan{b.status === 'past_due' && <span className="badge failed">Payment failed</span>}</h2>
          <p className="muted">
            {!paid ? 'You are on the free plan.'
              : b.status === 'past_due' ? 'The last payment did not go through. Update your card to keep the plan.'
              : b.renews && b.cancels ? `Cancelled: the plan ends on ${day(b.renews)}.`
              : b.renews ? `$${PLANS[b.plan].price} a month. Renews on ${day(b.renews)}.`
              : `$${PLANS[b.plan].price} a month.`}
          </p>
        </div>
        {paid ? <a className="button ghost" href="/api/billing/portal">Manage billing</a>
          : <a className="button" href="/api/billing/checkout">Upgrade to Pro, ${b.pro?.price ?? PLANS.pro.price} / month</a>}
      </section>
      {!paid && b.pro?.includesStudio && (
        <p className="pricing-offer"><b>Temporary discount: Pro includes everything in Studio.</b> See <a href="/pricing">the plans</a>.</p>
      )}
      <section className="card">
        <h2>What your plan includes</h2>
        <ul className="billing-limits">
          <li><b>{b.usage.servers} of {l.servers}</b> servers you own</li>
          <li><b>{l.members === null ? 'Unlimited' : l.members}</b> members on each server</li>
          <li><b>{l.historyDays === null ? 'All' : `${l.historyDays} days`}</b> of history to open and undo</li>
          <li className={l.assets ? '' : 'off'}>Asset library</li>
          <li className={l.restApi ? '' : 'off'}>REST API for build scripts</li>
          <li className={l.promote ? '' : 'off'}>Promote builds between servers</li>
        </ul>
        {!paid && b.usage.servers > l.servers && (
          <p className="muted small-print">You own more servers than the free plan allows. They keep working; you can&apos;t add another.</p>
        )}
      </section>
    </>
  );
}
