import { hub } from '../../../../../lib/server/hub';
import { baseUrl, json, requireRole, requireUser, route } from '../../../../../lib/server/route';
import { membersOf } from '../../../../../lib/server/access';
import { currentRelayToken, replaceRelayToken } from '../../../../../lib/server/relayToken';
import { relayConfig } from '../../../../../lib/server/views';

/** The server's relay settings, for setting the plugin up by hand (owner only). */
export const GET = route<{ id: string }>(async (req, { id }) => {
  const user = await requireUser();
  await requireRole(user, id, 'owner');
  const { token, replaced } = await currentRelayToken(id);
  if (replaced) hub().kick(id, 'relay token replaced');
  return json({ id, relay: relayConfig(baseUrl(req), token) });
});

/** Replaces the server's relay token (owner only): the old one stops working and the plugin is disconnected. */
export const POST = route<{ id: string }>(async (req, { id }) => {
  const user = await requireUser();
  await requireRole(user, id, 'owner');
  const token = await replaceRelayToken(id);
  hub().kick(id, 'relay token replaced');
  hub().emit({ type: 'access', users: await membersOf(id) });
  return json({ id, relay: relayConfig(baseUrl(req), token) });
});
