/**
 * An asset's anchor on the panel: parsing what is typed, the corner that puts the anchor at a spot, and which way a
 * facing points (for the 3D view's arrow). Placing from the panel never turns an asset: the draft import pastes it
 * as saved, so a corner from an anchor is a plain subtraction. Claude's `paste` with `facing` turns it on the server
 * (common/Anchor.java).
 */
import type { Facing } from './types';

export type Cell = [number, number, number];

/** Three whole numbers typed as "1 0 2" or "1, 0, 2", negatives allowed, or null. */
export function parsePosition(text: string): Cell | null {
  const t = text.trim();
  if (!t) return null;
  const v = t.split(/[\s,]+/).map(Number);
  return v.length === 3 && v.every(Number.isInteger) ? v as Cell : null;
}

/** The anchor typed as "1 0 2" (null when empty), or undefined when it is not three whole numbers of 0 or more. */
export function parseAnchor(text: string): Cell | null | undefined {
  if (!text.trim()) return null;
  const v = parsePosition(text);
  return v && v.every(n => n >= 0) ? v : undefined;
}

/** Whether an anchor lies in an asset of `size` (0 0 0 to size − 1). */
export const anchorInside = (anchor: Cell, size: readonly number[]) => anchor.every((n, i) => n >= 0 && n < size[i]);

/** The lowest corner that puts the asset's `anchor` cell at `at` (the asset as saved, not turned). */
export const cornerFromAnchor = (at: Cell, anchor: Cell): Cell => [at[0] - anchor[0], at[1] - anchor[1], at[2] - anchor[2]];

/** One block the way a facing points, in Minecraft axes: +x east, +y up, +z south. */
export const FACING_STEP: Record<Facing, Cell> = {
  north: [0, 0, -1], south: [0, 0, 1], east: [1, 0, 0], west: [-1, 0, 0], up: [0, 1, 0], down: [0, -1, 0],
};
