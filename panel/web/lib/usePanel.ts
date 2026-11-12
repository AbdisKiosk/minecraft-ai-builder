import { useCallback, useEffect, useReducer, useState } from 'react';
import { api, setUnauthorizedHandler } from './api';
import type { DraftSummary, Job, Server } from './types';

export type Servers = ReadonlyMap<string, Server>;

type Action =
  | { type: 'snapshot'; servers: Server[] }
  | { type: 'server'; server: Server }
  | { type: 'job'; server: string; event: string; job: Job }
  | { type: 'draft'; server: string; draft: DraftSummary };

function reduce(servers: Servers, action: Action): Servers {
  if (action.type === 'snapshot') return new Map(action.servers.map(s => [s.id, s]));
  if (action.type === 'server') return new Map(servers).set(action.server.id, action.server);
  const s = servers.get(action.server);
  if (!s) return servers;
  let next: Server;
  if (action.type === 'job') {
    const job = action.job;
    const running = s.running.filter(j => j.id !== job.id);
    next = action.event === 'job.end'
      ? { ...s, running, recent: [job, ...s.recent.filter(j => j.id !== job.id)].slice(0, 50) }
      : { ...s, running: [...running, job] };
  } else {
    const draft = action.draft;
    const drafts = (s.drafts || []).filter(x => x.id !== draft.id);
    next = { ...s, drafts: draft.status === 'open' ? [...drafts, draft] : drafts };
  }
  return new Map(servers).set(s.id, next);
}

export type Session = 'loading' | 'login' | 'ready';

/** The login session and the servers' state, kept current by the /events stream. */
export function usePanel() {
  const [session, setSession] = useState<Session>('loading');
  const [servers, dispatch] = useReducer(reduce, new Map<string, Server>());
  const [live, setLive] = useState(false);

  const start = useCallback(async () => {
    try {
      const state = await api<{ servers: Server[] }>('/api/state');
      dispatch({ type: 'snapshot', servers: state.servers });
      setSession('ready');
    } catch {
      // A 401 already switched to the login form; anything else is shown there too.
      setSession('login');
    }
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(() => setSession('login'));
    start();
  }, [start]);

  useEffect(() => {
    if (session !== 'ready') return;
    const source = new EventSource('/events');
    const on = (name: string, handle: (data: any) => void) =>
      source.addEventListener(name, ev => handle(JSON.parse((ev as MessageEvent).data)));
    on('snapshot', data => {
      dispatch({ type: 'snapshot', servers: data.servers });
      setLive(true);
    });
    on('server', server => dispatch({ type: 'server', server }));
    on('job', data => dispatch({ type: 'job', ...data }));
    on('draft', data => dispatch({ type: 'draft', ...data }));
    source.onerror = () => {
      setLive(false);
      // The browser retries by itself; check whether the session expired.
      fetch('/api/state', { credentials: 'same-origin' }).then(r => { if (r.status === 401) setSession('login'); });
    };
    return () => {
      source.close();
      setLive(false);
    };
  }, [session]);

  const logout = useCallback(async () => {
    await fetch('/api/logout', { method: 'POST', credentials: 'same-origin' });
    setSession('login');
  }, []);

  return { session, servers, live, login: start, logout };
}
