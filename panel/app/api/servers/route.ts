import { exec, one, transaction } from '../../../lib/server/db';
import { hub } from '../../../lib/server/hub';
import { baseUrl, body, HttpError, json, requireUser, route } from '../../../lib/server/route';
import { newToken, sha256, shortId } from '../../../lib/server/tokens';
import { relayConfig, serverViews } from '../../../lib/server/views';

const MAX_OWNED = 10;

export const GET = route(async () => {
  const user = await requireUser();
  return json({ servers: await serverViews(user.id) });
});

/** Makes a server owned by the caller; answers with its relay token, shown this once. */
export const POST = route(async req => {
  const user = await requireUser();
  const b = await body(req);
  const name = typeof b.name === 'string' ? b.name.trim() : '';
  if (!name || name.length > 64) throw new HttpError(400, 'give a name of 1 to 64 characters');
  const owned = await one<{ n: number }>('SELECT COUNT(*) AS n FROM servers WHERE owner_id = ?', [user.id]);
  if ((owned?.n ?? 0) >= MAX_OWNED) throw new HttpError(409, `you can own at most ${MAX_OWNED} servers`);

  const id = shortId(10);
  const token = newToken('aibs_');
  await transaction(async c => {
    await exec('INSERT INTO servers (id, name, owner_id, token_hash) VALUES (?, ?, ?, ?)', [id, name, user.id, sha256(token)], c);
    await exec("INSERT INTO memberships (server_id, user_id, role) VALUES (?, ?, 'owner')", [id, user.id], c);
  });
  hub().emit({ type: 'access', users: [user.id] });
  return json({ id, name, relay: relayConfig(baseUrl(req), token) }, 201);
});
