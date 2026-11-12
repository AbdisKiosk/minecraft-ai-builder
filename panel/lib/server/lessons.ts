import type { Json } from './hub.ts';
import type { Role } from './roles.ts';

/**
 * The Lessons page's requests, as lessons frames for the plugin (plugin/.../Lessons.java over the relay), with the
 * role each one needs. Every member sees lessons and marks them seen by opening the page; admins and owners add, edit
 * and delete them, and give feedback after a reject or revert (which only they can do).
 *   GET    lessons                         list
 *   POST   lessons            {text}       add (origin manual)
 *   PATCH  lessons/<lesson>   {text}       edit
 *   DELETE lessons/<lesson>                delete
 *   POST   lessons/seen                    mark all seen
 *   POST   lessons/feedback   {id, text, remember}   feedback on a rejected draft or reverted history entry
 */
export interface LessonOp { op: 'list' | 'add' | 'edit' | 'delete' | 'seen' | 'feedback'; args: Json; needs: Role }

export class LessonError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

/** The plugin cuts lessons to 160 characters and feedback to 300; this only bounds what crosses the relay. */
const MAX_INPUT = 2000;
const ID = /^[A-Za-z0-9_-]{1,40}$/;

export const TOO_OLD = 'This server\'s plugin is too old for lessons. Update it with the jar from this panel.';

function text(b: Json, key: string, what: string): string {
  const v = b[key];
  if (typeof v !== 'string' || !v.trim()) throw new LessonError(400, `${what} is empty`);
  return v.slice(0, MAX_INPUT);
}

function id(v: unknown, what: string): string {
  if (typeof v !== 'string' || !ID.test(v)) throw new LessonError(400, `'${String(v)}' is not ${what}`);
  return v;
}

/** The frame op for a request, or a LessonError (404 for a path that is not one). `by` names the panel user. */
export function lessonOp(method: string, path: string[], body: Json, by: string): LessonOp {
  const [first, ...rest] = path;
  if (rest.length) throw new LessonError(404, 'not found');
  if (method === 'GET' && !first) return { op: 'list', args: {}, needs: 'viewer' };
  if (method === 'POST' && !first) return { op: 'add', args: { text: text(body, 'text', 'the lesson'), by }, needs: 'admin' };
  if (method === 'POST' && first === 'seen') return { op: 'seen', args: {}, needs: 'viewer' };
  if (method === 'POST' && first === 'feedback') {
    return {
      op: 'feedback',
      args: { id: id(body.id, 'a draft or history id'), feedback: text(body, 'text', 'the feedback'), remember: body.remember === true, by },
      needs: 'admin',
    };
  }
  if (method === 'PATCH' && first) return { op: 'edit', args: { id: id(first, 'a lesson id'), text: text(body, 'text', 'the lesson') }, needs: 'admin' };
  if (method === 'DELETE' && first) return { op: 'delete', args: { id: id(first, 'a lesson id') }, needs: 'admin' };
  throw new LessonError(404, 'not found');
}

/**
 * Throws unless the plugin speaks lessons. An offline server is let through: the relay then answers that it is
 * offline. A plugin from before lessons would answer "unknown frame type", which says less.
 */
export function requireLessons(online: boolean, info: Json): void {
  if (online && info.lessons !== true) throw new LessonError(501, TOO_OLD);
}
