import { useSyncExternalStore } from 'react';

// Dashboard pages live in the URL hash under /dashboard: real /s/<id>/ paths belong to the MCP/REST proxy.
export type Route =
  | { page: 'dashboard' }
  | { page: 'tokens' }
  | { page: 'server'; server: string }
  | { page: 'setup'; server: string }
  | { page: 'diff'; server: string; id: string }
  | { page: 'draft'; server: string; id: string }
  | { page: 'map'; server: string; world: string | null }
  | { page: 'lessons'; server: string }
  | { page: 'assets' };

export const tokensHref = '#/tokens';
export const serverHref = (server: string) => `#/s/${encodeURIComponent(server)}`;
export const setupHref = (server: string) => `${serverHref(server)}/setup`;
export const diffHref = (server: string, id: string) => `${serverHref(server)}/h/${encodeURIComponent(id)}`;
export const draftHref = (server: string, id: string) => `${serverHref(server)}/d/${encodeURIComponent(id)}`;
export const lessonsHref = (server: string) => `${serverHref(server)}/lessons`;
export const mapHref = (server: string, world?: string) => `${serverHref(server)}/map${world ? '/' + encodeURIComponent(world) : ''}`;
export const assetsHref = '#/assets';

export function parseRoute(hash: string): Route {
  const parts = hash.replace(/^#\/?/, '').split('/').map(p => {
    try { return decodeURIComponent(p); } catch { return p; }
  });
  if (parts[0] === 'tokens') return { page: 'tokens' };
  if (parts[0] === 's' && parts[1]) {
    if (parts[2] === 'setup') return { page: 'setup', server: parts[1] };
    if (parts[2] === 'h' && parts[3]) return { page: 'diff', server: parts[1], id: parts[3] };
    if (parts[2] === 'd' && parts[3]) return { page: 'draft', server: parts[1], id: parts[3] };
    if (parts[2] === 'lessons') return { page: 'lessons', server: parts[1] };
    if (parts[2] === 'map') return { page: 'map', server: parts[1], world: parts[3] || null };
    return { page: 'server', server: parts[1] };
  }
  if (parts[0] === 'assets') return { page: 'assets' };
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
