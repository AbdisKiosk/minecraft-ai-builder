import { randomBytes } from 'node:crypto';
import { gunzipSync, gzipSync } from 'node:zlib';
import type { Json, Reply } from './hub';
import type { Role } from './roles';

// A user's asset library: regions saved from a server's world, owned by the account and placed into a draft on any
// server the user builds on. The rules live here, with MySQL, R2 and the relay passed in (assetStore.ts wires the real
// ones), so the tests can run them without any of the three.

/** Per user. */
export const MAX_ASSETS = 200;
export const MAX_BYTES = 256 * 1024 ** 2;
/** Saving from a server and placing into one need what promote needs there (app/api/promote/route.ts). */
export const PROMOTE_ROLE: Role = 'builder';
/** Same as the plugin's saved blueprints (Tools#savedName), so Claude can paste an asset by name later. */
export const NAME = /^[a-z0-9_-]{1,40}$/;
export const MAX_DESCRIPTION = 1000;
export const MAX_TAGS = 10;
const TAG = /^[a-z0-9][a-z0-9 _-]{0,31}$/;
const ID = /^[a-z0-9]{12}$/;
const WORLD = /^[^\0/\\]{1,64}$/;

/** The R2 files of an asset: the export frame's data (for the import frame) and the 3D view's blocks. */
export const FILES = { region: 'region.bin', blocks: 'blocks.json.gz' } as const;
export type FileName = keyof typeof FILES;
export const fileKey = (owner: number, id: string, name: FileName) => `${assetPrefix(owner, id)}${FILES[name]}`;
export const assetPrefix = (owner: number, id: string) => `users/${owner}/assets/${id}/`;

export class AssetError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

/** What the API sends for an asset. */
export interface Asset {
  id: string;
  name: string;
  description: string;
  tags: string[];
  /** Width (x), height (y) and length (z) in blocks. */
  size: [number, number, number];
  /** Blocks that are not air. */
  blocks: number;
  /** The Minecraft version of the server it was saved on. */
  mc: string | null;
  bytes: number;
  created: number;
  updated: number;
}

export interface AssetRow extends Asset { owner: number }
export interface Usage { count: number; bytes: number }
export type Changes = Partial<Pick<Asset, 'name' | 'description' | 'tags'>>;

/** The asset rows (MySQL). Only the service decides who may see what; the store also scopes writes to the owner. */
export interface AssetStore {
  list(owner: number): Promise<AssetRow[]>;
  get(id: string): Promise<AssetRow | null>;
  byName(owner: number, name: string): Promise<AssetRow | null>;
  usage(owner: number): Promise<Usage>;
  /**
   * Adds the row while no other insert for the same owner runs; `check` sees the owner's usage first and throws to
   * refuse. A name the owner already uses is refused with a 409 AssetError.
   */
  insert(row: AssetRow, check: (usage: Usage) => void): Promise<void>;
  /** A taken name is refused with a 409 AssetError. */
  update(owner: number, id: string, changes: Changes): Promise<void>;
  remove(owner: number, id: string): Promise<void>;
}

/** The asset files (R2). */
export interface AssetFiles {
  write(owner: number, id: string, name: FileName, bytes: Uint8Array): Promise<void>;
  read(owner: number, id: string, name: FileName): Promise<Uint8Array | null>;
  removeAll(owner: number, id: string): Promise<void>;
}

export interface Deps {
  store: AssetStore;
  files: AssetFiles;
  /** Throws (404 or 403) unless the signed-in user has at least `role` on the server. */
  requireRole(serverId: string, role: Role): Promise<unknown>;
  /** Sends a request frame to a server's plugin; rejects when it is offline or does not answer. */
  relay(serverId: string, frame: Json): Promise<Reply>;
  /** The Minecraft version a server reports, or null. */
  mcVersion(serverId: string): Promise<string | null>;
}

// ---- checks ----

export function checkName(v: unknown): string {
  if (typeof v !== 'string' || !NAME.test(v)) throw new AssetError(400, 'name must be 1-40 of a-z, 0-9, _ and -');
  return v;
}

