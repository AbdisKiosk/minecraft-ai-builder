import type { NextRequest } from 'next/server';
import { handleWebhook } from '../../../../lib/server/billing';
import { json } from '../../../../lib/server/route';

/**
 * Stripe's webhook: subscription changes that happen away from the panel (a renewal that fails, a cancellation in the
 * portal). Stripe signs the raw body with STRIPE_WEBHOOK_SECRET; anything unsigned is refused. Register it for
 * checkout.session.completed and customer.subscription.created, .updated and .deleted.
 */
export async function POST(req: NextRequest): Promise<Response> {
  if (!process.env.STRIPE_SECRET_KEY || !process.env.STRIPE_WEBHOOK_SECRET) return json({ error: 'billing webhook is not configured' }, 404);
  try {
    const ok = await handleWebhook(await req.text(), req.headers.get('stripe-signature'));
    return ok ? json({ received: true }) : json({ error: 'bad signature' }, 400);
  } catch (e) {
    // A 500 makes Stripe deliver the event again later.
    console.error('billing webhook failed', e);
    return json({ error: 'internal error' }, 500);
  }
}
