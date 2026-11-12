import { linkAnswer, linkDeps } from '../../../../../lib/server/minecraftLinkStore';
import { unlinkByUser } from '../../../../../lib/server/minecraftLinks';
import { requireUser, route } from '../../../../../lib/server/route';

/** Unlinks one of the signed-in user's Minecraft accounts. Answers 204. */
export const DELETE = route<{ uuid: string }>(async (_req, { uuid }) => {
  const user = await requireUser();
  return linkAnswer(() => unlinkByUser(linkDeps(), user.id, uuid));
});
