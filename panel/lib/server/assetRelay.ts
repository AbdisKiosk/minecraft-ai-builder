import { createHash, randomBytes } from 'node:crypto';
import { AssetError, checkName, checkQuota, fileKey, MAX_REGION_BYTES, saveAssetData, type AssetRow, type Deps } from './assets.ts';
import type { Json } from './hub.ts';

/** One instance per socket. User ids supplied by a plugin are never authorization. */
export class AssetGrants {
  private grants = new Map<string, { user: number; expires: number }>();
  private clock: () => number;
  constructor(clock = Date.now) { this.clock = clock; }

  issue(user: number, timeout: number): string {
    if (!Number.isSafeInteger(user) || user < 1) throw new AssetError(403, 'invalid asset caller');
    for (const [key, grant] of this.grants) if (grant.expires <= this.clock()) this.grants.delete(key);
    if (this.grants.size >= 128) throw new AssetError(429, 'too many concurrent asset requests');
    const key = randomBytes(32).toString('hex');
    this.grants.set(key, { user, expires: this.clock() + Math.min(timeout, 6 * 60_000) });
    return key;
  }

  owner(key: unknown): number {
    const grant = typeof key === 'string' ? this.grants.get(key) : undefined;
    if (!grant || grant.expires <= this.clock()) throw new AssetError(403, 'asset access needs an active authenticated tool request');
    return grant.user;
  }

  revoke(key: string) { this.grants.delete(key); }
  clear() { this.grants.clear(); }
}

/** An asset's anchor and facing for the plugin, each left out when not set; older plugins ignore both. */
const placement = (r: AssetRow) => ({ ...(r.anchor ? { anchor: r.anchor } : {}), ...(r.facing ? { facing: r.facing } : {}) });

/** The most assets get_context lists. */
const LISTED = 30;

/** An owner's name as Claude sees it: short, without an email's domain, and nothing JSON has to escape. */
const ownerTag = (name: string) => name.replace(/@.*/, '').replace(/[^\p{L}\p{N} ._-]+/gu, ' ').replace(/\s+/g, ' ').trim().slice(0, 16) || '?';
/**
 * A listed description is cut to 80 characters; a shared one to 56, so its `,"by":"<16>"` (24 characters) keeps every
 * entry within the size ToolsTest#largestAssetListingStaysSmall caps.
 */
const DESCRIPTION = 80;
const SHARED_DESCRIPTION = DESCRIPTION - 24;

/**
 * What `paste name=` means for this caller on this server: their own asset wins; otherwise the one asset of that name
 * shared with this server. Two or more shared ones are an error naming their owners, never a silent pick.
 */
async function resolve(deps: Deps, user: number, server: string, name: string): Promise<{ row: AssetRow; mine: boolean }> {
  const own = await deps.store.byName(user, name);
  if (own && own.owner === user) return { row: own, mine: true };
  const shared = (await deps.store.sharedWith(user, { server, name }))
    .filter(r => r.owner !== user && r.name === name && r.via.includes(server));
  if (!shared.length) throw new AssetError(404, `no asset '${name}' in your library or shared with this server`);
  if (shared.length > 1) {
    throw new AssetError(409, `'${name}' is shared with this server by ${shared.map(r => ownerTag(r.ownerName)).join(', ')}; `
      + 'ask one of them to rename theirs');
  }
  return { row: shared[0], mine: false };
}

/** Rules separated from MySQL, R2 and sockets so ownership and quota failures are tested without services. */
export async function assetRequest(deps: Deps, user: number, server: string, mc: string | null, frame: Json,
  sign: (key: string) => Promise<string>): Promise<Json> {
  // Re-check membership on every nested request: a revoked builder must not keep using an outstanding grant.
  await deps.requireRole(server, 'builder');
  if (frame.op === 'list') {
    const own = (await deps.store.list(user)).filter(r => r.owner === user).slice(0, LISTED);
    const names = new Set(own.map(r => r.name));
    // Shared assets come after the caller's own; one hidden by an own asset of the same name cannot be pasted by name.
    const shared = own.length >= LISTED ? []
      : (await deps.store.sharedWith(user, { server })).filter(r => r.owner !== user && r.via.includes(server) && !names.has(r.name));
    const brief = (r: AssetRow, cut = DESCRIPTION) => ({ name: r.name, size: r.size, description: r.description.replace(/\s+/g, ' ').slice(0, cut) });
    return { assets: [
      ...own.map(r => brief(r)),
      ...shared.slice(0, LISTED - own.length).map(r => ({ ...brief(r, SHARED_DESCRIPTION), by: ownerTag(r.ownerName) })),
    ] };
  }
  const args = frame.args ?? {};
  const name = checkName(args.name);
  if (frame.op === 'get') {
    // Builder here is checked above; a shared asset is read and signed under its owner's files.
    const { row, mine } = await resolve(deps, user, server, name);
    let bytes: Uint8Array | null;
    try {
      bytes = await deps.files.read(row.owner, row.id, 'region', MAX_REGION_BYTES);
    } catch (e) {
      if (e instanceof RangeError) throw new AssetError(400, 'this asset is too large for Claude to paste (5 MB limit)');
      throw e;
    }
    if (!bytes) throw new AssetError(410, 'the blocks of this asset are missing from storage');
    if (bytes.byteLength > MAX_REGION_BYTES) throw new AssetError(400, 'this asset is too large for Claude to paste (5 MB limit)');
    const url = await sign(fileKey(row.owner, row.id, 'region'));
    // Only orders the owner's list Claude sees: a failed touch must not fail the paste.
    if (mine) await deps.store.touch(user, row.id).catch(() => {});
    return { id: row.id, size: row.size, bytes: bytes.byteLength, sha256: createHash('sha256').update(bytes).digest('hex'), url, ...placement(row) };
  }
  if (frame.op === 'check') {
    await deps.allowSave?.(user);
    checkQuota(await deps.store.usage(user), 1, 0);
    if (await deps.store.byName(user, name)) throw new AssetError(409, `you already have an asset named '${name}'`);
    return {};
  }
  if (frame.op === 'save') {
    if (typeof args.data !== 'string') throw new AssetError(400, 'give valid base64 region data');
    if (args.data.length > Math.ceil(MAX_REGION_BYTES / 3) * 4)
      throw new AssetError(400, 'the region data is larger than 5 MB compressed; save a smaller region');
    const bytes = Buffer.from(args.data, 'base64');
    if (bytes.toString('base64') !== args.data) throw new AssetError(400, 'give valid base64 region data');
    const row = await saveAssetData(deps, user, args, bytes, mc);
    return { saved: row.name, size: row.size, blocks: row.blocks, library: true };
  }
  throw new AssetError(400, `unknown asset operation '${String(frame.op)}'`);
}
