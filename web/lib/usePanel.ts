import { useCallback, useEffect, useReducer, useState } from 'react';
import { api, signIn } from './api';
import type { DraftSummary, Job, Server } from './types';

export type Servers = ReadonlyMap<string, Server>;

type Action =
  | { type: 'snapshot'; servers: Server[] }
  | { type: 'server'; server: Server }
  | { type: 'job'; server: string; event: string; job: Job }
  | { type: 'draft'; server: string; draft: DraftSummary };

function reduce(servers: Servers, action: Action): Servers {
  if (action.type === 'snapshot') return new Map(action.servers.map(s => [s.id, s]));
  if (action.type === 'server') {
    // Live state only: name and role come with snapshots.
    const old = servers.get(action.server.id);
    return old ? new Map(servers).set(old.id, { ...old, ...action.server, name: old.name, role: old.role }) : servers;
  }
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

/** The user's servers, kept current by the /api/events stream. */
export function usePanel() {
  const [servers, dispatch] = useReducer(reduce, new Map<string, Server>());
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState('');
  const [live, setLive] = useState(false);

  const reload = useCallback(async () => {
    try {
      const state = await api<{ servers: Server[] }>('/api/servers');
      dispatch({ type: 'snapshot', servers: state.servers });
      setLoaded(true);
      setError('');
    } catch (ex) {
      setError(ex instanceof Error ? ex.message : String(ex));
    }
  }, []);

  useEffect(() => { reload(); }, [reload]);

  useEffect(() => {
    const source = new EventSource('/api/events');
    const on = (name: string, handle: (data: any) => void) =>
      source.addEventListener(name, ev => handle(JSON.parse((ev as MessageEvent).data)));
    on('snapshot', data => {
      dispatch({ type: 'snapshot', servers: data.servers });
      setLoaded(true);
      setLive(true);
    });
    on('server', server => dispatch({ type: 'server', server }));
    on('job', data => dispatch({ type: 'job', ...data }));
    on('draft', data => dispatch({ type: 'draft', ...data }));
    source.onerror = () => {
      setLive(false);
      // The browser retries by itself; check whether the session expired.
      fetch('/api/me', { credentials: 'same-origin' }).then(r => { if (r.status === 401) signIn(); }, () => {});
    };
    return () => {
      source.close();
      setLive(false);
    };
  }, []);

  return { servers, loaded, error, live, reload };
}
