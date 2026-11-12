import type { NextRequest } from 'next/server';
import type { Json } from '../../../../../lib/server/hub';
import { body, forward, HttpError, requireRole, requireUser, route } from '../../../../../lib/server/route';
import type { Role } from '../../../../../lib/server/roles';

// History and drafts live on the Minecraft server; these routes forward to its plugin over the relay.
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
  return forward(id, o.frame, TIMEOUT_MS);
});

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
  return forward(id, o.frame, TIMEOUT_MS);
});
