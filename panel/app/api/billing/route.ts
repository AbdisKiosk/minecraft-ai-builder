import { limitsOf, PLANS, PRO_INCLUDES_STUDIO, UNLIMITED } from '../../../lib/plans';
import { billingEnabled, billingOf } from '../../../lib/server/billing';
import { one } from '../../../lib/server/db';
import { json, requireUser, route } from '../../../lib/server/route';

/** The caller's plan, what it allows and how much of it they use. `enabled` is false on a panel that sells no plans. */
export const GET = route(async () => {
  const user = await requireUser();
  const owned = await one<{ n: number }>('SELECT COUNT(*) AS n FROM servers WHERE owner_id = ?', [user.id]);
  const usage = { servers: Number(owned?.n ?? 0) };
  if (!billingEnabled()) return json({ enabled: false, plan: 'free', status: null, renews: null, cancels: false, limits: UNLIMITED, usage });
  const billing = await billingOf(user.id);
  return json({
    enabled: true,
    ...billing,
    limits: limitsOf(billing.plan),
    usage,
    pro: { price: PLANS.pro.price, includesStudio: PRO_INCLUDES_STUDIO },
  });
});