export function checkDescription(v: unknown): string {
  if (v === undefined || v === null) return '';
  if (typeof v !== 'string') throw new AssetError(400, 'description must be text');
  const d = v.trim();
  if (d.length > MAX_DESCRIPTION) throw new AssetError(400, `description is longer than ${MAX_DESCRIPTION} characters`);
  return d;
}

/** Tags as given (an array of strings), trimmed, lowercased and without repeats. */
export function checkTags(v: unknown): string[] {
  if (v === undefined || v === null) return [];
  if (!Array.isArray(v) || !v.every(t => typeof t === 'string')) throw new AssetError(400, 'tags must be a list of strings');
  const tags = [...new Set((v as string[]).map(t => t.trim().toLowerCase()).filter(Boolean))];
  if (tags.length > MAX_TAGS) throw new AssetError(400, `at most ${MAX_TAGS} tags`);
  const bad = tags.find(t => !TAG.test(t));
  if (bad !== undefined) throw new AssetError(400, `tag '${bad}' must be 1-32 of a-z, 0-9, space, _ and -, starting with a letter or digit`);
  return tags;
}

/** Throws unless the owner can add `count` assets of `bytes` bytes in all. */
export function checkQuota(usage: Usage, count: number, bytes: number): void {
  if (usage.count + count > MAX_ASSETS) {
    throw new AssetError(409, `your library is full: ${usage.count} of ${MAX_ASSETS} assets. Delete some to save more.`);
  }
  if (usage.bytes + bytes > MAX_BYTES) {
    const mb = (n: number) => (n / 1024 ** 2).toFixed(1);
    throw new AssetError(409, `this asset needs ${mb(bytes)} MB and your library has ${mb(Math.max(0, MAX_BYTES - usage.bytes))} MB `
      + `of ${mb(MAX_BYTES)} MB left. Delete some assets, or save a smaller region.`);
  }
}

const ints = (v: unknown, n: number): number[] | null =>
  Array.isArray(v) && v.length === n && v.every(Number.isSafeInteger) ? (v as number[]) : null;

function checkWorld(v: unknown): string {
  if (typeof v !== 'string' || !WORLD.test(v)) throw new AssetError(400, 'give the world by name');
  return v;
}

function checkServer(v: unknown): string {
  if (typeof v !== 'string' || !v) throw new AssetError(400, 'give the server id');
  return v;
}

// ---- the export frame's data ----

const MAGIC = 0x53413031; // "SA01", StateArrays.java

/** A region's states as StateArrays.java writes them: a palette and [palette index, count] runs in y, z, x order. */
export interface StateRuns { length: number; palette: string[]; runs: [number, number][] }

/** Reads the gzipped bytes the draft export frame sends (base64-decoded); refuses more than `maxLength` states. */
export function decodeStates(gz: Uint8Array, maxLength: number): StateRuns {
  let raw: Buffer;
  try {
    // Header, palette and at most one 8-byte run per state; a palette of 64k states is far more than any region has.
    raw = gunzipSync(gz, { maxOutputLength: 12 + maxLength * 8 + 4 * 1024 ** 2 });
  } catch {
    throw new AssetError(502, 'the server sent region data that is not gzip, or too much of it');
  }
  const view = new DataView(raw.buffer, raw.byteOffset, raw.byteLength);
  let at = 0;
  const int = () => {
    if (at + 4 > view.byteLength) throw new AssetError(502, 'the region data ends early');
    const v = view.getInt32(at);
    at += 4;
    return v;
  };
  if (int() !== MAGIC) throw new AssetError(502, 'the server sent something that is not region data');
  const length = int();
  if (length < 0 || length > maxLength) throw new AssetError(502, `the region data has ${length} blocks, expected ${maxLength}`);
  const paletteSize = int();
  if (paletteSize < 0 || paletteSize > 65536) throw new AssetError(502, 'the region data has a bad palette');
  const utf8 = new TextDecoder();
  const palette: string[] = [];
  for (let i = 0; i < paletteSize; i++) {
    if (at + 2 > view.byteLength) throw new AssetError(502, 'the region data ends early');
    const n = view.getUint16(at);
    at += 2;
    if (at + n > view.byteLength) throw new AssetError(502, 'the region data ends early');
    // Java's modified UTF-8 is plain UTF-8 for block states (no NUL, no characters outside the BMP).
    palette.push(utf8.decode(raw.subarray(at, at + n)));
    at += n;
  }
  const runs: [number, number][] = [];
  let total = 0;
  while (total < length) {
    const p = int(), run = int();
    if (p < 0 || p >= palette.length || run <= 0 || total + run > length) throw new AssetError(502, 'the region data has a bad run');
    const last = runs[runs.length - 1];
    if (last && last[0] === p) last[1] += run;
    else runs.push([p, run]);
    total += run;
  }
  return { length, palette, runs };
}

