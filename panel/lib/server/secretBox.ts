import { createCipheriv, createDecipheriv, hkdfSync, randomBytes } from 'node:crypto';

// Secrets the panel must be able to use again (a user's Anthropic API key), sealed with AES-256-GCM. Unlike the
// panel's own tokens, they cannot be derived from a seed, so the database holds the ciphertext and the key stays in
// the environment.

const VERSION = 'v1';

function keyOf(secret: string): Buffer {
  return Buffer.from(hkdfSync('sha256', secret, '', 'aibuilder sealed secret v1', 32));
}

/** `plain` sealed under `secret`: "v1.<iv>.<ciphertext>.<tag>", each part base64url. */
export function seal(plain: string, secret: string): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', keyOf(secret), iv);
  const text = Buffer.concat([cipher.update(plain, 'utf8'), cipher.final()]);
  return [VERSION, iv.toString('base64url'), text.toString('base64url'), cipher.getAuthTag().toString('base64url')].join('.');
}

/** What `seal` sealed, or null when the secret changed or the text was altered. */
export function unseal(sealed: string, secret: string): string | null {
  const [version, iv, text, tag] = sealed.split('.');
  if (version !== VERSION || !iv || !text || !tag) return null;
  try {
    const decipher = createDecipheriv('aes-256-gcm', keyOf(secret), Buffer.from(iv, 'base64url'));
    decipher.setAuthTag(Buffer.from(tag, 'base64url'));
    return Buffer.concat([decipher.update(Buffer.from(text, 'base64url')), decipher.final()]).toString('utf8');
  } catch {
    return null;
  }
}

/** The key sealed secrets are sealed under. */
export function keySecret(): string {
  const s = process.env.KEY_SECRET || process.env.AUTH0_SECRET;
  if (!s) throw new Error('set AUTH0_SECRET (or KEY_SECRET)');
  return s;
}
