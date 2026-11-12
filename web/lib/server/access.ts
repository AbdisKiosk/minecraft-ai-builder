import { exec, one, query } from './db';
import type { Role } from './roles';
import { sha256 } from './tokens';

export interface UserRow { id: number; sub: string; email: string | null; name: string | null; picture: string | null }
export interface ServerRow { id: string; name: string; owner_id: number; created_at: Date; last_seen_at: Date | null; info: unknown }

/** The user's role on a server, or null when they are not a member. */
export async function roleOf(userId: number, serverId: string): Promise<Role | null> {
  const r = await one<{ role: Role }>('SELECT role FROM memberships WHERE server_id = ? AND user_id = ?', [serverId, userId]);
  return r?.role ?? null;
}

/** Every server the user is a member of, with their role. */
export async function serversOf(userId: number): Promise<(ServerRow & { role: Role })[]> {
  return query(
    `SELECT s.id, s.name, s.owner_id, s.created_at, s.last_seen_at, s.info, m.role
       FROM memberships m JOIN servers s ON s.id = m.server_id
      WHERE m.user_id = ? ORDER BY s.name, s.id`, [userId]);
}

/** The ids of the users who can see a server. */
export async function membersOf(serverId: string): Promise<number[]> {
  return (await query<{ user_id: number }>('SELECT user_id FROM memberships WHERE server_id = ?', [serverId])).map(r => r.user_id);
}

/** The server a plugin's relay token belongs to. */
export async function serverForRelayToken(token: string): Promise<string | null> {
  const r = await one<{ id: string }>('SELECT id FROM servers WHERE token_hash = ?', [sha256(token)]);
  return r?.id ?? null;
}

/** The user a client token belongs to; records its use at most once a minute. */
export async function userForClientToken(token: string): Promise<number | null> {
  const r = await one<{ id: string; user_id: number; recent: number }>(
    `SELECT id, user_id, (last_used_at IS NOT NULL AND last_used_at > NOW() - INTERVAL 1 MINUTE) AS recent
       FROM client_tokens WHERE token_hash = ?`, [sha256(token)]);
  if (!r) return null;
  if (!r.recent) exec('UPDATE client_tokens SET last_used_at = NOW() WHERE id = ?', [r.id]).catch(() => {});
  return r.user_id;
}

export async function serverExists(id: string): Promise<boolean> {
  return !!(await one('SELECT 1 FROM servers WHERE id = ?', [id]));
}
