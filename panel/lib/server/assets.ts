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
/**
 * Sharing an asset with a server needs this role there, and so does using one shared with it: listing, placing and
 * pasting it. The share counts only while the owner still has it too (AssetStore#sharedWith).
 */
export const SHARE_ROLE: Role = 'builder';
/** Same as the plugin's saved blueprints (Tools#savedName), so Claude can paste an asset by name later. */
export const NAME = /^[a-z0-9_-]{1,40}$/;
export const MAX_DESCRIPTION = 1000;
/** Bounds both plugin downloads and new uploads, before decoding compressed region data. */
export const MAX_REGION_BYTES = 5 * 1024 * 1024;
export const MAX_REGION_VOLUME = 2_000_000;
export const MAX_TAGS = 10;
/** The direction an asset faces away from what it attaches to. Claude's paste turns only between the four horizontal ones. */
export const FACINGS = ['north', 'south', 'east', 'west', 'up', 'down'] as const;
export type Facing = typeof FACINGS[number];
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
  /**
   * The attachment cell, zero-based from the lowest corner, and the direction the asset faces away from what it attaches
   * to. Each is null when not set (every asset from before anchors, and uploads), which keeps lowest-corner placement.
   */
  anchor: [number, number, number] | null;
  facing: Facing | null;
}

export interface AssetRow extends Asset { owner: number }

/** An asset in the library list: the user's own, or one shared with a server they build on. */
export interface LibraryAsset extends Asset {
  /** Only the owner shares, renames, describes or deletes it; others' assets are read-only. */
  mine: boolean;
  /** The owner's name, on another user's asset. */
  owner?: string;
  /** Own asset: the servers it is shared with. Another's: the servers the user builds on that it is shared with. */
  shared: string[];
}

/** A server an asset is shared with, as its owner sees it. */
export interface Share { server: string; name: string; created: number }
export interface ShareRow extends Share { asset: string }
/** Another user's asset, shared with servers the caller builds on (`via`). */
export interface SharedRow extends AssetRow { ownerName: string; via: string[] }
/** Narrows AssetStore#sharedWith to one server, asset name or asset id. */
export interface SharedFilter { server?: string; name?: string; id?: string }
export interface Usage { count: number; bytes: number }
export type Changes = Partial<Pick<Asset, 'name' | 'description' | 'tags' | 'anchor' | 'facing'>>;

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
  /** Also removes the asset's shares. */
  remove(owner: number, id: string): Promise<void>;
  touch(owner: number, id: string): Promise<void>;
  /** Every share of the owner's assets, with the server's name. */
  shares(owner: number): Promise<ShareRow[]>;
  /** Shares an asset with a server; sharing it again changes nothing. */
  share(id: string, server: string, by: number): Promise<void>;
  unshare(id: string, server: string): Promise<void>;
  /**
   * Other users' assets shared with servers where both `user` and the asset's owner have SHARE_ROLE or above, most
   * recently used first, each with the owner's name and those servers (`via`).
   */
  sharedWith(user: number, filter?: SharedFilter): Promise<SharedRow[]>;
}

