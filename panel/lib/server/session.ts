import type { UserRow } from './access';
import { auth0 } from './auth0';
import { exec, one } from './db';

// Signed-in users by Auth0 subject, so a page view doesn't write to the database every time.
const cache = new Map<string, { user: UserRow; until: number }>();
const CACHE_MS = 60_000;

/** The signed-in user, made or updated from the Auth0 profile; null when nobody is signed in. */
export async function currentUser(): Promise<UserRow | null> {
  const session = await auth0.getSession();
  if (!session) return null;
  const u = session.user;
  const hit = cache.get(u.sub);
  if (hit && hit.until > Date.now()) return hit.user;
  const email = typeof u.email === 'string' ? u.email : null;
  const name = typeof u.name === 'string' ? u.name.slice(0, 191) : null;
  const picture = typeof u.picture === 'string' && u.picture.length <= 1024 ? u.picture : null;
  await exec(
    `INSERT INTO users (sub, email, name, picture) VALUES (?, ?, ?, ?)
     ON DUPLICATE KEY UPDATE email = VALUES(email), name = VALUES(name), picture = VALUES(picture)`,
    [u.sub, email, name, picture]);
  const user = await one<UserRow>('SELECT id, sub, email, name, picture FROM users WHERE sub = ?', [u.sub]);
  if (!user) throw new Error('user row missing after upsert');
  cache.set(u.sub, { user, until: Date.now() + CACHE_MS });
  return user;
}
