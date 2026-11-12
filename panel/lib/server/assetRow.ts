import { FACINGS, type AssetRow, type Facing } from './assets.ts';

// The assets table's columns and how a row reads as an asset, apart from assetStore.ts so the tests can import it
// without MySQL.

export interface Db {
  id: string; user_id: number; name: string; description: string; tags: unknown;
  size_x: number; size_y: number; size_z: number; block_count: number; mc_version: string | null; bytes: number | string;
  created_at: Date; updated_at: Date;
  anchor_x: number | null; anchor_y: number | null; anchor_z: number | null; facing: string | null;
}

export const COLUMNS = 'id, user_id, name, description, tags, size_x, size_y, size_z, block_count, mc_version, bytes, created_at, updated_at, '
  + 'anchor_x, anchor_y, anchor_z, facing';

/** A MySQL row as an asset. An anchor needs all three cells; a facing this panel does not know reads as none. */
export function row(r: Db): AssetRow {
  const tags = typeof r.tags === 'string' ? JSON.parse(r.tags) : r.tags;
  const anchor = r.anchor_x === null || r.anchor_y === null || r.anchor_z === null ? null
    : [Number(r.anchor_x), Number(r.anchor_y), Number(r.anchor_z)] as [number, number, number];
  return {
    id: r.id, owner: r.user_id, name: r.name, description: r.description, tags: Array.isArray(tags) ? tags : [],
    size: [r.size_x, r.size_y, r.size_z], blocks: r.block_count, mc: r.mc_version, bytes: Number(r.bytes),
    created: r.created_at.getTime(), updated: r.updated_at.getTime(),
    anchor, facing: (FACINGS as readonly string[]).includes(r.facing ?? '') ? r.facing as Facing : null,
  };
}

/** The anchor_x, anchor_y, anchor_z and facing values to write. */
export const anchorColumns = (a: Pick<AssetRow, 'anchor' | 'facing'>) =>
  [a.anchor?.[0] ?? null, a.anchor?.[1] ?? null, a.anchor?.[2] ?? null, a.facing ?? null];
