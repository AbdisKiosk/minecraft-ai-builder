import { exec, one } from './db';
import { keySecret, seal, unseal } from './secretBox';

// A user's own Anthropic API key, which the panel's chat spends. It is never sent back to the browser: the panel
// shows only `hint`, its last four characters.

export interface ApiKeyView {
  hint: string;
  created: number;
  /** False when the key can't be read any more (KEY_SECRET changed): it must be entered again. */
  usable: boolean;
}

interface Row { sealed: string; hint: string; created_at: Date }

/** Why `key` cannot be an Anthropic API key for the chat, or null when it looks like one. */
export function keyRefusal(key: string): string | null {
  if (!/^sk-ant-[A-Za-z0-9_-]{20,300}$/.test(key)) return 'that does not look like an Anthropic API key (they start with sk-ant-)';
  if (key.startsWith('sk-ant-admin')) return 'that is an Admin API key, which cannot run Claude; make a normal API key in the Claude Console';
  return null;
}

async function row(userId: number): Promise<Row | null> {
  return one<Row>('SELECT sealed, hint, created_at FROM api_keys WHERE user_id = ?', [userId]);
}

export async function apiKeyView(userId: number): Promise<ApiKeyView | null> {
  const r = await row(userId);
  return r ? { hint: r.hint, created: r.created_at.getTime(), usable: unseal(r.sealed, keySecret()) !== null } : null;
}

/** The user's key itself, for a request to Anthropic; null when they have none that can be read. */
export async function apiKeyOf(userId: number): Promise<string | null> {
  const r = await row(userId);
  return r ? unseal(r.sealed, keySecret()) : null;
}

export async function saveApiKey(userId: number, key: string): Promise<void> {
  await exec(
    `INSERT INTO api_keys (user_id, sealed, hint) VALUES (?, ?, ?)
     ON DUPLICATE KEY UPDATE sealed = VALUES(sealed), hint = VALUES(hint), created_at = NOW()`,
    [userId, seal(key, keySecret()), key.slice(-4)]);
}

export async function deleteApiKey(userId: number): Promise<boolean> {
  return (await exec('DELETE FROM api_keys WHERE user_id = ?', [userId])).affectedRows > 0;
}
