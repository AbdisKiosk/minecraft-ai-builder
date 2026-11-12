import { accessToken, ensureAccessToken, revokeAccessToken } from '../../../../../lib/server/accessTokens';
import { body, json, requireRole, requireUser, route } from '../../../../../lib/server/route';

// The caller's own access token for this server (builders and up): Claude Code and scripts use it here and nowhere else.

function private_(res: Response): Response {
  res.headers.set('Cache-Control', 'no-store');
  return res;
}

/** The token, if the caller has one. */
export const GET = route<{ id: string }>(async (_req, { id }) => {
  const user = await requireUser();
  await requireRole(user, id, 'builder');
  return private_(json({ accessToken: await accessToken(user.id, id) }));
});

/** Makes the caller's token if they have none; {"renew": true} replaces it, and the old one stops working. */
export const POST = route<{ id: string }>(async (req, { id }) => {
  const user = await requireUser();
  await requireRole(user, id, 'builder');
  const b = await body(req);
  return private_(json({ accessToken: await ensureAccessToken(user.id, id, b.renew === true) }));
});

export const DELETE = route<{ id: string }>(async (_req, { id }) => {
  const user = await requireUser();
  await revokeAccessToken(user.id, id);
  return new Response(null, { status: 204 });
});
