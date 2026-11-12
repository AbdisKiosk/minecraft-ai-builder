import type { NextRequest } from 'next/server';
import { hub } from '../../../../../../lib/server/hub';
import { LessonError, lessonOp, requireLessons, type LessonOp } from '../../../../../../lib/server/lessons';
import { body, forward, HttpError, requireRole, requireUser, route } from '../../../../../../lib/server/route';

// Lessons live on the Minecraft server (plugins/AIBuilder/lessons.json); see lib/server/lessons.ts for the requests.
type P = { id: string; path?: string[] };

const TIMEOUT_MS = 30_000;

async function handle(req: NextRequest, { id, path }: P): Promise<Response> {
  const user = await requireUser();
  const by = `panel: ${user.name || user.email || 'user ' + user.id}`.slice(0, 80);
  const b = req.method === 'POST' || req.method === 'PATCH' ? await body(req) : {};
  let o: LessonOp;
  try {
    o = lessonOp(req.method, path ?? [], b, by);
    await requireRole(user, id, o.needs);
    const s = hub().snapshot(id);
    requireLessons(s.online, s.info);
  } catch (e) {
    if (e instanceof LessonError) throw new HttpError(e.status, e.message);
    throw e;
  }
  return forward(id, { t: 'lessons', op: o.op, args: o.args }, TIMEOUT_MS);
}

export const GET = route<P>(handle);
export const POST = route<P>(handle);
export const PATCH = route<P>(handle);
export const DELETE = route<P>(handle);
