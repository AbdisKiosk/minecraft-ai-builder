import { serversOf } from '../../../lib/server/access';
import { hub, type HubEvent } from '../../../lib/server/hub';
import { json } from '../../../lib/server/route';
import { currentUser } from '../../../lib/server/session';
import { serverViews } from '../../../lib/server/views';

export const dynamic = 'force-dynamic';

/**
 * Server-sent events for the dashboard: a snapshot of the user's servers, then server, job and draft changes on
 * those servers. When the user's access changes, a fresh snapshot.
 */
export async function GET(req: Request): Promise<Response> {
  const user = await currentUser();
  if (!user) return json({ error: 'sign in first' }, 401);
  const encoder = new TextEncoder();
  let allowed = new Set<string>();
  let cleanup = () => {};

  const stream = new ReadableStream<Uint8Array>({
    async start(controller) {
      let closed = false;
      const write = (text: string) => {
        if (closed) return;
        try {
          controller.enqueue(encoder.encode(text));
        } catch {
          cleanup();
        }
      };
      const send = (event: string, data: unknown) => write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
      // Serialised so a slow snapshot can't land after a newer one.
      let chain = Promise.resolve();
      const snapshot = () => {
        chain = chain.then(async () => {
          const servers = await serverViews(user.id);
          allowed = new Set(servers.map(s => s.id));
          send('snapshot', { servers });
        }).catch(e => console.warn('events: snapshot failed', e));
      };

      const unsubscribe = hub().subscribe((e: HubEvent) => {
        if (e.type === 'access') {
          if (e.users.includes(user.id)) snapshot();
          return;
        }
        if (!allowed.has(e.server)) return;
        if (e.type === 'server') send('server', { id: e.server, ...e.state });
        else if (e.type === 'job') send('job', { server: e.server, event: e.event, job: e.job });
        else if (e.type === 'history') send('history', { server: e.server, entry: e.entry });
        else send('draft', { server: e.server, draft: e.draft });
      });
      // Keeps proxies from closing an idle stream; also re-checks membership in case an event was missed.
      const ping = setInterval(() => write(': ping\n\n'), 25_000);
      const recheck = setInterval(async () => {
        try {
          const ids = new Set((await serversOf(user.id)).map(s => s.id));
          if (ids.size !== allowed.size || [...ids].some(id => !allowed.has(id))) snapshot();
        } catch {
          // the next tick tries again
        }
      }, 60_000);
      cleanup = () => {
        if (closed) return;
        closed = true;
        unsubscribe();
        clearInterval(ping);
        clearInterval(recheck);
        try {
          controller.close();
        } catch {
          // already closed
        }
      };
      req.signal.addEventListener('abort', cleanup);
      write('retry: 3000\n\n');
      snapshot();
    },
    cancel() {
      cleanup();
    },
  });

  return new Response(stream, {
    headers: {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    },
  });
}
