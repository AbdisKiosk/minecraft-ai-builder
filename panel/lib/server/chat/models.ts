import { ANTHROPIC_MODELS, DEFAULT_MODEL, OPENROUTER_FALLBACK, type ChatModel, type ChatSource } from '../../chat';
import { CREDIT_MODELS } from '../../credits';

// The models a chat can use. Anthropic's are a fixed list, and credits pay for the same ones at their own prices;
// OpenRouter's come from its public catalog, cut down to the ones that can call tools (the chat builds through tools),
// and kept for a while so a page view does not ask again.

const CATALOG = 'https://openrouter.ai/api/v1/models';
const KEEP_MS = 10 * 60_000;

type Json = Record<string, any>;

/** A catalog entry as a chat model; null for one the chat cannot use. */
export function openRouterModel(m: Json): ChatModel | null {
  const id = typeof m?.id === 'string' ? m.id : '';
  const params: unknown[] = Array.isArray(m.supported_parameters) ? m.supported_parameters : [];
  const endpoints: unknown[] | null = Array.isArray(m.endpoints) ? m.endpoints : null;
  // Batch variants answer later, not in a conversation.
  if (!id || id.endsWith(':batch') || !params.includes('tools')) return null;
  if (endpoints && !endpoints.includes('/api/v1/messages')) return null;
  const perMillion = (v: unknown) => {
    const n = Number(v);
    return Number.isFinite(n) && n >= 0 ? Math.round(n * 1e6 * 1e4) / 1e4 : null;
  };
  const input = perMillion(m.pricing?.prompt), output = perMillion(m.pricing?.completion);
  const efforts: unknown[] = Array.isArray(m.reasoning?.supported_efforts) ? m.reasoning.supported_efforts : [];
  const claude = id.startsWith('anthropic/');
  const count = (v: unknown) => (typeof v === 'number' && v > 0 ? v : null);
  return {
    provider: 'openrouter',
    id,
    name: typeof m.name === 'string' && m.name ? m.name : id,
    price: input === null || output === null ? null : { input, output },
    context: count(m.context_length),
    maxOutput: count(m.top_provider?.max_completion_tokens),
    images: Array.isArray(m.architecture?.input_modalities) && m.architecture.input_modalities.includes('image'),
    claude,
    // Claude from 4.6 on; OpenRouter lists its effort levels. Other models think as they do by default.
    adaptive: claude && efforts.includes('high'),
  };
}

/** The catalog's usable models: Claude first, then the rest in the catalog's order (newest first). */
export function openRouterModels(catalog: unknown): ChatModel[] {
  const data: Json[] = Array.isArray((catalog as Json)?.data) ? (catalog as Json).data : [];
  const models = data.map(openRouterModel).filter((m): m is ChatModel => m !== null);
  return [...models.filter(m => m.claude), ...models.filter(m => !m.claude)];
}

interface Cached { models: ChatModel[]; at: number }
// One cache per process: Next's route bundles each load their own copy of this module.
const g = globalThis as { __aibOpenRouterModels?: Cached; __aibOpenRouterLoading?: Promise<ChatModel[]> };

/**
 * OpenRouter's models, read at most every ten minutes. When the catalog can't be read, the last list read, or else
 * the Claude models, with `stale` set.
 */
export async function openRouterCatalog(fetchImpl: typeof fetch = fetch): Promise<{ models: ChatModel[]; stale: boolean }> {
  const cached = g.__aibOpenRouterModels;
  if (cached && Date.now() - cached.at < KEEP_MS) return { models: cached.models, stale: false };
  g.__aibOpenRouterLoading ??= (async () => {
    const res = await fetchImpl(CATALOG, { signal: AbortSignal.timeout(15_000) });
    if (!res.ok) throw new Error(`OpenRouter's model list answered ${res.status}`);
    const models = openRouterModels(await res.json());
    if (!models.length) throw new Error('OpenRouter’s model list had no model that can use tools');
    g.__aibOpenRouterModels = { models, at: Date.now() };
    return models;
  })().finally(() => { g.__aibOpenRouterLoading = undefined; });
  try {
    return { models: await g.__aibOpenRouterLoading, stale: false };
  } catch (e) {
    console.warn('chat: could not read OpenRouter’s models', (e as Error).message);
    return { models: cached?.models ?? [...OPENROUTER_FALLBACK], stale: true };
  }
}

export async function modelsOf(source: ChatSource): Promise<{ models: ChatModel[]; stale: boolean }> {
  if (source === 'openrouter') return openRouterCatalog();
  return { models: [...(source === 'credits' ? CREDIT_MODELS : ANTHROPIC_MODELS)], stale: false };
}

/** The source's model with this id, or null when it has none the chat can use. */
export async function findModel(source: ChatSource, id: string): Promise<ChatModel | null> {
  return (await modelsOf(source)).models.find(m => m.id === id) ?? null;
}

export function defaultModel(provider: ChatSource): string {
  return DEFAULT_MODEL[provider];
}
