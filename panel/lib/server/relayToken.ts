import { exec, one } from './db';
import { deriveRelayToken, newSeed, sha256 } from './tokens';

/** The server's current relay token; a server whose token can't be rebuilt gets a new one (which disconnects it). */
export async function currentRelayToken(serverId: string): Promise<{ token: string; replaced: boolean }> {
  const row = await one<{ relay_seed: string | null; token_hash: string }>(
    'SELECT relay_seed, token_hash FROM servers WHERE id = ?', [serverId]);
  if (row?.relay_seed) {
    const token = deriveRelayToken(row.relay_seed, relaySecret());
    if (sha256(token) === row.token_hash) return { token, replaced: false };
  }
  return { token: await replaceRelayToken(serverId), replaced: true };
}

/** Gives the server a new relay token; the old one stops working. */
export async function replaceRelayToken(serverId: string): Promise<string> {
  const seed = newSeed();
  const token = deriveRelayToken(seed, relaySecret());
  await exec('UPDATE servers SET relay_seed = ?, token_hash = ? WHERE id = ?', [seed, sha256(token), serverId]);
  return token;
}

/** The key relay and access tokens are derived from. */
export function relaySecret(): string {
  const s = process.env.RELAY_SECRET || process.env.AUTH0_SECRET;
  if (!s) throw new Error('set AUTH0_SECRET (or RELAY_SECRET)');
  return s;
}
