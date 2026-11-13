import { NextResponse } from 'next/server';
import { billingEnabled, ownsCheckout, syncUser } from '../../../../lib/server/billing';
import { baseUrl, HttpError, route } from '../../../../lib/server/route';
import { currentUser } from '../../../../lib/server/session';

/**
 * Where Stripe Checkout sends the browser after paying. Reads the new subscription from Stripe right away, so the
 * Billing page shows the plan without waiting for the webhook.
 */
export const GET = route(async req => {
  if (!billingEnabled()) throw new HttpError(404, 'this panel sells no plans');
  const base = baseUrl(req);
  const user = await currentUser();
  if (!user) return NextResponse.redirect(`${base}/auth/login?returnTo=${encodeURIComponent('/dashboard#/billing')}`);
  const session = req.nextUrl.searchParams.get('session_id');
  if (session && await ownsCheckout(user, session)) await syncUser(user.id);
  return NextResponse.redirect(`${base}/dashboard#/billing`);
});
