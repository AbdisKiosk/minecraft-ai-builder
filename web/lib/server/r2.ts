import { DeleteObjectsCommand, GetObjectCommand, HeadObjectCommand, ListObjectsV2Command, PutObjectCommand, S3Client } from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';

// Cloudflare R2 through its S3 API. One client per process, shared by server.ts and Next's route bundles.
const g = globalThis as { __aibR2?: S3Client };

function env(name: string): string {
  const v = process.env[name];
  if (!v) throw new Error(`${name} is not set`);
  return v;
}

export function r2(): S3Client {
  if (!g.__aibR2) {
    g.__aibR2 = new S3Client({
      region: 'auto',
      endpoint: `https://${env('R2_ACCOUNT_ID')}.r2.cloudflarestorage.com`,
      credentials: { accessKeyId: env('R2_ACCESS_KEY_ID'), secretAccessKey: env('R2_SECRET_ACCESS_KEY') },
    });
  }
  return g.__aibR2;
}

const bucket = () => env('R2_BUCKET');

const missing = (e: unknown) => {
  const s = (e as { name?: string; $metadata?: { httpStatusCode?: number } }) ?? {};
  return s.name === 'NoSuchKey' || s.name === 'NotFound' || s.$metadata?.httpStatusCode === 404;
};

/** The object's size, or null when there is none. */
export async function sizeOf(key: string): Promise<number | null> {
  try {
    const r = await r2().send(new HeadObjectCommand({ Bucket: bucket(), Key: key }));
    return r.ContentLength ?? 0;
  } catch (e) {
    if (missing(e)) return null;
    throw e;
  }
}

/** The object as a web stream with its size, or null when there is none. */
export async function read(key: string): Promise<{ body: ReadableStream<Uint8Array>; size: number } | null> {
  try {
    const r = await r2().send(new GetObjectCommand({ Bucket: bucket(), Key: key }));
    if (!r.Body) return null;
    return { body: r.Body.transformToWebStream() as ReadableStream<Uint8Array>, size: r.ContentLength ?? 0 };
  } catch (e) {
    if (missing(e)) return null;
    throw e;
  }
}

/** The whole object in memory, or null when there is none. For small objects only. */
export async function readBytes(key: string): Promise<Uint8Array | null> {
  try {
    const r = await r2().send(new GetObjectCommand({ Bucket: bucket(), Key: key }));
    return r.Body ? await r.Body.transformToByteArray() : null;
  } catch (e) {
    if (missing(e)) return null;
    throw e;
  }
}

export async function write(key: string, body: Uint8Array, contentType: string): Promise<void> {
  await r2().send(new PutObjectCommand({ Bucket: bucket(), Key: key, Body: body, ContentType: contentType }));
}

/** A URL the holder can PUT exactly `size` bytes to at `key` within `seconds`, without our R2 keys. */
export async function uploadUrl(key: string, size: number, contentType: string, seconds = 900): Promise<string> {
  return getSignedUrl(r2(), new PutObjectCommand({ Bucket: bucket(), Key: key, ContentType: contentType, ContentLength: size }),
    { expiresIn: seconds, signableHeaders: new Set(['content-type', 'content-length']) });
}

/** Deletes up to a thousand keys at a time. */
export async function remove(keys: string[]): Promise<void> {
  for (let i = 0; i < keys.length; i += 1000) {
    const batch = keys.slice(i, i + 1000);
    await r2().send(new DeleteObjectsCommand({ Bucket: bucket(), Delete: { Objects: batch.map(Key => ({ Key })), Quiet: true } }));
  }
}

/** Deletes every object whose key starts with `prefix`. */
export async function removePrefix(prefix: string): Promise<number> {
  let token: string | undefined;
  let n = 0;
  do {
    const page = await r2().send(new ListObjectsV2Command({ Bucket: bucket(), Prefix: prefix, ContinuationToken: token }));
    const keys = (page.Contents ?? []).map(o => o.Key!).filter(Boolean);
    if (keys.length) await remove(keys);
    n += keys.length;
    token = page.IsTruncated ? page.NextContinuationToken : undefined;
  } while (token);
  return n;
}

/** Whether R2 is configured; without it the app keeps no archive and the plugins keep their history themselves. */
export function configured(): boolean {
  return ['R2_ACCOUNT_ID', 'R2_ACCESS_KEY_ID', 'R2_SECRET_ACCESS_KEY', 'R2_BUCKET'].every(k => !!process.env[k]);
}
