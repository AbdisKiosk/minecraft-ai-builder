import { PROVIDERS, providerName, type ProviderId } from '../chat';
import { exec, query } from './db';
import { keySecret, seal, unseal } from './secretBox';

// A user's own API keys for the panel's chat, at most one per provider. A key is never sent back to the browser: the
// panel shows only `hint`, its last four characters.

export interface ApiKeyView {
  hint: string;
  created: number;
  /** False when the key can't be read any more (KEY_SECRET changed): it must be entered again. */
  usable: boolean;
}

export type ApiKeyViews = Record<ProviderId, ApiKeyView | null>;

interface Row { provider: string; sealed: string; hint: string; created_at: Date }

const PATTERN: Record<ProviderId, RegExp> = {
  anthropic: /^sk-ant-[A-Za-z0-9_-]{20,300}$/,
  openrouter: /^sk-or-[A-Za-z0-9_-]{20,300}$/,
};

/** Why `key` cannot be a chat key for `provider`, or null when it looks like one. */
export function keyRefusal(provider: ProviderId, key: string): string | null {
  const other = PROVIDERS.find(p => p.id !== provider && key.startsWith(p.keyPrefix));
  // Both names start with a vowel: "an Anthropic key", "an OpenRouter key".
  if (other) return `that is an ${other.name} key; add it under ${other.name}`;
  if (provider === 'anthropic' && key.startsWith('sk-ant-admin')) {
    return 'that is an Admin API key, which cannot run Claude; make a normal API key in the Claude Console';
  }
  const prefix = PROVIDERS.find(p => p.id === provider)!.keyPrefix;
  if (!PATTERN[provider].test(key)) return `that does not look like an ${providerName(provider)} API key (they start with ${prefix})`;
  return null;
}

async function rows(userId: number): Promise<Row[]> {
  return query<Row>('SELECT provider, sealed, hint, created_at FROM api_keys WHERE user_id = ?', [userId]);
}

export async function apiKeyViews(userId: number): Promise<ApiKeyViews> {
  const views = Object.fromEntries(PROVIDERS.map(p => [p.id, null])) as ApiKeyViews;
  for (const r of await rows(userId)) {
    if (r.provider in views) {
      views[r.provider as ProviderId] = { hint: r.hint, created: r.created_at.getTime(), usable: unseal(r.sealed, keySecret()) !== null };
    }
  }
  return views;
}

/** The providers the user has a key for that can be read. */
export async function usableProviders(userId: number): Promise<ProviderId[]> {
  const views = await apiKeyViews(userId);
  return PROVIDERS.map(p => p.id).filter(id => views[id]?.usable);
}

/** The user's key itself, for a request to the provider; null when they have none that can be read. */
export async function apiKeyOf(userId: number, provider: ProviderId): Promise<string | null> {
  const r = (await rows(userId)).find(row => row.provider === provider);
  return r ? unseal(r.sealed, keySecret()) : null;
}

export async function saveApiKey(userId: number, provider: ProviderId, key: string): Promise<void> {
  await exec(
    `INSERT INTO api_keys (user_id, provider, sealed, hint) VALUES (?, ?, ?, ?)
     ON DUPLICATE KEY UPDATE sealed = VALUES(sealed), hint = VALUES(hint), created_at = NOW()`,
    [userId, provider, seal(key, keySecret()), key.slice(-4)]);
}

export async function deleteApiKey(userId: number, provider: ProviderId): Promise<boolean> {
  return (await exec('DELETE FROM api_keys WHERE user_id = ? AND provider = ?', [userId, provider])).affectedRows > 0;
}
