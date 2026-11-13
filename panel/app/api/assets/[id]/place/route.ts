import { answer, deps } from '../../../../../lib/server/assetStore';
import { placeAsset } from '../../../../../lib/server/assets';
import { body, json, requireUser, route } from '../../../../../lib/server/route';

/**
 * Places the asset into a draft on a server: {server, world, origin [x,y,z], air?, skipUnknown?}. Needs the role promote
 * needs on that server, and the asset must be the user's or shared with that server.
 * Answers with the plugin's import reply ({job, status, draft?, error?, skipped}) and its status.
 */
export const POST = route<{ id: string }>(async (req, { id }) => {
  const user = await requireUser();
  const b = await body(req);
  return answer(async () => {
    const reply = await placeAsset(deps(user), user.id, id, b);
    return json(reply.body, reply.status);
  });
});
