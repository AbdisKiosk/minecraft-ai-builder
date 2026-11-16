import { isProvider, type ProviderId } from '../../../../../lib/chat';
import { apiKeyViews, deleteApiKey, keyRefusal, saveApiKey } from '../../../../../lib/server/apiKeys';
import { checkKey, KeyCheckError } from '../../../../../lib/server/chat/providers';
import { body, HttpError, json, requireUser, route } from '../../../../../lib/server/route';

type P = { provider: string };

function providerOf(p: string): ProviderId {
  if (!isProvider(p)) throw new HttpError(404, `unknown provider '${p}'`);
  return p;
}

/** Saves {"key": "..."} for the provider, after the provider accepted it once (a check that spends nothing). */
export const PUT = route<P>(async (req, params) => {
  const user = await requireUser();
  const provider = providerOf(params.provider);
  const b = await body(req);
  const key = typeof b.key === 'string' ? b.key.trim() : '';
  const refused = keyRefusal(provider, key);
  if (refused) throw new HttpError(400, refused);
  try {
    await checkKey(provider, key);
  } catch (e) {
    if (e instanceof KeyCheckError) throw new HttpError(e.kind === 'refused' ? 400 : 502, e.message);
    throw e;
  }
  await saveApiKey(user.id, provider, key);
  const res = json({ keys: await apiKeyViews(user.id) });
  res.headers.set('Cache-Control', 'no-store');
  return res;
});

export const DELETE = route<P>(async (_req, params) => {
  const user = await requireUser();
  await deleteApiKey(user.id, providerOf(params.provider));
  return new Response(null, { status: 204 });
});
