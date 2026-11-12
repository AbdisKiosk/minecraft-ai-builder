import { createHash } from 'node:crypto';
import { read, sizeOf, write } from './r2';

// Minecraft client jars for the 3D view, which takes block models and textures from them. Each version is
// downloaded from Mojang once, checked against Mojang's SHA-1 and kept in R2 (a container's disk does not last).

const MANIFEST = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json';
const VERSION = /^[0-9A-Za-z][0-9A-Za-z._+-]{0,63}$/;
const LEGACY = /^1\.(\d+)(\..*)?$/;
/** The oldest client with flattened block names, which servers before 1.13 report too (see LegacyBlockTable). */
export const FIRST_FLAT = '1.13.2';

export class UnknownVersion extends Error {}

/** The client version to use for a server's reported version. */
export function clientVersion(reported: string): string {
  const legacy = LEGACY.exec(reported);
  if (legacy && Number(legacy[1]) < 13) return FIRST_FLAT;
  // Paper's builds report e.g. 26.3.build.248.
  return reported.replace(/\.build\..*$/, '');
}

const downloads = new Map<string, Promise<void>>();

/** The jar for a server's reported version, as a stream from R2, downloading it from Mojang on first use. */
export async function clientJar(reported: string): Promise<{ body: ReadableStream<Uint8Array>; size: number; version: string }> {
  if (!VERSION.test(reported)) throw new UnknownVersion('not a Minecraft version: ' + reported);
  const version = clientVersion(reported);
  const key = `minecraft/client/${version}.jar`;
  if ((await sizeOf(key)) === null) {
    let p = downloads.get(version);
    if (!p) {
      p = download(version, key).finally(() => downloads.delete(version));
      downloads.set(version, p);
    }
    await p;
  }
  const jar = await read(key);
  if (!jar) throw new Error(`client ${version} vanished from storage`);
  return { ...jar, version };
}

async function json(url: string): Promise<any> {
  const r = await fetch(url, { signal: AbortSignal.timeout(30_000) });
  if (!r.ok) throw new Error(`${url} answered ${r.status}`);
  return r.json();
}

async function download(version: string, key: string): Promise<void> {
  const manifest = await json(MANIFEST);
  const entry = (manifest.versions as { id: string; url: string }[]).find(v => v.id === version);
  if (!entry) throw new UnknownVersion('Mojang lists no Minecraft ' + version);
  const client = (await json(entry.url)).downloads?.client as { url: string; sha1: string } | undefined;
  if (!client) throw new UnknownVersion(`Mojang has no client download for ${version}`);
  const r = await fetch(client.url, { signal: AbortSignal.timeout(180_000) });
  if (!r.ok) throw new Error(`${client.url} answered ${r.status}`);
  const bytes = new Uint8Array(await r.arrayBuffer());
  const got = createHash('sha1').update(bytes).digest('hex');
  if (got.toLowerCase() !== client.sha1.toLowerCase()) throw new Error(`client ${version} has SHA-1 ${got}, Mojang says ${client.sha1}`);
  await write(key, bytes, 'application/java-archive');
}