/** Blocks that are not air. */
export function solidBlocks(s: StateRuns): number {
  const air = new Set(['air', 'cave_air', 'void_air']);
  return s.runs.reduce((n, [p, run]) => n + (air.has(s.palette[p]) ? 0 : run), 0);
}

/**
 * What the 3D view reads (lib/schematic.ts BlockData), in the asset's own coordinates (0 0 0 is its lowest corner).
 * There are no changes, so before and after are the same. `models` is left out: the export frame sends states without
 * their default properties, which only the server knows; the browser fills them in from the client jar.
 */
export function viewerBlocks(size: [number, number, number], s: StateRuns) {
  return { box: [0, 0, 0, size[0] - 1, size[1] - 1, size[2] - 1], palette: s.palette, before: s.runs, after: s.runs, changes: [] };
}

// ---- the service ----

export const newId = () => {
  const alphabet = 'abcdefghijkmnpqrstuvwxyz23456789';
  return [...randomBytes(12)].map(b => alphabet[b % alphabet.length]).join('');
};

const view = ({ owner: _owner, ...a }: AssetRow): Asset => a;

/** The asset if `userId` owns it; otherwise 404, the same as for an asset that does not exist. */
export async function owned(deps: Deps, userId: number, id: unknown): Promise<AssetRow> {
  const row = typeof id === 'string' && ID.test(id) ? await deps.store.get(id) : null;
  if (!row || row.owner !== userId) throw new AssetError(404, `no asset '${String(id)}'`);
  return row;
}

export async function listAssets(deps: Deps, userId: number) {
  const rows = await deps.store.list(userId);
  const assets = rows.filter(r => r.owner === userId).map(view);
  return {
    assets,
    quota: { count: assets.length, bytes: assets.reduce((n, a) => n + a.bytes, 0), maxCount: MAX_ASSETS, maxBytes: MAX_BYTES },
  };
}

export async function getAsset(deps: Deps, userId: number, id: unknown): Promise<Asset> {
  return view(await owned(deps, userId, id));
}

/** The 3D view's blocks of an asset, parsed. */
export async function assetBlocks(deps: Deps, userId: number, id: unknown): Promise<unknown> {
  const row = await owned(deps, userId, id);
  const gz = await deps.files.read(row.owner, row.id, 'blocks');
  if (!gz) throw new AssetError(410, 'the blocks of this asset are missing from storage');
  return JSON.parse(gunzipSync(gz).toString('utf8'));
}

