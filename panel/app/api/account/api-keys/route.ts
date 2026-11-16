import { apiKeyViews } from '../../../../lib/server/apiKeys';
import { json, requireUser, route } from '../../../../lib/server/route';

// The signed-in user's API keys for the panel's chat, one per provider. No answer ever carries a key.

/** {keys: {anthropic: {hint, created, usable} | null, openrouter: ...}}. */
export const GET = route(async () => {
  const user = await requireUser();
  const res = json({ keys: await apiKeyViews(user.id) });
  res.headers.set('Cache-Control', 'no-store');
  return res;
});
