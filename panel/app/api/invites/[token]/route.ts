import { acceptInvite, previewInvite } from '../../../../lib/server/invites';
import { HttpError, json, requireUser, route } from '../../../../lib/server/route';

type P = { token: string };

export const GET = route<P>(async (_req, { token }) => {
  await requireUser();
  const preview = await previewInvite(token);
  if (!preview) throw new HttpError(404, 'This invite link is not valid.');
  return json(preview);
});

/** Joins the server the invite is for. */
export const POST = route<P>(async (_req, { token }) => {
  const user = await requireUser();
  const out = await acceptInvite(token, user.id);
  if ('error' in out) throw new HttpError(410, out.error);
  return json(out);
});
