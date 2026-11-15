import Anthropic from '@anthropic-ai/sdk';
import { apiKeyView, deleteApiKey, keyRefusal, saveApiKey } from '../../../../lib/server/apiKeys';
import { body, HttpError, json, requireUser, route } from '../../../../lib/server/route';

// The signed-in user's Anthropic API key for the panel's chat. No answer ever carries the key.

function private_(res: Response): Response {
  res.headers.set('Cache-Control', 'no-store');
  return res;
}

/** {key: {hint, created, usable} | null}. */
export const GET = route(async () => {
  const user = await requireUser();
  return private_(json({ key: await apiKeyView(user.id) }));
});

/** Saves {"key": "sk-ant-..."} after Anthropic accepted it once (listing models spends nothing). */
export const PUT = route(async req => {
  const user = await requireUser();
  const b = await body(req);
  const key = typeof b.key === 'string' ? b.key.trim() : '';
  const refused = keyRefusal(key);
  if (refused) throw new HttpError(400, refused);
  try {
    await new Anthropic({ apiKey: key, maxRetries: 1, timeout: 15_000 }).models.list({ limit: 1 });
  } catch (e) {
    if (e instanceof Anthropic.AuthenticationError || e instanceof Anthropic.PermissionDeniedError) {
      throw new HttpError(400, 'Anthropic does not accept this key. Check that it was copied whole and is still active.');
    }
    if (e instanceof Anthropic.APIError) throw new HttpError(502, 'could not check the key with Anthropic just now; try again');
    throw e;
  }
  await saveApiKey(user.id, key);
  return private_(json({ key: await apiKeyView(user.id) }));
});

export const DELETE = route(async () => {
  const user = await requireUser();
  await deleteApiKey(user.id);
  return new Response(null, { status: 204 });
});
