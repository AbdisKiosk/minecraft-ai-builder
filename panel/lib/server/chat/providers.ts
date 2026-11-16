import Anthropic from '@anthropic-ai/sdk';
import { providerName, type ProviderId } from '../../chat';

// How the chat reaches each provider. Both take Anthropic's Messages API, so one client and one loop serve them; only
// the address, the way the key is sent and a few request fields differ.

type Fetch = typeof fetch;

interface Setup {
  baseURL: string;
  /** Sent as the SDK's apiKey (Anthropic's own header) or as a plain bearer token. */
  auth: 'apiKey' | 'bearer';
  /** Fields this provider takes on top of the Messages API's. */
  extra?: Record<string, unknown>;
}

const SETUP: Record<ProviderId, Setup> = {
  anthropic: { baseURL: 'https://api.anthropic.com', auth: 'apiKey' },
  // OpenRouter serves Claude from Anthropic and from cloud platforms; Anthropic's own API first, as OpenRouter
  // recommends for Claude agents, with the others as fallbacks.
  openrouter: { baseURL: 'https://openrouter.ai/api', auth: 'bearer', extra: { provider: { order: ['anthropic'] } } },
};

/**
 * A client that sends `key` to `provider` and nowhere else. Every option is given, so none comes from the panel's
 * environment: an ANTHROPIC_API_KEY or ANTHROPIC_BASE_URL set there must never mix with a user's request.
 */
export function clientFor(provider: ProviderId, key: string, fetchImpl?: Fetch): Anthropic {
  const s = SETUP[provider];
  return new Anthropic({
    baseURL: s.baseURL,
    ...(s.auth === 'apiKey' ? { apiKey: key, authToken: null } : { apiKey: null, authToken: key }),
    ...(fetchImpl ? { fetch: fetchImpl } : {}),
  });
}

/** The request with the provider's own fields added. */
export function withExtra<T extends object>(provider: ProviderId, params: T): T {
  return { ...params, ...SETUP[provider].extra };
}

export class KeyCheckError extends Error {
  /** refused: the provider does not accept the key. unavailable: it could not be asked. */
  readonly kind: 'refused' | 'unavailable';

  constructor(kind: 'refused' | 'unavailable', message: string) {
    super(message);
    this.kind = kind;
  }
}

/** Asks the provider whether it accepts `key`, without spending anything. Throws KeyCheckError when it does not. */
export async function checkKey(provider: ProviderId, key: string, fetchImpl: Fetch = fetch): Promise<void> {
  const refused = () => new KeyCheckError('refused',
    `${providerName(provider)} does not accept this key. Check that it was copied whole and is still active.`);
  const unavailable = () => new KeyCheckError('unavailable', `could not check the key with ${providerName(provider)} just now; try again`);
  if (provider === 'anthropic') {
    try {
      await clientFor(provider, key, fetchImpl).models.list({ limit: 1 }, { maxRetries: 1, timeout: 15_000 });
    } catch (e) {
      if (e instanceof Anthropic.AuthenticationError || e instanceof Anthropic.PermissionDeniedError) throw refused();
      if (e instanceof Anthropic.APIError) throw unavailable();
      throw e;
    }
    return;
  }
  // OpenRouter's model list is public, so it says nothing about a key; this answers with the key's own details.
  let res: Response;
  try {
    res = await fetchImpl(`${SETUP.openrouter.baseURL}/v1/key`, {
      headers: { Authorization: `Bearer ${key}` }, signal: AbortSignal.timeout(15_000),
    });
  } catch {
    throw unavailable();
  }
  if (res.status === 401 || res.status === 403) throw refused();
  if (!res.ok) throw unavailable();
}

/** What went wrong with a request to the provider, in words for the user; null when it is not the provider's error. */
export function providerFailure(provider: ProviderId, e: unknown): string | null {
  const name = providerName(provider);
  if (e instanceof Anthropic.AuthenticationError) return `${name} no longer accepts your API key. Replace it under Access tokens.`;
  if (e instanceof Anthropic.PermissionDeniedError) return `Your ${name} key may not use this model. Pick another model, or check the key with ${name}.`;
  if (e instanceof Anthropic.RateLimitError) return `${name}’s rate limit for your key was reached. Wait a minute and say "continue".`;
  if (e instanceof Anthropic.BadRequestError) return `${name} refused the request: ${e.message}`;
  if (e instanceof Anthropic.APIConnectionError) return `The panel could not reach ${name}. Say "continue" to try again.`;
  if (e instanceof Anthropic.APIError) {
    if (e.status === 402) return `Your ${name} account has run out of credit. Add some with ${name}, then say "continue".`;
    return `${name} answered with an error (${e.status ?? 'no status'}). Say "continue" to try again.`;
  }
  return null;
}
