import { createHash, randomBytes } from 'node:crypto';
import { AssetError, checkName, checkQuota, fileKey, MAX_REGION_BYTES, saveAssetData, type Deps } from './assets.ts';
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

/** Rules separated from MySQL, R2 and sockets so ownership and quota failures are tested without services. */
export async function assetRequest(deps: Deps, user: number, server: string, mc: string | null, frame: Json,
  sign: (key: string) => Promise<string>): Promise<Json> {
  // Re-check membership on every nested request: a revoked builder must not keep using an outstanding grant.
  await deps.requireRole(server, 'builder');
  if (frame.op === 'list') {
    const rows = await deps.store.list(user);
    return { assets: rows.filter(r => r.owner === user).slice(0, 30).map(r => ({
      name: r.name, size: r.size, description: r.description.replace(/\s+/g, ' ').slice(0, 80),
    })) };
  }
  const args = frame.args ?? {};
  const name = checkName(args.name);
  if (frame.op === 'get') {
    const row = await deps.store.byName(user, name);
    if (!row || row.owner !== user) throw new AssetError(404, `no asset '${name}' in your library`);
    let bytes: Uint8Array | null;
    try {
      bytes = await deps.files.read(user, row.id, 'region', MAX_REGION_BYTES);
    } catch (e) {
      if (e instanceof RangeError) throw new AssetError(400, 'this asset is too large for Claude to paste (5 MB limit)');
      throw e;
    }
    if (!bytes) throw new AssetError(410, 'the blocks of this asset are missing from storage');
    if (bytes.byteLength > MAX_REGION_BYTES) throw new AssetError(400, 'this asset is too large for Claude to paste (5 MB limit)');
    const url = await sign(fileKey(user, row.id, 'region'));
    // Only orders the list Claude sees: a failed touch must not fail the paste.
    await deps.store.touch(user, row.id).catch(() => {});
    return { id: row.id, size: row.size, bytes: bytes.byteLength, sha256: createHash('sha256').update(bytes).digest('hex'), url };
  }
  if (frame.op === 'check') {
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
