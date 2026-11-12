import { clientJar, UnknownVersion } from '../../../../../lib/server/clientJars';
import { HttpError, requireUser, route } from '../../../../../lib/server/route';

/** Block models and textures for the 3D view. A version's jar never changes, so browsers may keep it. */
export const GET = route<{ version: string }>(async (_req, { version }) => {
  await requireUser();
  try {
    const jar = await clientJar(version);
    return new Response(jar.body, {
      headers: {
        'Content-Type': 'application/java-archive',
        'Content-Length': String(jar.size),
        'Cache-Control': 'private, max-age=31536000, immutable',
      },
    });
  } catch (e) {
    if (e instanceof UnknownVersion) throw new HttpError(404, e.message);
    console.warn('could not get the Minecraft client jar', e);
    throw new HttpError(502, 'could not download the Minecraft client from Mojang: ' + (e instanceof Error ? e.message : String(e)));
  }
});
