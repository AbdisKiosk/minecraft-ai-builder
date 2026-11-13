import { linkAnswer, linkDeps } from '../../../../lib/server/minecraftLinkStore';
import { issueCode, listLinks } from '../../../../lib/server/minecraftLinks';
import { requireUser, route } from '../../../../lib/server/route';

/** The Minecraft accounts linked to the signed-in user: {links: [{uuid, name, linked}], max}. */
export const GET = route(async () => {
  const user = await requireUser();
  return linkAnswer(() => listLinks(linkDeps(), user.id));
});

/**
 * Makes a one-time code for `/aibuilder link <code>` in game: {code, expires}. It lasts ten minutes, and the user's
 * earlier unused codes stop working. The code is shown once; only its hash is kept.
 */
export const POST = route(async () => {
  const user = await requireUser();
  return linkAnswer(() => issueCode(linkDeps(), user.id), 201);
});
