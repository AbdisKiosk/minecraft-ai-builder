import { useSyncExternalStore } from 'react';

// Pages live in the URL hash: the panel serves one static page, and /s/<id>/ paths belong to the MCP/REST proxy.
export type Route =
  | { page: 'dashboard' }
  | { page: 'server'; server: string }
  | { page: 'diff'; server: string; id: string }
  | { page: 'draft'; server: string; id: string };

export const serverHref = (server: string) => `#/s/${encodeURIComponent(server)}`;
export const diffHref = (server: string, id: string) => `${serverHref(server)}/h/${encodeURIComponent(id)}`;
export const draftHref = (server: string, id: string) => `${serverHref(server)}/d/${encodeURIComponent(id)}`;

export function parseRoute(hash: string): Route {
  const parts = hash.replace(/^#\/?/, '').split('/').map(p => {
    try { return decodeURIComponent(p); } catch { return p; }
  });
  if (parts[0] === 's' && parts[1]) {
    if (parts[2] === 'h' && parts[3]) return { page: 'diff', server: parts[1], id: parts[3] };
    if (parts[2] === 'd' && parts[3]) return { page: 'draft', server: parts[1], id: parts[3] };
    return { page: 'server', server: parts[1] };
  }
  return { page: 'dashboard' };
}

function subscribe(listener: () => void) {
  window.addEventListener('hashchange', listener);
  return () => window.removeEventListener('hashchange', listener);
}

export function useHash(): string {
  return useSyncExternalStore(subscribe, () => window.location.hash, () => '');
}

export function go(href: string) {
  window.location.hash = href;
}
