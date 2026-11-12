import { gunzipSync } from 'node:zlib';
import { exec, one, query, transaction } from './db';
import { readBytes, remove, sizeOf, uploadUrl } from './r2';

// History entries the plugins archive: detail in MySQL, blocks in R2. See RelayProtocol.java for the frames.

const FILES = { diff: 'diff.gz', blocks: 'blocks.json.gz', render: 'render.json.gz' } as const;
export type FileName = keyof typeof FILES;
const ENTRY = /^h[0-9a-z]{1,24}$/;
const MAX_FILE = 256 * 1024 * 1024;
/** Kept per server; the oldest entries go first. */
export const KEEP_ENTRIES = 5000;
export const KEEP_BYTES = 2 * 1024 ** 3;

export class ArchiveError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

const isFile = (n: unknown): n is FileName => typeof n === 'string' && n in FILES;
const key = (serverId: string, entryId: string, name: FileName) => `servers/${serverId}/history/${entryId}/${FILES[name]}`;
export const serverPrefix = (serverId: string) => `servers/${serverId}/`;

function checkEntry(entryId: unknown): string {
  if (typeof entryId !== 'string' || !ENTRY.test(entryId)) throw new ArchiveError(400, 'bad history entry id');
  return entryId;
}

/** Upload URLs for an entry's files: {"files":[{"name","size"}]} → {"urls":{name: url}}. */
export async function uploadUrls(serverId: string, entryId: unknown, files: unknown): Promise<{ urls: Record<string, string> }> {
  const id = checkEntry(entryId);
  if (!Array.isArray(files) || !files.length || files.length > 3) throw new ArchiveError(400, 'give one to three files');
  const urls: Record<string, string> = {};
  for (const f of files as { name?: unknown; size?: unknown }[]) {
    if (!isFile(f?.name)) throw new ArchiveError(400, `unknown file '${String(f?.name)}'`);
    if (!Number.isInteger(f.size) || (f.size as number) < 1 || (f.size as number) > MAX_FILE) throw new ArchiveError(400, `bad size for ${f.name}`);
    urls[f.name] = await uploadUrl(key(serverId, id, f.name), f.size as number, 'application/gzip');
  }
  return { urls };
}

/** Records an uploaded entry, after checking its files are there, then trims the server's archive. */
export async function commit(serverId: string, entryId: unknown, detail: unknown, files: unknown): Promise<void> {
  const id = checkEntry(entryId);
  if (!detail || typeof detail !== 'object' || Array.isArray(detail) || (detail as { id?: unknown }).id !== id) {
    throw new ArchiveError(400, 'detail must be the entry, with its id');
  }
  const names = Array.isArray(files) ? files : [];
  if (!names.every(isFile)) throw new ArchiveError(400, 'unknown file name');
  let bytes = 0;
  for (const name of names as FileName[]) {
    const size = await sizeOf(key(serverId, id, name));
    if (size === null) throw new ArchiveError(409, `${name} was not uploaded`);
    bytes += size;
  }
  const time = Number((detail as { time?: unknown }).time) || Date.now();
  await exec(
    `INSERT INTO history (server_id, entry_id, time_ms, detail, files, bytes) VALUES (?, ?, ?, ?, ?, ?)
     ON DUPLICATE KEY UPDATE time_ms = VALUES(time_ms), detail = VALUES(detail), files = VALUES(files), bytes = VALUES(bytes)`,
    [serverId, id, time, JSON.stringify(detail), names.join(','), bytes]);
  await trim(serverId);
}

/** Drops the oldest entries over KEEP_ENTRIES or KEEP_BYTES, from MySQL and R2. */
async function trim(serverId: string) {
  const drop = await transaction(async c => {
    const rows = await query<{ entry_id: string; files: string; bytes: number }>(
      'SELECT entry_id, files, bytes FROM history WHERE server_id = ? ORDER BY time_ms DESC, entry_id DESC FOR UPDATE', [serverId], c);
    let total = 0;
    const out: { entry_id: string; files: string }[] = [];
    rows.forEach((r, n) => {
      total += Number(r.bytes);
      if (n >= KEEP_ENTRIES || total > KEEP_BYTES) out.push(r);
    });
    for (const r of out) await exec('DELETE FROM history WHERE server_id = ? AND entry_id = ?', [serverId, r.entry_id], c);
    return out;
  });
  const keys = drop.flatMap(r => r.files.split(',').filter(isFile).map(n => key(serverId, r.entry_id, n)));
  if (keys.length) await remove(keys).catch(e => console.warn('archive: could not delete old files', e));
}

export interface Row { entry_id: string; detail: Record<string, any>; files: FileName[] }

function row(r: { entry_id: string; detail: unknown; files: string }): Row {
  const detail = typeof r.detail === 'string' ? JSON.parse(r.detail) : r.detail;
  return { entry_id: r.entry_id, detail: detail as Record<string, any>, files: r.files.split(',').filter(isFile) };
}

export async function entry(serverId: string, entryId: string): Promise<Row | null> {
  const r = await one<{ entry_id: string; detail: unknown; files: string }>(
    'SELECT entry_id, detail, files FROM history WHERE server_id = ? AND entry_id = ?', [serverId, entryId]);
  return r && row(r);
}

export async function hasArchive(serverId: string): Promise<boolean> {
  return !!(await one('SELECT 1 FROM history WHERE server_id = ? LIMIT 1', [serverId]));
}

const LISTED = ['types', 'changes', 'listed'];

/** Newest first, like the plugin's list: {entries, total}; `before` is an entry id to page from. */
export async function list(serverId: string, limit: number, before: string | null) {
  let rows;
  if (before) {
    const b = await one<{ time_ms: number }>('SELECT time_ms FROM history WHERE server_id = ? AND entry_id = ?', [serverId, before]);
    rows = !b ? [] : await query<{ entry_id: string; detail: unknown; files: string }>(
      `SELECT entry_id, detail, files FROM history WHERE server_id = ? AND (time_ms < ? OR (time_ms = ? AND entry_id < ?))
        ORDER BY time_ms DESC, entry_id DESC LIMIT ?`, [serverId, b.time_ms, b.time_ms, before, limit]);
  } else {
    rows = await query<{ entry_id: string; detail: unknown; files: string }>(
      'SELECT entry_id, detail, files FROM history WHERE server_id = ? ORDER BY time_ms DESC, entry_id DESC LIMIT ?', [serverId, limit]);
  }
  const total = await one<{ n: number }>('SELECT COUNT(*) AS n FROM history WHERE server_id = ?', [serverId]);
  return {
    entries: rows.map(r => {
      const d = { ...row(r).detail };
      for (const k of LISTED) delete d[k];
      return d;
    }),
    total: Number(total?.n ?? 0),
  };
}

/** A gzipped JSON file of an entry, parsed. */
export async function readJson(serverId: string, entryId: string, name: 'blocks' | 'render'): Promise<unknown | null> {
  const bytes = await readBytes(key(serverId, entryId, name));
  return bytes && JSON.parse(gunzipSync(bytes).toString('utf8'));
}

/** The entry's diff as the plugin encoded it, base64, for reverting an entry the server no longer keeps. */
export async function diffBase64(serverId: string, entryId: string): Promise<string | null> {
  const bytes = await readBytes(key(serverId, entryId, 'diff'));
  return bytes && Buffer.from(bytes).toString('base64');
}
