import { memberRefusal } from '../../../../../lib/plans';
import { PAYMENT_REQUIRED, serverLimits } from '../../../../../lib/server/billing';
import { exec, query } from '../../../../../lib/server/db';
import { baseUrl, body, HttpError, json, requireRole, requireUser, route } from '../../../../../lib/server/route';
import { grantable, isRole, type Role } from '../../../../../lib/server/roles';
import { newToken, sha256, shortId } from '../../../../../lib/server/tokens';

type P = { id: string };
const DAYS = 7;
const MAX_OPEN = 50;

/** The server's invites that are still open (not used, not expired). Their links are only shown when made. */
export const GET = route<P>(async (_req, { id }) => {
  const user = await requireUser();
  await requireRole(user, id, 'admin');
  const rows = await query<{ id: string; role: Role; expires_at: Date; created_at: Date; by: string | null }>(
    `SELECT i.id, i.role, i.expires_at, i.created_at, COALESCE(u.name, u.email) AS \`by\`
       FROM invites i JOIN users u ON u.id = i.created_by
      WHERE i.server_id = ? AND i.used_by IS NULL AND i.used_at IS NULL AND i.expires_at > NOW()
      ORDER BY i.created_at DESC`, [id]);
  return json({
    invites: rows.map(r => ({ id: r.id, role: r.role, by: r.by, created: r.created_at.getTime(), expires: r.expires_at.getTime() })),
  });
});

/** Makes a single-use invite link for a role the caller may give; it lasts seven days. */
export const POST = route<P>(async (req, { id }) => {
  const user = await requireUser();
  const mine = await requireRole(user, id, 'admin');
  const { role } = await body(req);
  if (!isRole(role) || !grantable(mine).includes(role)) throw new HttpError(400, `give one of: ${grantable(mine).join(', ')}`);
  const members = await query<{ n: number }>('SELECT COUNT(*) AS n FROM memberships WHERE server_id = ?', [id]);
  const refused = memberRefusal(await serverLimits(id), Number(members[0]?.n ?? 0));
  if (refused) throw new HttpError(PAYMENT_REQUIRED, refused);
  const open = await query<{ n: number }>(
    'SELECT COUNT(*) AS n FROM invites WHERE server_id = ? AND used_at IS NULL AND expires_at > NOW()', [id]);
  if ((open[0]?.n ?? 0) >= MAX_OPEN) throw new HttpError(409, `at most ${MAX_OPEN} open invites; revoke some first`);
  const inviteId = shortId(10);
  const token = newToken('aibi_');
  const expires = new Date(Date.now() + DAYS * 86_400_000);
  await exec('INSERT INTO invites (id, server_id, token_hash, role, created_by, expires_at) VALUES (?, ?, ?, ?, ?, ?)',
    [inviteId, id, sha256(token), role, user.id, expires]);
  return json({ id: inviteId, role, expires: expires.getTime(), url: `${baseUrl(req)}/invite/${token}` }, 201);
});
