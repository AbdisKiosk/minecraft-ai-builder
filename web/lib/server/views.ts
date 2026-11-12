import { serversOf } from './access';
import { hub } from './hub';
import type { Role } from './roles';

export interface ServerView {
  id: string;
  name: string;
  role: Role;
  lastSeen: number | null;
  online: boolean;
  since: number;
  info: Record<string, unknown>;
  running: unknown[];
  recent: unknown[];
  drafts: unknown[];
}

/** What the dashboard knows about each of the user's servers: the database row merged with the live state. */
export async function serverViews(userId: number): Promise<ServerView[]> {
  const rows = await serversOf(userId);
  const h = hub();
  return rows.map(r => {
    const live = h.snapshot(r.id);
    // While a server is offline, what it said when it last connected (its Minecraft version, for the 3D view).
    const saved = typeof r.info === 'string' ? JSON.parse(r.info) : r.info;
    const info = Object.keys(live.info).length ? live.info : (saved && typeof saved === 'object' ? saved : {});
    return { id: r.id, name: r.name, role: r.role, lastSeen: r.last_seen_at ? r.last_seen_at.getTime() : null, ...live, info };
  });
}

/** What goes in the plugin's config.yml under relay:. */
export function relayConfig(base: string, token: string) {
  return { url: base.replace(/^http/, 'ws'), token };
}
