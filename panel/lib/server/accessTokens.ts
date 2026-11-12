import { exec, one, query } from './db';
import { relaySecret } from './relayToken';
import { deriveAccessToken, newSeed, sha256 } from './tokens';

// A user's access token for one server (aibc_): what Claude Code and build scripts send to /s/<server>/. Each user has
// at most one per server, and the panel can show it again because it is derived from a stored seed.

export interface AccessToken {
  /** Null when it can't be rebuilt (the secret changed); making it again gives a new one. */
  token: string | null;
  created: number;
  lastUsed: number | null;
}

interface Row { seed: string; token_hash: string; created_at: Date; last_used_at: Date | null }

function view(row: Row): AccessToken {
  const token = deriveAccessToken(row.seed, relaySecret());
  return {
    token: sha256(token) === row.token_hash ? token : null,
    created: row.created_at.getTime(),
    lastUsed: row.last_used_at?.getTime() ?? null,
  };
}

async function row(userId: number, serverId: string): Promise<Row | null> {
  return one<Row>('SELECT seed, token_hash, created_at, last_used_at FROM access_tokens WHERE user_id = ? AND server_id = ?',
    [userId, serverId]);
}

/** The user's token for the server, or null when they have none. */
export async function accessToken(userId: number, serverId: string): Promise<AccessToken | null> {
  const r = await row(userId, serverId);
  return r ? view(r) : null;
}

/** The user's token for the server, made when they have none; `renew` replaces it, so the old one stops working. */
export async function ensureAccessToken(userId: number, serverId: string, renew = false): Promise<AccessToken> {
  const old = await row(userId, serverId);
  if (old && !renew) {
    const v = view(old);
    if (v.token) return v;
  }
  const seed = newSeed();
  const hash = sha256(deriveAccessToken(seed, relaySecret()));
  if (old) {
    await exec('UPDATE access_tokens SET seed = ?, token_hash = ?, created_at = NOW(), last_used_at = NULL WHERE user_id = ? AND server_id = ?',
      [seed, hash, userId, serverId]);
  } else {
    await exec('INSERT INTO access_tokens (user_id, server_id, seed, token_hash) VALUES (?, ?, ?, ?)', [userId, serverId, seed, hash]);
  }
  return view((await row(userId, serverId))!);
}

export async function revokeAccessToken(userId: number, serverId: string): Promise<boolean> {
  return (await exec('DELETE FROM access_tokens WHERE user_id = ? AND server_id = ?', [userId, serverId])).affectedRows > 0;
}

/** Who a token belongs to and which server it opens; records its use at most once a minute. */
export async function accessForToken(token: string): Promise<{ userId: number; serverId: string } | null> {
  const r = await one<{ user_id: number; server_id: string; recent: number }>(
    `SELECT user_id, server_id, (last_used_at IS NOT NULL AND last_used_at > NOW() - INTERVAL 1 MINUTE) AS recent
       FROM access_tokens WHERE token_hash = ?`, [sha256(token)]);
  if (!r) return null;
  if (!r.recent) {
    exec('UPDATE access_tokens SET last_used_at = NOW() WHERE user_id = ? AND server_id = ?', [r.user_id, r.server_id]).catch(() => {});
  }
  return { userId: r.user_id, serverId: r.server_id };
}

/** The user's tokens, one per server, without the tokens themselves. */
export async function accessTokensOf(userId: number) {
  const rows = await query<{ server_id: string; name: string; created_at: Date; last_used_at: Date | null }>(
    `SELECT t.server_id, s.name, t.created_at, t.last_used_at
       FROM access_tokens t JOIN servers s ON s.id = t.server_id
      WHERE t.user_id = ? ORDER BY s.name, s.id`, [userId]);
  return rows.map(r => ({ server: r.server_id, name: r.name, created: r.created_at.getTime(), lastUsed: r.last_used_at?.getTime() ?? null }));
}
