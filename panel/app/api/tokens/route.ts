import { accessTokensOf } from '../../../lib/server/accessTokens';
import { json, requireUser, route } from '../../../lib/server/route';

/** The caller's access tokens, one per server, without the tokens themselves (the server's own route shows one). */
export const GET = route(async () => {
  const user = await requireUser();
  return json({ tokens: await accessTokensOf(user.id) });
});