/** Saves a region of a server's world: {server, world, box [x1,y1,z1,x2,y2,z2], name, description?, tags?}. */
export async function createAsset(deps: Deps, userId: number, b: Json): Promise<Asset> {
  const server = checkServer(b.server);
  const world = checkWorld(b.world);
  const box = ints(b.box, 6);
  if (!box) throw new AssetError(400, 'give box as [x1,y1,z1,x2,y2,z2]');
  const name = checkName(b.name);
  const description = checkDescription(b.description);
  const tags = checkTags(b.tags);
  await deps.requireRole(server, PROMOTE_ROLE);
  // Refuse early what will be refused anyway, before the server reads the region.
  checkQuota(await deps.store.usage(userId), 1, 0);
  if (await deps.store.byName(userId, name)) throw new AssetError(409, `you already have an asset named '${name}'`);

  const size: [number, number, number] = [Math.abs(box[3] - box[0]) + 1, Math.abs(box[4] - box[1]) + 1, Math.abs(box[5] - box[2]) + 1];
  const volume = size[0] * size[1] * size[2];
  const exported = await deps.relay(server, { t: 'draft', op: 'export', args: { world, box } });
  const body = exported.body as { data?: unknown; error?: unknown } | null;
  if (exported.status !== 200) throw new AssetError(exported.status, typeof body?.error === 'string' ? body.error : 'the server could not read the region');
  if (typeof body?.data !== 'string') throw new AssetError(502, 'the server sent no region data');
  const region = new Uint8Array(Buffer.from(body.data, 'base64'));
  const states = decodeStates(region, volume);
  if (states.length !== volume) throw new AssetError(502, `the server sent ${states.length} blocks for a region of ${volume}`);
  const blocks = solidBlocks(states);
  if (blocks === 0) throw new AssetError(400, 'the region is only air');
  const viewer = gzipSync(JSON.stringify(viewerBlocks(size, states)));
  const bytes = region.byteLength + viewer.byteLength;
  checkQuota(await deps.store.usage(userId), 1, bytes);

  const now = Date.now();
  const row: AssetRow = {
    id: newId(), owner: userId, name, description, tags, size, blocks, mc: await deps.mcVersion(server), bytes, created: now, updated: now,
  };
  // Files first, so a row never points at missing files; they go again if the row is refused.
  await deps.files.write(userId, row.id, 'region', region);
  await deps.files.write(userId, row.id, 'blocks', viewer);
  try {
    await deps.store.insert(row, usage => checkQuota(usage, 1, bytes));
  } catch (e) {
    await deps.files.removeAll(userId, row.id).catch(() => {});
    throw e;
  }
  return view(row);
}

/** Renames, describes or tags an asset: {name?, description?, tags?}. */
export async function updateAsset(deps: Deps, userId: number, id: unknown, b: Json): Promise<Asset> {
  const row = await owned(deps, userId, id);
  const changes: Changes = {};
  if (b.name !== undefined) changes.name = checkName(b.name);
  if (b.description !== undefined) changes.description = checkDescription(b.description);
  if (b.tags !== undefined) changes.tags = checkTags(b.tags);
  if (!Object.keys(changes).length) throw new AssetError(400, 'give name, description or tags');
  if (changes.name && changes.name !== row.name && await deps.store.byName(userId, changes.name)) {
    throw new AssetError(409, `you already have an asset named '${changes.name}'`);
  }
  await deps.store.update(userId, row.id, changes);
  return view({ ...row, ...changes, updated: Date.now() });
}

export async function deleteAsset(deps: Deps, userId: number, id: unknown): Promise<void> {
  const row = await owned(deps, userId, id);
  await deps.store.remove(userId, row.id);
  await deps.files.removeAll(userId, row.id);
}

/**
 * Places an asset into a draft on a server: {server, world, origin [x,y,z], air?, skipUnknown?}. Answers with the
 * import frame's reply, which names the draft. Nothing is placed in the world until that draft is approved.
 */
export async function placeAsset(deps: Deps, userId: number, id: unknown, b: Json): Promise<Reply> {
  const row = await owned(deps, userId, id);
  const server = checkServer(b.server);
  const world = checkWorld(b.world);
  const origin = ints(b.origin, 3);
  if (!origin) throw new AssetError(400, 'give origin as [x,y,z], the lowest corner');
  await deps.requireRole(server, PROMOTE_ROLE);
  const region = await deps.files.read(row.owner, row.id, 'region');
  if (!region) throw new AssetError(410, 'the blocks of this asset are missing from storage');
  return deps.relay(server, {
    t: 'draft', op: 'import', args: {
      world, origin, size: row.size, data: Buffer.from(region).toString('base64'),
      air: b.air !== false, skipUnknown: b.skipUnknown === true, label: `asset ${row.name}`,
    },
  });
}
