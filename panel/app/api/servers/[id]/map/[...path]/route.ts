import type { NextRequest } from 'next/server';
import { roleOf, type UserRow } from '../../../../../../lib/server/access';
import { hub, type Json } from '../../../../../../lib/server/hub';
import { tileCache, tileKey } from '../../../../../../lib/server/mapTiles';
import { relayError } from '../../../../../../lib/server/plugin-proxy';
import { atLeast, type Role } from '../../../../../../lib/server/roles';
import { body, forward, HttpError, json, requireUser, route } from '../../../../../../lib/server/route';

// The map of a server's worlds (see plugin/.../map/WorldMap.java). The plugin draws tiles from its region files;
// this caches them by version (lib/server/mapTiles.ts) and lets builders mark named areas on it.
//   GET    map/index?world=w                      regions and their versions, spawn, players, markers, areas
//   GET    map/tile/<world>/<zoom>/<x>/<z>?v=..   one 512x512 PNG
//   POST   map/areas  {world, name, rect:[x1,z1,x2,z2], y?:[y1,y2], note?}
//   DELETE map/areas/<world>/<name>
type P = { id: string; path: string[] };

const INDEX_TIMEOUT_MS = 30_000;
const TILE_TIMEOUT_MS = 120_000;
const AREA_TIMEOUT_MS = 60_000;
const NO_MAP = 'This server\'s plugin is too old for the map. Update it with the jar from this panel.';
const IMMUTABLE = 'private, max-age=31536000, immutable';

// Roles by user and server for a few seconds: a map view asks for dozens of tiles at once.
const roles = new Map<string, { role: Role | null; until: number }>();

async function role(user: UserRow, serverId: string, needed: Role): Promise<void> {
  const key = `${user.id}|${serverId}`;
  let hit = roles.get(key);
  if (!hit || hit.until < Date.now()) {
    hit = { role: await roleOf(user.id, serverId), until: Date.now() + 10_000 };
    if (roles.size > 5000) roles.clear();
    roles.set(key, hit);
  }
  if (!hit.role) throw new HttpError(404, `unknown server '${serverId}'`);
  if (!atLeast(hit.role, needed)) throw new HttpError(403, `this needs the ${needed} role; yours is ${hit.role}`);
}

function requireMap(serverId: string) {
  const s = hub().snapshot(serverId);
  if (s.online && s.info.map !== true) throw new HttpError(501, NO_MAP);
}

function int(s: string | undefined, what: string): number {
  if (!s || !/^-?\d{1,9}$/.test(s)) throw new HttpError(400, `${what} must be a whole number`);
  return Number(s);
}

export const GET = route<P>(async (req, { id, path }) => {
  const user = await requireUser();
  await role(user, id, 'viewer');
  if (path.length === 1 && path[0] === 'index') return index(id, req.nextUrl.searchParams.get('world') || '');
  if (path.length === 5 && path[0] === 'tile') {
    const [, world, zoom, x, z] = path;
    return tile(req, id, world, int(zoom, 'zoom'), int(x, 'x'), int(z, 'z'), req.nextUrl.searchParams.get('v') || '');
  }
  throw new HttpError(404, 'not found');
});

async function index(serverId: string, world: string): Promise<Response> {
  const cache = tileCache();
  const cached = () => cache.index(`${serverId}|${world}`) ?? (world ? undefined : cache.index(`${serverId}|`));
  if (!hub().online(serverId)) {
    const last = cached();
    if (last) return json({ ...last, offline: true });
    return json({ error: 'The server is offline, and the panel has no map of it yet.', offline: true }, 503);
  }
  requireMap(serverId);
  try {
    const reply = await hub().request(serverId, { t: 'map', op: 'index', args: world ? { world } : {} }, INDEX_TIMEOUT_MS);
    if (reply.status !== 200) return json(reply.body, reply.status);
    const body = reply.body as Json;
    cache.setIndex(`${serverId}|${body.world}`, body);
    if (!world) cache.setIndex(`${serverId}|`, body);
    return json(body);
  } catch (e) {
    const last = cached();
    if (last) return json({ ...last, offline: true });
    const [status, b] = relayError(e);
    return json(b, status);
  }
}

async function tile(req: NextRequest, serverId: string, world: string, zoom: number, x: number, z: number, v: string): Promise<Response> {
  const cache = tileCache();
  const key = tileKey(serverId, world, zoom, x, z);
  const etag = `"${v}"`;
  const hit = cache.get(key);
  if (v && hit?.v === v) {
    if (req.headers.get('if-none-match') === etag) return new Response(null, { status: 304, headers: { ETag: etag, 'Cache-Control': IMMUTABLE } });
    return png(hit.png, { ETag: etag, 'Cache-Control': IMMUTABLE });
  }
  // Offline, or the plugin is busy: the last version we have beats nothing, but must not be kept by the browser.
  const stale = () => hit ? png(hit.png, { 'Cache-Control': 'no-store', 'X-Map-Stale': '1' }) : null;
  if (!hub().online(serverId)) return stale() ?? json({ error: 'the server is offline', offline: true }, 503);
  requireMap(serverId);
  let fresh: Uint8Array | null;
  try {
    fresh = await cache.once(`${key}|${v}`, async () => {
      const reply = await hub().request(serverId, { t: 'map', op: 'tile', args: { world, zoom, x, z } }, TILE_TIMEOUT_MS);
      const b = reply.body as Json | null;
      if (reply.status !== 200) throw new HttpError(reply.status, String(b?.error ?? 'the plugin could not draw this tile'));
      if (!b?.png) return null;
      const bytes = new Uint8Array(Buffer.from(String(b.png), 'base64'));
      cache.put(key, v || String(b.v ?? ''), bytes);
      return bytes;
    });
  } catch (e) {
    const s = stale();
    if (s) return s;
    if (e instanceof HttpError) throw e;
    const [status, b] = relayError(e);
    return json(b, status);
  }
  if (!fresh) return new Response(null, { status: 404, headers: { 'Cache-Control': 'no-store' } });
  return png(fresh, v ? { ETag: etag, 'Cache-Control': IMMUTABLE } : { 'Cache-Control': 'no-store' });
}

function png(bytes: Uint8Array, headers: Record<string, string>): Response {
  return new Response(bytes as Uint8Array<ArrayBuffer>, { headers: { 'Content-Type': 'image/png', ...headers } });
}

/** Marks an area: builders, since Claude builds in it. */
export const POST = route<P>(async (req, { id, path }) => {
  if (path.length !== 1 || path[0] !== 'areas') throw new HttpError(404, 'not found');
  const user = await requireUser();
  await role(user, id, 'builder');
  requireMap(id);
  const b = await body(req);
  const rect = b.rect;
  if (!Array.isArray(rect) || rect.length !== 4 || !rect.every(n => Number.isInteger(n))) throw new HttpError(400, 'rect must be [x1,z1,x2,z2]');
  const args: Json = { world: String(b.world || ''), name: String(b.name || ''), rect, note: String(b.note || '').slice(0, 300) };
  if (Array.isArray(b.y) && b.y.length === 2 && b.y.every((n: unknown) => Number.isInteger(n))) args.y = b.y;
  return forward(id, { t: 'map', op: 'area', args }, AREA_TIMEOUT_MS);
});

export const DELETE = route<P>(async (_req, { id, path }) => {
  if (path.length !== 3 || path[0] !== 'areas') throw new HttpError(404, 'not found');
  const user = await requireUser();
  await role(user, id, 'builder');
  requireMap(id);
  return forward(id, { t: 'map', op: 'unarea', args: { world: path[1], name: path[2] } }, AREA_TIMEOUT_MS);
});
