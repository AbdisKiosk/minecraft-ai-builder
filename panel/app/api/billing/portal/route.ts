import { NextResponse } from 'next/server';
import { billingEnabled, portalUrl } from '../../../../lib/server/billing';
import { baseUrl, HttpError, requireUser, route } from '../../../../lib/server/route';

/** Sends the browser to Stripe's Customer Portal: change the card, see invoices, cancel. */
export const GET = route(async req => {
  if (!billingEnabled()) throw new HttpError(404, 'this panel sells no plans');
  const base = baseUrl(req);
  const url = await portalUrl(await requireUser(), base);
  return NextResponse.redirect(url ?? `${base}/dashboard#/billing`, 303);
});
