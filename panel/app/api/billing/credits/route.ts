import { NextResponse } from 'next/server';
import { topupRefusal } from '../../../../lib/credits';
import { topupUrl } from '../../../../lib/server/billing';
import { creditsEnabled } from '../../../../lib/server/credits';
import { baseUrl, HttpError, route } from '../../../../lib/server/route';
import { currentUser } from '../../../../lib/server/session';

/**
 * Sends the browser to Stripe Checkout to buy ?amount= dollars of credits: a plain link target, like the checkout of a
 * plan. Signed out, it goes through sign-in and comes back.
 */
export const GET = route(async req => {
  if (!creditsEnabled()) throw new HttpError(404, 'this panel sells no credits');
  const amount = Number(req.nextUrl.searchParams.get('amount'));
  const refused = topupRefusal(amount);
  if (refused) throw new HttpError(400, refused);
  const base = baseUrl(req);
  const user = await currentUser();
  if (!user) return NextResponse.redirect(`${base}/auth/login?returnTo=${encodeURIComponent(`/api/billing/credits?amount=${amount}`)}`);
  return NextResponse.redirect(await topupUrl(user, base, amount), 303);
});
