import type { UserRow } from './access';
import { anchorColumns, COLUMNS, row, type Db } from './assetRow';
import {
  AssetError, assetPrefix, fileKey, SHARE_ROLE, type AssetFiles, type AssetStore, type Changes, type Deps, type SharedRow,
} from './assets';
import { featureRefusal } from '../plans';
import { limitsFor, PAYMENT_REQUIRED } from './billing';
import { exec, one, query, transaction } from './db';
import { hub } from './hub';
import { relayError } from './plugin-proxy';
import { readBytes, removePrefix, write } from './r2';
import { atLeast, ROLES } from './roles';
import { HttpError, json, requireRole } from './route';

// The asset library's MySQL rows, R2 files and relay, for the rules in assets.ts.

/** Exporting or importing a large region can take minutes, as for promote. */
const RELAY_TIMEOUT_MS = 6 * 60_000;

/** The roles that may share an asset with a server and use one shared with it, for SQL. */
const SHARERS = ROLES.filter(r => atLeast(r, SHARE_ROLE)).map(r => `'${r}'`).join(', ');

const taken = (e: unknown, name: string) => (e as { code?: string })?.code === 'ER_DUP_ENTRY'
  ? new AssetError(409, `you already have an asset named '${name}'`) : e;

export const store: AssetStore = {
  async list(owner) {
    return (await query<Db>(`SELECT ${COLUMNS} FROM assets WHERE user_id = ? ORDER BY COALESCE(last_used_at, created_at) DESC, id`, [owner])).map(row);
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
          `INSERT INTO assets (id, user_id, name, description, tags, size_x, size_y, size_z, block_count, mc_version, bytes,
             anchor_x, anchor_y, anchor_z, facing)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
          [a.id, a.owner, a.name, a.description, JSON.stringify(a.tags), a.size[0], a.size[1], a.size[2], a.blocks, a.mc, a.bytes,
            ...anchorColumns(a)], c);
      } catch (e) {
        throw taken(e, a.name);
      }
    });
  },
  async update(owner, id, changes: Changes) {
    const sets: string[] = [], params: (string | number | null)[] = [];
    if (changes.name !== undefined) { sets.push('name = ?'); params.push(changes.name); }
    if (changes.description !== undefined) { sets.push('description = ?'); params.push(changes.description); }
    if (changes.tags !== undefined) { sets.push('tags = ?'); params.push(JSON.stringify(changes.tags)); }
    if (changes.anchor !== undefined) {
      sets.push('anchor_x = ?, anchor_y = ?, anchor_z = ?');
      params.push(...anchorColumns({ anchor: changes.anchor, facing: null }).slice(0, 3));
    }
    if (changes.facing !== undefined) { sets.push('facing = ?'); params.push(changes.facing); }
    if (!sets.length) return;
    try {
      await exec(`UPDATE assets SET ${sets.join(', ')} WHERE id = ? AND user_id = ?`, [...params, id, owner]);
    } catch (e) {
      throw taken(e, changes.name ?? '');
    }
  },
  async remove(owner, id) {
    // asset_shares rows go with it (ON DELETE CASCADE).
    await exec('DELETE FROM assets WHERE id = ? AND user_id = ?', [id, owner]);
  },
  async touch(owner, id) {
    await exec('UPDATE assets SET last_used_at = NOW(), updated_at = updated_at WHERE id = ? AND user_id = ?', [id, owner]);
  },
  async shares(owner) {
    const rows = await query<{ asset_id: string; server_id: string; name: string; created_at: Date }>(
      `SELECT sh.asset_id, sh.server_id, s.name, sh.created_at
         FROM asset_shares sh JOIN assets a ON a.id = sh.asset_id JOIN servers s ON s.id = sh.server_id
        WHERE a.user_id = ? ORDER BY s.name, s.id`, [owner]);
    return rows.map(r => ({ asset: r.asset_id, server: r.server_id, name: r.name, created: r.created_at.getTime() }));
  },
  async share(id, server, by) {
    await exec(`INSERT INTO asset_shares (asset_id, server_id, shared_by) VALUES (?, ?, ?)
       ON DUPLICATE KEY UPDATE asset_id = asset_id`, [id, server, by]);
  },
  async unshare(id, server) {
    await exec('DELETE FROM asset_shares WHERE asset_id = ? AND server_id = ?', [id, server]);
  },
  async sharedWith(user, filter = {}) {
    // One row per (asset, server); both the caller and the owner must still build on that server.
    const where = ['a.user_id <> ?'], params: (string | number)[] = [user, user];
    if (filter.server !== undefined) { where.push('sh.server_id = ?'); params.push(filter.server); }
    if (filter.name !== undefined) { where.push('a.name = ?'); params.push(filter.name); }
    if (filter.id !== undefined) { where.push('a.id = ?'); params.push(filter.id); }
    const rows = await query<Db & { server_id: string; owner_name: string }>(
      `SELECT ${COLUMNS.split(', ').map(c => `a.${c}`).join(', ')}, sh.server_id,
              COALESCE(NULLIF(u.name, ''), u.email, CONCAT('user ', u.id)) AS owner_name
         FROM asset_shares sh
         JOIN assets a ON a.id = sh.asset_id
         JOIN users u ON u.id = a.user_id
         JOIN memberships mine ON mine.server_id = sh.server_id AND mine.user_id = ? AND mine.role IN (${SHARERS})
         JOIN memberships theirs ON theirs.server_id = sh.server_id AND theirs.user_id = a.user_id AND theirs.role IN (${SHARERS})
        WHERE ${where.join(' AND ')}
        ORDER BY COALESCE(a.last_used_at, a.created_at) DESC, a.id, sh.server_id`, params);
    const out = new Map<string, SharedRow>();
    for (const r of rows) {
      const seen = out.get(r.id);
      if (seen) seen.via.push(r.server_id);
      else out.set(r.id, { ...row(r), ownerName: r.owner_name, via: [r.server_id] });
    }
    return [...out.values()];
  },
};

export const files: AssetFiles = {
  write: (owner, id, name, bytes) => write(fileKey(owner, id, name), bytes, name === 'region' ? 'application/octet-stream' : 'application/gzip'),
  read: (owner, id, name, maxBytes) => readBytes(fileKey(owner, id, name), maxBytes),
  removeAll: async (owner, id) => { await removePrefix(assetPrefix(owner, id)); },
};

/** Refuses saving, uploading and sharing on a plan without the asset library; what is already there stays usable. */
export async function allowSave(userId: number): Promise<void> {
  const refused = featureRefusal(await limitsFor(userId), 'assets');
  if (refused) throw new AssetError(PAYMENT_REQUIRED, refused);
}

/** The real dependencies for a signed-in user. */
export function deps(user: UserRow): Deps {
  return {
    store,
    files,
    allowSave,
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
