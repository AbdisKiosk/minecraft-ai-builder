import { membersOf } from './access';
import { exec, one, transaction } from './db';
import { hub } from './hub';
import { atLeast, type Role } from './roles';
import { sha256 } from './tokens';

export interface InvitePreview {
  server: { id: string; name: string };
  role: Role;
  by: string | null;
  state: 'open' | 'used' | 'expired';
}

interface InviteRow { id: string; server_id: string; name: string; role: Role; by: string | null; used_at: Date | null; expires_at: Date }

const SELECT = `SELECT i.id, i.server_id, s.name, i.role, COALESCE(u.name, u.email) AS \`by\`, i.used_at, i.expires_at
                  FROM invites i JOIN servers s ON s.id = i.server_id JOIN users u ON u.id = i.created_by
                 WHERE i.token_hash = ?`;

function stateOf(r: InviteRow): InvitePreview['state'] {
  if (r.used_at) return 'used';
  return r.expires_at.getTime() <= Date.now() ? 'expired' : 'open';
}

export async function previewInvite(token: string): Promise<InvitePreview | null> {
  const r = await one<InviteRow>(SELECT, [sha256(token)]);
  return r && { server: { id: r.server_id, name: r.name }, role: r.role, by: r.by, state: stateOf(r) };
}

/**
 * Uses an invite: the user joins the server with its role, or keeps their role when it is already higher.
 * @returns the server id, or an error message
 */
export async function acceptInvite(token: string, userId: number): Promise<{ server: string } | { error: string }> {
  const out = await transaction<{ server: string } | { error: string }>(async c => {
    const r = await one<InviteRow>(SELECT + ' FOR UPDATE', [sha256(token)], c);
    if (!r) return { error: 'This invite link is not valid.' };
    const state = stateOf(r);
    if (state !== 'open') return { error: state === 'used' ? 'This invite has already been used.' : 'This invite has expired.' };
    const current = await one<{ role: Role }>('SELECT role FROM memberships WHERE server_id = ? AND user_id = ? FOR UPDATE',
      [r.server_id, userId], c);
    if (!current) {
      await exec('INSERT INTO memberships (server_id, user_id, role) VALUES (?, ?, ?)', [r.server_id, userId, r.role], c);
    } else if (!atLeast(current.role, r.role)) {
      await exec('UPDATE memberships SET role = ? WHERE server_id = ? AND user_id = ?', [r.role, r.server_id, userId], c);
    }
    await exec('UPDATE invites SET used_by = ?, used_at = NOW() WHERE id = ?', [userId, r.id], c);
    return { server: r.server_id };
  });
  if ('server' in out) hub().emit({ type: 'access', users: await membersOf(out.server) });
  return out;
}
