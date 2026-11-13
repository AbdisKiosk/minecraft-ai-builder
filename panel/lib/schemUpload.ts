// What both the upload dialog and the upload route (lib/server/schem.ts) need to agree on.

/** The largest `.schem` file the Assets page takes, checked in the browser, before the route reads it and after. */
export const MAX_UPLOAD_BYTES = 8 * 1024 * 1024;

/**
 * An asset name from a file name: "My House (2).schem" becomes "my-house-2". Lowercased, every run of characters
 * outside a-z, 0-9, _ and - turned into one "-", cut to 40. Empty when nothing usable is left.
 */
export function nameFromFile(fileName: string): string {
  const base = fileName.split(/[\\/]/).pop() ?? '';
  return base.replace(/\.(schem|schematic|litematic|nbt)$/i, '').toLowerCase()
    .replace(/[^a-z0-9_-]+/g, '-').replace(/-{2,}/g, '-').replace(/^-+|-+$/g, '')
    .slice(0, 40).replace(/-+$/, '');
}
