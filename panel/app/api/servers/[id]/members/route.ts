import { query } from '../../../../../lib/server/db';
import { json, requireRole, requireUser, route } from '../../../../../lib/server/route';
import { canManage, grantable, type Role } from '../../../../../lib/server/roles';

/** Who has access to the server, and what the caller may change. */
export const GET = route<{ id: string }>(async (_req, { id }) => {
  const user = await requireUser();
  const mine = await requireRole(user, id, 'viewer');
  const rows = await query<{ id: number; name: string | null; email: string | null; picture: string | null; role: Role; created_at: Date }>(
    `SELECT u.id, u.name, u.email, u.picture, m.role, m.created_at
       FROM memberships m JOIN users u ON u.id = m.user_id
      WHERE m.server_id = ?
      ORDER BY FIELD(m.role, 'owner', 'admin', 'builder', 'viewer'), u.name`, [id]);
  return json({
    role: mine,
    grantable: grantable(mine),
    members: rows.map(r => ({
      id: r.id,
      name: r.name,
      email: r.email,
      picture: r.picture,
      role: r.role,
      since: r.created_at.getTime(),
      you: r.id === user.id,
      manageable: r.id !== user.id && canManage(mine, r.role),
    })),
  });
});