/** The asset files (R2). */
export interface AssetFiles {
  write(owner: number, id: string, name: FileName, bytes: Uint8Array): Promise<void>;
  read(owner: number, id: string, name: FileName, maxBytes?: number): Promise<Uint8Array | null>;
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
  /** Throws an AssetError (402) when the user's plan has no asset library; without it every user has one. */
  allowSave?(userId: number): Promise<void>;
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

/** An anchor inside an asset of `size`, or null for none (null, undefined or ""). */
export function checkAnchor(v: unknown, size: [number, number, number]): [number, number, number] | null {
  if (v === undefined || v === null || v === '') return null;
  const a = ints(v, 3);
  if (!a || a.some((n, i) => n < 0 || n >= size[i])) {
    throw new AssetError(400, `anchor must be [x,y,z] inside the asset, from 0 0 0 to ${size.map(n => n - 1).join(' ')}`);
  }
  return a as [number, number, number];
}

/** A facing, or null for none (null, undefined or ""). */
export function checkFacing(v: unknown): Facing | null {
  if (v === undefined || v === null || v === '') return null;
  if (typeof v !== 'string' || !(FACINGS as readonly string[]).includes(v)) throw new AssetError(400, `facing must be one of ${FACINGS.join(', ')}`);
  return v as Facing;
}

/** Throws unless the owner can add `count` assets of `bytes` bytes in all. */
export function checkQuota(usage: Usage, count: number, bytes: number): void {
  if (usage.count + count > MAX_ASSETS) {
    throw new AssetError(409, `your library is full: ${usage.count} of ${MAX_ASSETS} assets. Delete some to save more.`);
  }
  if (usage.bytes + bytes > MAX_BYTES) {
    throw new AssetError(409, `this asset needs ${mb(bytes)} MB and your library has ${mb(Math.max(0, MAX_BYTES - usage.bytes))} MB `
      + `of ${mb(MAX_BYTES)} MB left. Delete some assets, or save a smaller region.`);
  }
}

const mb = (n: number) => (n / 1024 ** 2).toFixed(1);

/** The 2,000,000-block cap of every asset, whether saved on the panel or by Claude; refused before reading the world. */
function checkVolume(volume: number): void {
  if (volume > MAX_REGION_VOLUME) {
    throw new AssetError(400, `this region is ${volume.toLocaleString('en-US')} blocks; an asset is at most `
      + `${MAX_REGION_VOLUME.toLocaleString('en-US')} blocks. Select a smaller region.`);
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
    const state = utf8.decode(raw.subarray(at, at + n));
    // As for .schem uploads (schem.ts MAX_STATE_LENGTH): no real block state is longer, and a paste names states back.
    if (state.length > 256) throw new AssetError(502, 'the region data has a block state longer than 256 characters');
    palette.push(state);
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
  if (at !== raw.byteLength) throw new AssetError(502, 'the region data has trailing bytes');
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
const sharedView = ({ ownerName, via, ...r }: SharedRow): LibraryAsset => ({ ...view(r), mine: false, owner: ownerName, shared: via });

/** The asset if `userId` owns it; otherwise 404, the same as for an asset that does not exist. */
export async function owned(deps: Deps, userId: number, id: unknown): Promise<AssetRow> {
  const row = typeof id === 'string' && ID.test(id) ? await deps.store.get(id) : null;
  if (!row || row.owner !== userId) throw new AssetError(404, `no asset '${String(id)}'`);
  return row;
}

/**
 * The asset if `userId` owns it, or if it is shared with a server where they and its owner build (with `server`: that
 * server); otherwise 404, the same as for an asset that does not exist. Only for reading and placing, never changes.
 */
export async function readable(deps: Deps, userId: number, id: unknown, server?: string): Promise<LibraryAsset & { row: AssetRow }> {
  if (typeof id !== 'string' || !ID.test(id)) throw new AssetError(404, `no asset '${String(id)}'`);
  const row = await deps.store.get(id);
  if (row && row.owner === userId) return { ...view(row), mine: true, shared: [], row };
  const shared = (await deps.store.sharedWith(userId, server === undefined ? { id } : { id, server }))
    .find(r => r.id === id && r.owner !== userId);
  if (!shared) throw new AssetError(404, `no asset '${id}'`);
  return { ...sharedView(shared), row: shared };
}

/** The user's own assets, then those shared with servers they build on, with the quota of their own. */
export async function listAssets(deps: Deps, userId: number) {
  const [rows, shares, others] = await Promise.all([deps.store.list(userId), deps.store.shares(userId), deps.store.sharedWith(userId)]);
  const own = rows.filter(r => r.owner === userId);
  const sharedTo = new Map<string, string[]>();
  for (const s of shares) sharedTo.set(s.asset, [...sharedTo.get(s.asset) ?? [], s.server]);
  const assets: LibraryAsset[] = [
    ...own.map(r => ({ ...view(r), mine: true, shared: sharedTo.get(r.id) ?? [] })),
    ...others.filter(r => r.owner !== userId).map(sharedView),
  ];
  return {
    assets,
    quota: { count: own.length, bytes: own.reduce((n, a) => n + a.bytes, 0), maxCount: MAX_ASSETS, maxBytes: MAX_BYTES },
  };
}

export async function getAsset(deps: Deps, userId: number, id: unknown): Promise<LibraryAsset> {
  const { row: _row, ...asset } = await readable(deps, userId, id);
  if (asset.mine) asset.shared = (await deps.store.shares(userId)).filter(s => s.asset === asset.id).map(s => s.server);
  return asset;
}

/** The 3D view's blocks of an asset the user owns or can use through a share, parsed. */
export async function assetBlocks(deps: Deps, userId: number, id: unknown): Promise<unknown> {
  const { row } = await readable(deps, userId, id);
  const gz = await deps.files.read(row.owner, row.id, 'blocks');
  if (!gz) throw new AssetError(410, 'the blocks of this asset are missing from storage');
  return JSON.parse(gunzipSync(gz).toString('utf8'));
}

/** Saves a region of a server's world: {server, world, box [x1,y1,z1,x2,y2,z2], name, description?, tags?, anchor?, facing?}. */
export async function createAsset(deps: Deps, userId: number, b: Json): Promise<Asset> {
  const server = checkServer(b.server);
  const world = checkWorld(b.world);
  const box = ints(b.box, 6);
  if (!box) throw new AssetError(400, 'give box as [x1,y1,z1,x2,y2,z2]');
  const name = checkName(b.name);
  const description = checkDescription(b.description);
  const tags = checkTags(b.tags);
  await deps.requireRole(server, PROMOTE_ROLE);
  await deps.allowSave?.(userId);
  // Refuse early what will be refused anyway, before the server reads the region.
  const size: [number, number, number] = [Math.abs(box[3] - box[0]) + 1, Math.abs(box[4] - box[1]) + 1, Math.abs(box[5] - box[2]) + 1];
  const anchor = checkAnchor(b.anchor, size), facing = checkFacing(b.facing);
  checkVolume(size[0] * size[1] * size[2]);
  checkQuota(await deps.store.usage(userId), 1, 0);
  if (await deps.store.byName(userId, name)) throw new AssetError(409, `you already have an asset named '${name}'`);

  const exported = await deps.relay(server, { t: 'draft', op: 'export', args: { world, box } });
  const body = exported.body as { data?: unknown; error?: unknown } | null;
  if (exported.status !== 200) throw new AssetError(exported.status, typeof body?.error === 'string' ? body.error : 'the server could not read the region');
  if (typeof body?.data !== 'string') throw new AssetError(502, 'the server sent no region data');
  const region = new Uint8Array(Buffer.from(body.data, 'base64'));
  return saveAssetData(deps, userId, { name, description, tags, size, anchor, facing }, region, await deps.mcVersion(server));
}

/** Shared by panel exports and authenticated relay saves; bytes and dimensions are never trusted. */
export async function saveAssetData(deps: Deps, userId: number, b: Json, region: Uint8Array, mc: string | null): Promise<Asset> {
  await deps.allowSave?.(userId);
  const name = checkName(b.name), description = checkDescription(b.description), tags = checkTags(b.tags);
  const dimensions = ints(b.size, 3);
  if (!dimensions || dimensions.some(n => n < 1)) throw new AssetError(400, 'give the asset dimensions as three positive whole numbers');
  const size = dimensions as [number, number, number];
  const anchor = checkAnchor(b.anchor, size), facing = checkFacing(b.facing);
  const volume = size[0] * size[1] * size[2];
  checkVolume(volume);
  if (!region.byteLength) throw new AssetError(400, 'the region data is empty');
  if (region.byteLength > MAX_REGION_BYTES) {
    throw new AssetError(400, `the region's block data is ${(region.byteLength / 1024 ** 2).toFixed(2)} MB compressed; an asset is `
      + `at most ${MAX_REGION_BYTES / 1024 ** 2} MB. Select a smaller region.`);
  }
  checkQuota(await deps.store.usage(userId), 1, 0);
  if (await deps.store.byName(userId, name)) throw new AssetError(409, `you already have an asset named '${name}'`);
  const states = decodeStates(region, volume);
  if (states.length !== volume) throw new AssetError(502, `the server sent ${states.length} blocks for a region of ${volume}`);
  const blocks = solidBlocks(states);
  if (blocks === 0) throw new AssetError(400, 'the region is only air');
  const viewer = gzipSync(JSON.stringify(viewerBlocks(size, states)));
  const bytes = region.byteLength + viewer.byteLength;
  checkQuota(await deps.store.usage(userId), 1, bytes);

  const now = Date.now();
  const row: AssetRow = {
    id: newId(), owner: userId, name, description, tags, size, blocks, mc, bytes, created: now, updated: now, anchor, facing,
  };
  // Files first, so a row never points at missing files; they go again if the row is refused.
  try {
    await deps.files.write(userId, row.id, 'region', region);
    await deps.files.write(userId, row.id, 'blocks', viewer);
    await deps.store.insert(row, usage => checkQuota(usage, 1, bytes));
  } catch (e) {
    await deps.files.removeAll(userId, row.id).catch(() => {});
    throw e;
  }
  return view(row);
}

/** Renames, describes, tags or anchors an asset: {name?, description?, tags?, anchor?, facing?}; null clears an anchor or facing. */
export async function updateAsset(deps: Deps, userId: number, id: unknown, b: Json): Promise<Asset> {
  const row = await owned(deps, userId, id);
  const changes: Changes = {};
  if (b.name !== undefined) changes.name = checkName(b.name);
  if (b.description !== undefined) changes.description = checkDescription(b.description);
  if (b.tags !== undefined) changes.tags = checkTags(b.tags);
  if (b.anchor !== undefined) changes.anchor = checkAnchor(b.anchor, row.size);
  if (b.facing !== undefined) changes.facing = checkFacing(b.facing);
  if (!Object.keys(changes).length) throw new AssetError(400, 'give name, description, tags, anchor or facing');
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

// ---- sharing ----

/** The servers the owner shared an asset with. */
export async function listShares(deps: Deps, userId: number, id: unknown): Promise<{ shares: Share[] }> {
  const row = await owned(deps, userId, id);
  return { shares: (await deps.store.shares(userId)).filter(s => s.asset === row.id).map(({ asset: _asset, ...s }) => s) };
}

/**
 * Shares an asset with a server: {server}. Only the owner, who needs SHARE_ROLE there, as do the members who use it.
 * It stays the owner's asset and counts against the owner's quota alone. Answers with the asset's shares.
 */
export async function shareAsset(deps: Deps, userId: number, id: unknown, b: Json): Promise<{ shares: Share[] }> {
  const row = await owned(deps, userId, id);
  const server = checkServer(b.server);
  await deps.requireRole(server, SHARE_ROLE);
  await deps.allowSave?.(userId);
  await deps.store.share(row.id, server, userId);
  return listShares(deps, userId, row.id);
}

/** Stops sharing an asset with a server; needs no role there, so an owner who left can still take it back. */
export async function unshareAsset(deps: Deps, userId: number, id: unknown, server: unknown): Promise<void> {
  const row = await owned(deps, userId, id);
  await deps.store.unshare(row.id, checkServer(server));
}

/**
 * Places an asset into a draft on a server: {server, world, origin [x,y,z], air?, skipUnknown?}. Answers with the
 * import frame's reply, which names the draft. Nothing is placed in the world until that draft is approved.
 */
export async function placeAsset(deps: Deps, userId: number, id: unknown, b: Json): Promise<Reply> {
  const server = checkServer(b.server);
  const world = checkWorld(b.world);
  const origin = ints(b.origin, 3);
  if (!origin) throw new AssetError(400, 'give origin as [x,y,z], the lowest corner');
  await deps.requireRole(server, PROMOTE_ROLE);
  // The user's own asset, or one shared with this very server.
  const { row, mine } = await readable(deps, userId, id, server);
  const region = await deps.files.read(row.owner, row.id, 'region');
  if (!region) throw new AssetError(410, 'the blocks of this asset are missing from storage');
  const reply = await deps.relay(server, {
    t: 'draft', op: 'import', args: {
      world, origin, size: row.size, data: Buffer.from(region).toString('base64'),
      air: b.air !== false, skipUnknown: b.skipUnknown === true, label: `asset ${row.name}`,
    },
  });
  // Only orders the owner's list Claude sees: the import already happened, so a failed touch must not fail it.
  if (mine && reply.status >= 200 && reply.status < 300) await deps.store.touch(userId, row.id).catch(() => {});
  return reply;
}
