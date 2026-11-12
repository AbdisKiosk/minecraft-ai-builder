import { answer, deps } from '../../../lib/server/assetStore';
import { createAsset, listAssets } from '../../../lib/server/assets';
import { body, requireUser, route } from '../../../lib/server/route';

/** The signed-in user's assets, newest first, with their quota: {assets, quota: {count, bytes, maxCount, maxBytes}}. */
export const GET = route(async () => {
  const user = await requireUser();
  return answer(() => listAssets(deps(user), user.id));
});

/**
 * Saves a region of a server's world as an asset: {server, world, box [x1,y1,z1,x2,y2,z2], name, description?, tags?}.
 * Needs the role promote needs on that server. Answers 201 with the asset.
 */
export const POST = route(async req => {
  const user = await requireUser();
  const b = await body(req);
  return answer(() => createAsset(deps(user), user.id, b), 201);
});
