import { readFile } from 'node:fs/promises';
import { addJarEntry, SETUP_ENTRY, setupProperties } from '../../../../../lib/server/jarSetup';
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
  const { token, replaced } = await currentRelayToken(id);
  if (replaced) hub().kick(id, 'relay token replaced');
  const relay = relayConfig(baseUrl(req), token);
  const bytes = addJarEntry(await readFile(jar.file), SETUP_ENTRY, Buffer.from(setupProperties(relay.url, relay.token)));
  return new Response(new Uint8Array(bytes), {
    headers: {
      'Content-Type': 'application/java-archive',
      'Content-Length': String(bytes.length),
      // Always the same name, so a new download replaces the old file instead of sitting next to it.
      'Content-Disposition': 'attachment; filename="AIBuilder.jar"',
      'Cache-Control': 'no-store',
    },
  });
});
