import { readFile } from 'node:fs/promises';
import { one } from '../../../../../lib/server/db';
import { addJarEntry, downloadName, SETUP_ENTRY, setupProperties } from '../../../../../lib/server/jarSetup';
import { findJar } from '../../../../../lib/server/jarSource';
import { hub } from '../../../../../lib/server/hub';
import { currentRelayToken } from '../../../../../lib/server/relayToken';
import { baseUrl, HttpError, requireRole, requireUser, route } from '../../../../../lib/server/route';
import { relayConfig } from '../../../../../lib/server/views';

/**
 * The plugin jar with this server's relay settings inside (owner only), so installing it is the whole setup.
 * Downloading again gives the same settings; a POST, because it can make the server's first relay token.
 */
export const POST = route<{ id: string }>(async (req, { id }) => {
  const user = await requireUser();
  await requireRole(user, id, 'owner');
  const jar = await findJar();
  if (!jar) throw new HttpError(503, 'this panel has no plugin jar to hand out; build it with ./gradlew build in plugin/');
  const server = await one<{ name: string }>('SELECT name FROM servers WHERE id = ?', [id]);
  const { token, replaced } = await currentRelayToken(id);
  if (replaced) hub().kick(id, 'relay token replaced');
  const relay = relayConfig(baseUrl(req), token);
  const bytes = addJarEntry(await readFile(jar.file), SETUP_ENTRY, Buffer.from(setupProperties(relay.url, relay.token)));
  return new Response(new Uint8Array(bytes), {
    headers: {
      'Content-Type': 'application/java-archive',
      'Content-Length': String(bytes.length),
      // Which server it is for and which commit it was built from; replace the old jar in plugins/ with it.
      'Content-Disposition': `attachment; filename="${downloadName(server?.name ?? id, jar.name)}"`,
      'Cache-Control': 'no-store',
    },
  });
});
