import type { UserRow } from './access';
import { AssetError, assetPrefix, fileKey, type AssetFiles, type AssetRow, type AssetStore, type Changes, type Deps } from './assets';
import { exec, one, query, transaction } from './db';
import { hub } from './hub';
import { relayError } from './plugin-proxy';
import { readBytes, removePrefix, write } from './r2';
import { HttpError, json, requireRole } from './route';

// The asset library's MySQL rows, R2 files and relay, for the rules in assets.ts.

/** Exporting or importing a large region can take minutes, as for promote. */
const RELAY_TIMEOUT_MS = 6 * 60_000;

interface Db {
  id: string; user_id: number; name: string; description: string; tags: unknown;
  size_x: number; size_y: number; size_z: number; block_count: number; mc_version: string | null; bytes: number | string;
  created_at: Date; updated_at: Date;
}

const COLUMNS = 'id, user_id, name, description, tags, size_x, size_y, size_z, block_count, mc_version, bytes, created_at, updated_at';

function row(r: Db): AssetRow {
  const tags = typeof r.tags === 'string' ? JSON.parse(r.tags) : r.tags;
  return {
    id: r.id, owner: r.user_id, name: r.name, description: r.description, tags: Array.isArray(tags) ? tags : [],
    size: [r.size_x, r.size_y, r.size_z], blocks: r.block_count, mc: r.mc_version, bytes: Number(r.bytes),
    created: r.created_at.getTime(), updated: r.updated_at.getTime(),
  };
}

const taken = (e: unknown, name: string) => (e as { code?: string })?.code === 'ER_DUP_ENTRY'
  ? new AssetError(409, `you already have an asset named '${name}'`) : e;

export const store: AssetStore = {
  async list(owner) {
    return (await query<Db>(`SELECT ${COLUMNS} FROM assets WHERE user_id = ? ORDER BY updated_at DESC, id`, [owner])).map(row);
  },
  async get(id) {
    const r = await one<Db>(`SELECT ${COLUMNS} FROM assets WHERE id = ?`, [id]);
    return r && row(r);
  },
  async byName(owner, name) {
    const r = await one<Db>(`SELECT ${COLUMNS} FROM assets WHERE user_id = ? AND name = ?`, [owner, name]);
    return r && row(r);
  },
  async usage(owner) {
    const r = await one<{ n: number; bytes: number | string | null }>('SELECT COUNT(*) AS n, SUM(bytes) AS bytes FROM assets WHERE user_id = ?', [owner]);
    return { count: Number(r?.n ?? 0), bytes: Number(r?.bytes ?? 0) };
  },
  async insert(a, check) {
    await transaction(async c => {
      // The user's row serialises one user's inserts, so two saves at once cannot both fit in the last of the quota.
      await query('SELECT id FROM users WHERE id = ? FOR UPDATE', [a.owner], c);
      const r = await one<{ n: number; bytes: number | string | null }>(
        'SELECT COUNT(*) AS n, SUM(bytes) AS bytes FROM assets WHERE user_id = ?', [a.owner], c);
      check({ count: Number(r?.n ?? 0), bytes: Number(r?.bytes ?? 0) });
      try {
        await exec(
          `INSERT INTO assets (id, user_id, name, description, tags, size_x, size_y, size_z, block_count, mc_version, bytes)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
          [a.id, a.owner, a.name, a.description, JSON.stringify(a.tags), a.size[0], a.size[1], a.size[2], a.blocks, a.mc, a.bytes], c);
      } catch (e) {
        throw taken(e, a.name);
      }
    });
  },
  async update(owner, id, changes: Changes) {
    const sets: string[] = [], params: (string | number)[] = [];
    if (changes.name !== undefined) { sets.push('name = ?'); params.push(changes.name); }
    if (changes.description !== undefined) { sets.push('description = ?'); params.push(changes.description); }
    if (changes.tags !== undefined) { sets.push('tags = ?'); params.push(JSON.stringify(changes.tags)); }
    if (!sets.length) return;
    try {
      await exec(`UPDATE assets SET ${sets.join(', ')} WHERE id = ? AND user_id = ?`, [...params, id, owner]);
    } catch (e) {
      throw taken(e, changes.name ?? '');
    }
  },
  async remove(owner, id) {
    await exec('DELETE FROM assets WHERE id = ? AND user_id = ?', [id, owner]);
  },
};

export const files: AssetFiles = {
  write: (owner, id, name, bytes) => write(fileKey(owner, id, name), bytes, name === 'region' ? 'application/octet-stream' : 'application/gzip'),
  read: (owner, id, name) => readBytes(fileKey(owner, id, name)),
  removeAll: async (owner, id) => { await removePrefix(assetPrefix(owner, id)); },
};

/** The real dependencies for a signed-in user. */
export function deps(user: UserRow): Deps {
  return {
    store,
    files,
    requireRole: (serverId, role) => requireRole(user, serverId, role),
    relay: (serverId, frame) => hub().request(serverId, frame, RELAY_TIMEOUT_MS),
    async mcVersion(serverId) {
      const live = hub().snapshot(serverId).info.mc;
      if (typeof live === 'string') return live;
      const r = await one<{ info: unknown }>('SELECT info FROM servers WHERE id = ?', [serverId]);
      const info = typeof r?.info === 'string' ? JSON.parse(r.info) : r?.info;
      return typeof info?.mc === 'string' ? info.mc : null;
    },
  };
}

/**
 * Runs an asset call and answers with what it returns (a Response as it is, nothing as 204): AssetErrors become HTTP
 * errors, and an offline or silent server the relay's answer.
 */
export async function answer(work: () => Promise<unknown>, status = 200): Promise<Response> {
  try {
    const out = await work();
    if (out instanceof Response) return out;
    return out === undefined ? new Response(null, { status: 204 }) : json(out, status);
  } catch (e) {
    if (e instanceof AssetError) throw new HttpError(e.status, e.message);
    const code = (e as { code?: unknown } | null)?.code;
    if (code === 'OFFLINE' || code === 'TIMEOUT') {
      const [s, b] = relayError(e);
      return json(b, s);
    }
    throw e;
  }
}
