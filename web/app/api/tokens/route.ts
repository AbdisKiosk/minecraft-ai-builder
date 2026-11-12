import { exec, one, query } from '../../../lib/server/db';
import { body, HttpError, json, requireUser, route } from '../../../lib/server/route';
import { newToken, sha256, shortId } from '../../../lib/server/tokens';

const MAX_TOKENS = 20;

export const GET = route(async () => {
  const user = await requireUser();
  const rows = await query<{ id: string; label: string; created_at: Date; last_used_at: Date | null }>(
    'SELECT id, label, created_at, last_used_at FROM client_tokens WHERE user_id = ? ORDER BY created_at DESC', [user.id]);
  return json({
    tokens: rows.map(r => ({ id: r.id, label: r.label, created: r.created_at.getTime(), lastUsed: r.last_used_at?.getTime() ?? null })),
  });
});

/** Makes a client token for Claude Code and scripts; it is shown this once. */
export const POST = route(async req => {
  const user = await requireUser();
  const b = await body(req);
  const label = typeof b.label === 'string' ? b.label.trim() : '';
  if (!label || label.length > 64) throw new HttpError(400, 'give a label of 1 to 64 characters, e.g. "laptop"');
  const n = await one<{ n: number }>('SELECT COUNT(*) AS n FROM client_tokens WHERE user_id = ?', [user.id]);
  if ((n?.n ?? 0) >= MAX_TOKENS) throw new HttpError(409, `at most ${MAX_TOKENS} tokens; revoke some first`);
  const id = shortId(10);
  const token = newToken('aibc_');
  await exec('INSERT INTO client_tokens (id, user_id, label, token_hash) VALUES (?, ?, ?, ?)', [id, user.id, label, sha256(token)]);
  return json({ id, label, token }, 201);
});
