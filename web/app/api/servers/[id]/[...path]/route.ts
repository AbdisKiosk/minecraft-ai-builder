import type { NextRequest } from 'next/server';
import * as archive from '../../../../../lib/server/archive';
import { hub, type Json } from '../../../../../lib/server/hub';
import { relayError } from '../../../../../lib/server/plugin-proxy';
import { body, forward, HttpError, json, requireRole, requireUser, route } from '../../../../../lib/server/route';
import type { Role } from '../../../../../lib/server/roles';

// Drafts live on the Minecraft server, and so does history until its plugin archives it (archive.ts): these routes
// answer from the archive when they can and forward to the plugin over the relay otherwise.
const TIMEOUT_MS = 6 * 60_000;

type P = { id: string; path: string[] };

interface Op { frame: Json; needs: Role }

function op(t: 'history' | 'draft', name: string, args: Json, needs: Role = 'viewer'): Op {
  return { frame: { t, op: name, args }, needs };
}

function viewArgs(req: NextRequest, id: string): Json {
  const args: Json = { id };
  for (const k of ['view', 'corner']) {
    const v = req.nextUrl.searchParams.get(k);
    if (v) args[k] = v;
  }
  return args;
}

function resolveGet(req: NextRequest, path: string[]): Op | null {
  const [kind, id, sub] = path;
  const t = kind === 'history' ? 'history' : kind === 'drafts' ? 'draft' : null;
  if (!t || path.length > 3) return null;
  if (!id) {
    if (t === 'draft') return op(t, 'list', {});
    const args: Json = {};
    const limit = Number(req.nextUrl.searchParams.get('limit'));
    if (Number.isInteger(limit) && limit > 0) args.limit = Math.min(limit, 200);
    const before = req.nextUrl.searchParams.get('before');
    if (before) args.before = before;
    return op(t, 'list', args);
  }
  if (!sub) return op(t, 'get', { id });
  if (sub === 'render') return op(t, 'render', viewArgs(req, id));
  if (sub === 'blocks') return op(t, 'blocks', { id });
  return null;
}

export const GET = route<P>(async (req, { id, path }) => {
  const o = resolveGet(req, path);
  if (!o) throw new HttpError(404, 'not found');
  const user = await requireUser();
  await requireRole(user, id, o.needs);
  if (o.frame.t === 'history') {
    const archived = await fromArchive(id, o.frame);
    if (archived) return archived;
  }
  return forward(id, o.frame, TIMEOUT_MS);
});

const NOT_KEPT = 'The server is offline, and this entry was archived without its pictures (it is older than the archive, or too large to draw).';
const OFFLINE_VIEW = 'The server is offline. Only the default picture (isometric, from the south-east) is kept; other views need the server online.';

/** The archive's answer to a history request, or null to ask the plugin. */
async function fromArchive(serverId: string, frame: Json): Promise<Response | null> {
  const args = frame.args as Json;
  if (frame.op === 'list') {
    // A plugin that never archived (an old one) still lists its own history.
    if (!(await archive.hasArchive(serverId)) && hub().online(serverId)) return null;
    return json(await archive.list(serverId, args.limit ?? 50, args.before ?? null));
  }
  const row = await archive.entry(serverId, String(args.id));
  if (!row) return null;
  if (frame.op === 'get') return json(row.detail);
  const online = hub().online(serverId);
  if (frame.op === 'blocks') {
    if (row.files.includes('blocks')) return json(await archive.readJson(serverId, row.entry_id, 'blocks'));
    return online ? null : json({ error: NOT_KEPT, offline: true }, 503);
  }
  if (frame.op === 'render') {
    const isDefault = (!args.view || args.view === 'iso') && (!args.corner || args.corner === 'se');
    if (isDefault && row.files.includes('render')) return json(await archive.readJson(serverId, row.entry_id, 'render'));
    if (!online) return json({ error: row.files.includes('render') ? OFFLINE_VIEW : NOT_KEPT, offline: true }, 503);
    return null;
  }
  return null;
}

/** Revert a history entry, approve or reject a draft: admins and owners. */
export const POST = route<P>(async (req, { id, path }) => {
  const [kind, itemId, action] = path;
  if (path.length !== 3 || !itemId) throw new HttpError(404, 'not found');
  let o: Op;
  const b = await body(req);
  const force = b.force === true;
  if (kind === 'history' && action === 'revert') o = op('history', 'revert', { id: itemId, force }, 'admin');
  else if (kind === 'drafts' && action === 'approve') o = op('draft', 'approve', { id: itemId, force, by: '' }, 'admin');
  else if (kind === 'drafts' && action === 'reject') o = op('draft', 'reject', { id: itemId, by: '' }, 'admin');
  else throw new HttpError(404, 'not found');
  const user = await requireUser();
  await requireRole(user, id, o.needs);
  if ('by' in o.frame.args) o.frame.args.by = `panel: ${user.name || user.email || 'user ' + user.id}`.slice(0, 80);
  if (o.frame.t === 'history') return revert(id, o.frame);
  return forward(id, o.frame, TIMEOUT_MS);
});

/** Reverts on the plugin; for an entry it no longer keeps, sends the archived entry and diff along. */
async function revert(serverId: string, frame: Json): Promise<Response> {
  try {
    const h = hub();
    let reply = await h.request(serverId, frame, TIMEOUT_MS);
    const err = (reply.body as { error?: unknown } | null)?.error;
    if (reply.status === 400 && typeof err === 'string' && err.startsWith('no history entry')) {
      const row = await archive.entry(serverId, String(frame.args.id));
      const diff = row?.files.includes('diff') ? await archive.diffBase64(serverId, row.entry_id) : null;
      if (row && diff) reply = await h.request(serverId, { ...frame, args: { ...frame.args, entry: row.detail, diff } }, TIMEOUT_MS);
    }
    return reply.body === null ? new Response(null, { status: reply.status }) : json(reply.body, reply.status);
  } catch (e) {
    const [status, b] = relayError(e);
    return json(b, status);
  }
}
