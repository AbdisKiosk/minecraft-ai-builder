import { exec } from '../../../../../lib/server/db';
import { hub } from '../../../../../lib/server/hub';
import { baseUrl, json, requireRole, requireUser, route } from '../../../../../lib/server/route';
import { newToken, sha256 } from '../../../../../lib/server/tokens';
import { membersOf } from '../../../../../lib/server/access';
import { relayConfig } from '../../../../../lib/server/views';

/** Replaces the server's relay token (owner only): the old one stops working and the plugin is disconnected. */
export const POST = route<{ id: string }>(async (req, { id }) => {
  const user = await requireUser();
  await requireRole(user, id, 'owner');
  const token = newToken('aibs_');
  await exec('UPDATE servers SET token_hash = ? WHERE id = ?', [sha256(token), id]);
  hub().kick(id, 'relay token replaced');
  hub().emit({ type: 'access', users: await membersOf(id) });
  return json({ id, relay: relayConfig(baseUrl(req), token) });
});
