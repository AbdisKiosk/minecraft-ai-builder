import { exec } from '../../../../../../lib/server/db';
import { HttpError, requireRole, requireUser, route } from '../../../../../../lib/server/route';

/** Revokes an open invite. */
export const DELETE = route<{ id: string; inviteId: string }>(async (_req, { id, inviteId }) => {
  const user = await requireUser();
  await requireRole(user, id, 'admin');
  const r = await exec('DELETE FROM invites WHERE id = ? AND server_id = ? AND used_at IS NULL', [inviteId, id]);
  if (!r.affectedRows) throw new HttpError(404, 'no such open invite');
  return new Response(null, { status: 204 });
});
