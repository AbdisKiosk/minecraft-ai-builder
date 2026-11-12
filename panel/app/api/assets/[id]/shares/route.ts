import { answer, deps } from '../../../../../lib/server/assetStore';
import { listShares, shareAsset, unshareAsset } from '../../../../../lib/server/assets';
import { body, requireUser, route } from '../../../../../lib/server/route';

type P = { id: string };

/** The servers the owner shared the asset with: {shares: [{server, name, created}]}. 404 for anyone else. */
export const GET = route<P>(async (_req, { id }) => {
  const user = await requireUser();
  return answer(() => listShares(deps(user), user.id, id));
});

/**
 * Shares it with a server: {server}. Owner only, with the builder role there; builders there can then list, place and
 * paste it. Answers with the shares.
 */
export const POST = route<P>(async (req, { id }) => {
  const user = await requireUser();
  const b = await body(req);
  return answer(() => shareAsset(deps(user), user.id, id, b));
});

/** Stops sharing it with `?server=`. Owner only. Answers 204. */
export const DELETE = route<P>(async (req, { id }) => {
  const user = await requireUser();
  return answer(() => unshareAsset(deps(user), user.id, id, req.nextUrl.searchParams.get('server')));
});
