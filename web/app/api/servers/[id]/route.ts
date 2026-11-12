import { membersOf } from '../../../../lib/server/access';
import { exec } from '../../../../lib/server/db';
import { hub } from '../../../../lib/server/hub';
import { body, HttpError, json, requireRole, requireUser, route } from '../../../../lib/server/route';

type P = { id: string };

/** Renames a server (admin and above). */
export const PATCH = route<P>(async (req, { id }) => {
  const user = await requireUser();
  await requireRole(user, id, 'admin');
  const b = await body(req);
  const name = typeof b.name === 'string' ? b.name.trim() : '';
  if (!name || name.length > 64) throw new HttpError(400, 'give a name of 1 to 64 characters');
  await exec('UPDATE servers SET name = ? WHERE id = ?', [name, id]);
  hub().emit({ type: 'access', users: await membersOf(id) });
  return json({ id, name });
});

/** Deletes a server and everyone's access to it (owner only). Its plugin is disconnected. */
export const DELETE = route<P>(async (_req, { id }) => {
  const user = await requireUser();
  await requireRole(user, id, 'owner');
  const members = await membersOf(id);
  await exec('DELETE FROM servers WHERE id = ?', [id]);
  hub().kick(id, 'server deleted from the panel');
  hub().emit({ type: 'access', users: members });
  return new Response(null, { status: 204 });
});
