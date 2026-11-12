import { NextResponse, type NextRequest } from 'next/server';
import { roleOf, type UserRow } from './access';
import { hub, type Json } from './hub';
import { relayError } from './plugin-proxy';
import { atLeast, type Role } from './roles';
import { currentUser } from './session';

export class HttpError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

type Params = Record<string, string | string[]>;
type Handler<P extends Params> = (req: NextRequest, params: P) => Promise<Response>;

/** Wraps an API route: JSON errors, and writes only from the app's own pages. */
export function route<P extends Params = Record<string, never>>(fn: Handler<P>) {
  return async (req: NextRequest, ctx: { params: Promise<P> }): Promise<Response> => {
    try {
      if (req.method !== 'GET' && req.method !== 'HEAD' && !sameOrigin(req)) throw new HttpError(403, 'cross-site request refused');
      return await fn(req, await ctx.params);
    } catch (e) {
      if (e instanceof HttpError) return json({ error: e.message }, e.status);
      console.error(`${req.method} ${req.nextUrl.pathname} failed`, e);
      return json({ error: 'internal error' }, 500);
    }
  };
}

export function json(body: unknown, status = 200): Response {
  return NextResponse.json(body, { status });
}

export async function requireUser(): Promise<UserRow> {
  const user = await currentUser();
  if (!user) throw new HttpError(401, 'sign in first');
  return user;
}

/** The user's role on the server; 404 when they are not a member, 403 when the role is below `needed`. */
export async function requireRole(user: UserRow, serverId: string, needed: Role): Promise<Role> {
  const role = await roleOf(user.id, serverId);
  if (!role) throw new HttpError(404, `unknown server '${serverId}'`);
  if (!atLeast(role, needed)) throw new HttpError(403, `this needs the ${needed} role; yours is ${role}`);
  return role;
}

export async function body(req: NextRequest): Promise<Json> {
  const text = await req.text();
  if (!text.trim()) return {};
  try {
    const b = JSON.parse(text);
    if (b && typeof b === 'object' && !Array.isArray(b)) return b;
  } catch {
    // fall through
  }
  throw new HttpError(400, 'body must be a JSON object');
}

/** Sends a frame to a server and answers with its reply's status and body. */
export async function forward(serverId: string, frame: Json, timeoutMs: number): Promise<Response> {
  try {
    const reply = await hub().request(serverId, frame, timeoutMs);
    if (reply.body === null) return new Response(null, { status: reply.status });
    return json(reply.body, reply.status);
  } catch (e) {
    const [status, b] = relayError(e);
    return json(b, status);
  }
}

/** The app's public URL; relay and MCP addresses shown to users are built from it. */
export function baseUrl(req: NextRequest): string {
  return (process.env.APP_BASE_URL || req.nextUrl.origin).replace(/\/+$/, '');
}

function sameOrigin(req: NextRequest): boolean {
  const origin = req.headers.get('origin');
  if (!origin) return true; // browsers always send Origin on cross-site POSTs
  const host = req.headers.get('x-forwarded-host') || req.headers.get('host');
  try {
    return !!host && new URL(origin).host.toLowerCase() === host.toLowerCase();
  } catch {
    return false;
  }
}
