import { answer, deps } from '../../../../lib/server/assetStore';
import { deleteAsset, getAsset, updateAsset } from '../../../../lib/server/assets';
import { body, requireUser, route } from '../../../../lib/server/route';

type P = { id: string };

/** One of the user's assets; 404 for anyone else's. */
export const GET = route<P>(async (_req, { id }) => {
  const user = await requireUser();
  return answer(() => getAsset(deps(user), user.id, id));
});

/** Renames, describes or tags it: {name?, description?, tags?}. Answers with the asset. */
export const PATCH = route<P>(async (req, { id }) => {
  const user = await requireUser();
  const b = await body(req);
  return answer(() => updateAsset(deps(user), user.id, id, b));
});

/** Deletes it and its files. Answers 204. */
export const DELETE = route<P>(async (_req, { id }) => {
  const user = await requireUser();
  return answer(() => deleteAsset(deps(user), user.id, id));
});
