// Only server.ts imports this: Next's bundler would trace the whole project for the directory read.
import { createReadStream } from 'node:fs';
import { readdir, stat } from 'node:fs/promises';
import type { IncomingMessage, ServerResponse } from 'node:http';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';

// The plugin's jar as a download, so a server owner gets the build that speaks this panel's relay protocol.
// The Docker image carries it in plugin-jar/; in a checkout it is the Gradle build's output.

const NAME = /^AIBuilder-[0-9A-Za-z._+-]+\.jar$/;

/** The directories searched for the jar: PLUGIN_JAR_DIR when set, else the image's and then a checkout's. */
export function jarDirs(cwd = process.cwd(), env: Record<string, string | undefined> = process.env): string[] {
  if (env.PLUGIN_JAR_DIR) return [path.resolve(cwd, env.PLUGIN_JAR_DIR)];
  return [path.join(cwd, 'plugin-jar'), path.join(cwd, '..', 'plugin', 'core', 'build', 'libs')];
}

export interface PluginJar { file: string; name: string; size: number; modified: number }

/** The newest AIBuilder-<version>.jar in the first directory that has one, or null. */
export async function findPluginJar(dirs = jarDirs()): Promise<PluginJar | null> {
  for (const dir of dirs) {
    let names: string[];
    try {
      names = await readdir(dir);
    } catch {
      continue; // no such directory
    }
    let best: PluginJar | null = null;
    for (const name of names) {
      if (!NAME.test(name)) continue;
      const file = path.join(dir, name);
      const s = await stat(file);
      if (s.isFile() && (!best || s.mtimeMs > best.modified)) best = { file, name, size: s.size, modified: s.mtimeMs };
    }
    if (best) return best;
  }
  return null;
}

/** GET or HEAD of the jar. Anyone may download it: it holds no secrets and is useless without a relay token. */
export async function handlePluginJar(req: IncomingMessage, res: ServerResponse): Promise<void> {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405, { Allow: 'GET, HEAD' }).end();
    return;
  }
  const jar = await findPluginJar();
  if (!jar) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('This panel was started without the plugin jar. Build it with ./gradlew build in plugin/, '
      + 'or build the panel image from the repository root (see docs/panel.md).\n');
    return;
  }
  const etag = `"${jar.size.toString(16)}-${Math.floor(jar.modified).toString(16)}"`;
  // Revalidated on every request, so a new deploy's jar is never hidden by a cached old one.
  const headers = { ETag: etag, 'Cache-Control': 'no-cache' };
  if (req.headers['if-none-match'] === etag) {
    res.writeHead(304, headers).end();
    return;
  }
  res.writeHead(200, {
    ...headers,
    'Content-Type': 'application/java-archive',
    'Content-Length': jar.size,
    'Content-Disposition': `attachment; filename="${jar.name}"`,
  });
  if (req.method === 'HEAD') {
    res.end();
    return;
  }
  await pipeline(createReadStream(jar.file), res);
}
