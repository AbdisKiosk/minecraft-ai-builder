import { PROVIDERS, type ChatModel, type ProviderId } from '../../../lib/chat';
import { modelsOf } from '../../../lib/server/chat/models';
import { json, requireUser, route } from '../../../lib/server/route';

/**
 * The models a chat can use, per provider: {models: {anthropic: [...], openrouter: [...]}, stale: [providers whose
 * list could not be read just now and is older or shorter]}.
 */
export const GET = route(async () => {
  await requireUser();
  const models = {} as Record<ProviderId, ChatModel[]>;
  const stale: ProviderId[] = [];
  for (const p of PROVIDERS) {
    const r = await modelsOf(p.id);
    models[p.id] = r.models;
    if (r.stale) stale.push(p.id);
  }
  return json({ models, stale });
});
