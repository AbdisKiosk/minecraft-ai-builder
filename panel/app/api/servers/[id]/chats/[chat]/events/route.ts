import type { ChatEvent } from '../../../../../../../lib/chat';
import { follow, ownChat } from '../../../../../../../lib/server/chat/runs';
import { roleOf } from '../../../../../../../lib/server/access';
import { atLeast } from '../../../../../../../lib/server/roles';
import { HttpError, json } from '../../../../../../../lib/server/route';
import { currentUser } from '../../../../../../../lib/server/session';

export const dynamic = 'force-dynamic';

/**
 * Server-sent events of the chat's running turn, from its start: text, tool calls and their answers, usage, then
 * "end", after which the stream closes. A chat with no turn running sends "end" at once.
 */
export async function GET(req: Request, ctx: { params: Promise<{ id: string; chat: string }> }): Promise<Response> {
  const { id, chat } = await ctx.params;
  const user = await currentUser();
  if (!user) return json({ error: 'sign in first' }, 401);
  if (!atLeast(await roleOf(user.id, id), 'builder')) return json({ error: 'no such chat' }, 404);
  try {
    await ownChat(chat, id, user);
  } catch (e) {
    if (e instanceof HttpError) return json({ error: e.message }, e.status);
    throw e;
  }
  const encoder = new TextEncoder();
  let cleanup = () => {};

  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      let closed = false;
      let unfollow: (() => void) | null = null;
      const ping = setInterval(() => write(': ping\n\n'), 25_000);
      cleanup = () => {
        if (closed) return;
        closed = true;
        clearInterval(ping);
        unfollow?.();
        try {
          controller.close();
        } catch {
          // already closed
        }
      };
      const write = (text: string) => {
        if (closed) return;
        try {
          controller.enqueue(encoder.encode(text));
        } catch {
          cleanup();
        }
      };
      const send = (e: ChatEvent) => {
        write(`data: ${JSON.stringify(e)}\n\n`);
        if (e.type === 'end') cleanup();
      };
      req.signal.addEventListener('abort', cleanup);
      unfollow = follow(chat, send);
      if (!unfollow) send({ type: 'end', reason: 'done' });
      else if (closed) unfollow();
    },
    cancel() {
      cleanup();
    },
  });

  return new Response(stream, {
    headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache, no-transform', Connection: 'keep-alive', 'X-Accel-Buffering': 'no' },
  });
}
