import { roleOf } from './access';
import { exec, one, query, transaction } from './db';
import { LinkError, type LinkDeps, type LinkStore, type MinecraftLink } from './minecraftLinks';

// The MySQL rows of Minecraft account links (migration 009), for the rules in minecraftLinks.ts. It must not import
// anything that imports the hub (route.ts, assetStore.ts): hub.ts loads it directly.

export const linkStore: LinkStore = {
  async issueCode(user, hash, created, expires, since, check) {
    await transaction(async c => {
      // The user's row serialises one user's codes, so two requests at once cannot both pass the limit.
      await query('SELECT id FROM users WHERE id = ? FOR UPDATE', [user], c);
      // Every code made before the window has expired (CODE_TTL_MS = CODE_WINDOW_MS): the table stays small.
      await exec('DELETE FROM minecraft_link_codes WHERE created_at < ?', [since], c);
      const r = await one<{ n: number }>('SELECT COUNT(*) AS n FROM minecraft_link_codes WHERE user_id = ? AND created_at >= ?', [user, since], c);
      check(Number(r?.n ?? 0));
      await exec('UPDATE minecraft_link_codes SET used_at = ? WHERE user_id = ? AND used_at IS NULL', [created, user], c);
      await exec('INSERT INTO minecraft_link_codes (code_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)',
        [hash, user, created, expires], c);
    });
  },
  async consumeCode(hash, now) {
    const spent = await exec('UPDATE minecraft_link_codes SET used_at = ? WHERE code_hash = ? AND used_at IS NULL AND expires_at > ?',
      [now, hash, now]);
    if (spent.affectedRows !== 1) return null;
    const r = await one<{ user_id: number }>('SELECT user_id FROM minecraft_link_codes WHERE code_hash = ?', [hash]);
    return r?.user_id ?? null;
  },
  async links(user) {
    const rows = await query<{ uuid: string; player_name: string | null; linked_at: Date }>(
      'SELECT uuid, player_name, linked_at FROM minecraft_links WHERE user_id = ? ORDER BY linked_at, uuid', [user]);
    return rows.map((r): MinecraftLink => ({ uuid: r.uuid, name: r.player_name, linked: r.linked_at.getTime() }));
  },
  async userOf(uuid) {
    const r = await one<{ user_id: number }>('SELECT user_id FROM minecraft_links WHERE uuid = ?', [uuid]);
    return r?.user_id ?? null;
  },
  async link(uuid, user, name, at, check) {
    return transaction(async c => {
      await query('SELECT id FROM users WHERE id = ? FOR UPDATE', [user], c);
      const owner = await one<{ user_id: number }>('SELECT user_id FROM minecraft_links WHERE uuid = ? FOR UPDATE', [uuid], c);
      const count = await one<{ n: number }>('SELECT COUNT(*) AS n FROM minecraft_links WHERE user_id = ? AND uuid <> ?', [user, uuid], c);
      check(Number(count?.n ?? 0), owner?.user_id ?? null);
      if (owner) {
        if (name) await exec('UPDATE minecraft_links SET player_name = ? WHERE uuid = ?', [name, uuid], c);
        return false;
      }
      try {
        await exec('INSERT INTO minecraft_links (uuid, user_id, player_name, linked_at) VALUES (?, ?, ?, ?)', [uuid, user, name, at], c);
      } catch (e) {
        // Another account linked it between the check and the insert.
        if ((e as { code?: string })?.code === 'ER_DUP_ENTRY') throw new LinkError(409, 'this Minecraft account is linked to another panel account');
        throw e;
      }
      return true;
    });
  },
  async unlink(uuid, user) {
    return (await exec('DELETE FROM minecraft_links WHERE uuid = ? AND user_id = ?', [uuid, user])).affectedRows > 0;
  },
  async rename(uuid, name) {
    await exec('UPDATE minecraft_links SET player_name = ? WHERE uuid = ? AND (player_name IS NULL OR player_name <> ?)', [name, uuid, name]);
  },
};

/** The panel page where a user makes a code, as a player should be told it. */
export function linkPage(): string {
  const base = (process.env.APP_BASE_URL || '').replace(/\/+$/, '');
  return base ? `${base}/dashboard#/tokens` : "the panel's Access tokens page";
}

export function linkDeps(): LinkDeps {
  return { store: linkStore, roleOf, clock: Date.now, page: linkPage() };
}

/** Runs a route's link work and answers with what it returns (nothing as 204); LinkErrors answer with their status. */
export async function linkAnswer(work: () => Promise<unknown>, status = 200): Promise<Response> {
  try {
    const out = await work();
    return out === undefined ? new Response(null, { status: 204 }) : Response.json(out, { status });
  } catch (e) {
    if (e instanceof LinkError) return Response.json({ error: e.message }, { status: e.status });
    throw e;
  }
}
