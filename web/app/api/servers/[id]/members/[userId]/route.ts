import { membersOf, roleOf } from '../../../../../../lib/server/access';
import { exec } from '../../../../../../lib/server/db';
import { hub } from '../../../../../../lib/server/hub';
import { body, HttpError, json, requireRole, requireUser, route } from '../../../../../../lib/server/route';
import { canManage, grantable, isRole } from '../../../../../../lib/server/roles';

type P = { id: string; userId: string };

const article = (role: string) => (/^[aeiou]/.test(role) ? 'an ' : 'a ') + role;

/** Changes a member's role: only for members ranked below the caller, and only to roles the caller may give. */
export const PATCH = route<P>(async (req, { id, userId }) => {
  const user = await requireUser();
  const mine = await requireRole(user, id, 'admin');
  const target = Number(userId);
  const theirs = await roleOf(target, id);
  if (!theirs) throw new HttpError(404, 'not a member');
  if (target === user.id || !canManage(mine, theirs)) throw new HttpError(403, `${article(mine)} cannot change ${article(theirs)}`);
  const { role } = await body(req);
  if (!isRole(role) || !grantable(mine).includes(role)) throw new HttpError(400, `give one of: ${grantable(mine).join(', ')}`);
  await exec('UPDATE memberships SET role = ? WHERE server_id = ? AND user_id = ?', [role, id, target]);
  hub().emit({ type: 'access', users: [target] });
  return json({ id: target, role });
});

/** Removes a member. Anyone but the owner may also remove themselves (leave). */
export const DELETE = route<P>(async (_req, { id, userId }) => {
  const user = await requireUser();
  const mine = await requireRole(user, id, 'viewer');
  const target = Number(userId);
  const theirs = await roleOf(target, id);
  if (!theirs) throw new HttpError(404, 'not a member');
  if (target === user.id) {
    if (mine === 'owner') throw new HttpError(400, 'the owner cannot leave; delete the server instead');
  } else if (!canManage(mine, theirs)) {
    throw new HttpError(403, `${article(mine)} cannot remove ${article(theirs)}`);
  }
  await exec('DELETE FROM memberships WHERE server_id = ? AND user_id = ?', [id, target]);
  hub().emit({ type: 'access', users: [target, ...(await membersOf(id))] });
  return new Response(null, { status: 204 });
});
