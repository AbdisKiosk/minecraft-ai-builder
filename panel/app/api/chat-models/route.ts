import { PROVIDERS, type ChatModel, type ChatSource } from '../../../lib/chat';
import { modelsOf } from '../../../lib/server/chat/models';
import { creditsEnabled } from '../../../lib/server/credits';
import { json, requireUser, route } from '../../../lib/server/route';

/**
 * The models a chat can use, per source: {models: {anthropic: [...], openrouter: [...], credits: [...]}, stale:
 * [providers whose list could not be read just now and is older or shorter]}. The credits list has the prices credits
 * pay, and is there only on a panel that sells them.
 */
export const GET = route(async () => {
  await requireUser();
  const models: Partial<Record<ChatSource, ChatModel[]>> = {};
  const stale: ChatSource[] = [];
  for (const source of [...PROVIDERS.map(p => p.id), ...(creditsEnabled() ? ['credits' as const] : [])]) {
    const r = await modelsOf(source);
    models[source] = r.models;
    if (r.stale) stale.push(source);
  }
  return json({ models, stale });
});
