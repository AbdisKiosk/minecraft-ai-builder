import { NextResponse } from 'next/server';
import { billingEnabled, billingOf, checkoutUrl } from '../../../../lib/server/billing';
import { baseUrl, HttpError, route } from '../../../../lib/server/route';
import { currentUser } from '../../../../lib/server/session';

/**
 * Sends the browser to Stripe Checkout to subscribe to Pro: a plain link target, so the pricing page needs no script.
 * Signed out, it goes through sign-up and comes back; already subscribed, it goes to the Billing page.
 */
export const GET = route(async req => {
  if (!billingEnabled()) throw new HttpError(404, 'this panel sells no plans');
  const base = baseUrl(req);
  const user = await currentUser();
  if (!user) return NextResponse.redirect(`${base}/auth/login?screen_hint=signup&returnTo=${encodeURIComponent('/api/billing/checkout')}`);
  if ((await billingOf(user.id)).plan !== 'free') return NextResponse.redirect(`${base}/dashboard#/billing`);
  return NextResponse.redirect(await checkoutUrl(user, base), 303);
});
