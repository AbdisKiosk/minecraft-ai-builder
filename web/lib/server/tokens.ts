import { createHash, randomBytes } from 'node:crypto';

/** Server tokens (aibs_) let a plugin connect; client tokens (aibc_) let Claude Code build; invites (aibi_) join. */
export function newToken(prefix: 'aibs_' | 'aibc_' | 'aibi_'): string {
  return prefix + randomBytes(32).toString('base64url');
}

export function sha256(token: string): string {
  return createHash('sha256').update(token, 'utf8').digest('hex');
}

const ALPHABET = 'abcdefghijkmnpqrstuvwxyz23456789';

/** A short, URL-safe id without look-alike characters, e.g. for /s/<id>. */
export function shortId(length = 10): string {
  const bytes = randomBytes(length);
  let out = '';
  for (const b of bytes) out += ALPHABET[b % ALPHABET.length];
  return out;
}

export function bearer(header: string | null | undefined): string | null {
  if (!header) return null;
  const m = /^Bearer\s+(\S+)$/i.exec(header.trim());
  return m ? m[1] : null;
}
