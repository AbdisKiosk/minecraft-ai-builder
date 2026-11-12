import { exec } from '../../../../lib/server/db';
import { HttpError, requireUser, route } from '../../../../lib/server/route';

export const DELETE = route<{ id: string }>(async (_req, { id }) => {
  const user = await requireUser();
  const r = await exec('DELETE FROM client_tokens WHERE id = ? AND user_id = ?', [id, user.id]);
  if (!r.affectedRows) throw new HttpError(404, 'no such token');
  return new Response(null, { status: 204 });
});
