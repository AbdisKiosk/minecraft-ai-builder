/**
 * Minecraft versions as the plugin reports them ("1.8.8", "1.21.11", "26.3"), compared part by part. Used to warn
 * before placing an asset on a server older than the one it was saved on; the plugin's import decides what fits.
 */

/** The numeric parts of a version, or null for anything else (unknown, a snapshot name). */
export function versionParts(v: string | null | undefined): number[] | null {
  if (typeof v !== 'string' || !/^\d{1,4}(\.\d{1,4}){0,3}$/.test(v.trim())) return null;
  return v.trim().split('.').map(Number);
}

/** Negative when a is older than b, 0 when equal ("1.21" = "1.21.0"), positive when newer; null when either is unknown. */
export function compareVersions(a: string | null | undefined, b: string | null | undefined): number | null {
  const x = versionParts(a), y = versionParts(b);
  if (!x || !y) return null;
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const d = (x[i] ?? 0) - (y[i] ?? 0);
    if (d) return Math.sign(d);
  }
  return 0;
}

/**
 * An advisory for placing an asset saved on {@link asset} onto a server running {@link server}, or null when the server
 * is as new or newer. Only names a server lacks are caught (and refused or left out); a state that exists on both with
 * a different meaning is placed as it is.
 */
export function placeWarning(asset: string | null | undefined, server: string | null | undefined): string | null {
  const after = ' Blocks that server does not have stop the draft unless you leave them out below.';
  if (!versionParts(asset)) return 'The Minecraft version this asset was saved on is not known, so some of its blocks may not exist on that server.' + after;
  if (!versionParts(server)) return `This asset is from Minecraft ${asset}, and that server's version is not known yet.` + after;
  const c = compareVersions(asset, server)!;
  return c > 0 ? `This asset is from Minecraft ${asset}, newer than that server's ${server}.` + after : null;
}

/** The first {@link max} entries of a list and how many were left out, so a long list stays readable. */
export function capped<T>(list: readonly T[] | null | undefined, max: number): { shown: T[]; more: number } {
  const all = list ?? [];
  return { shown: all.slice(0, max), more: Math.max(0, all.length - max) };
}
