// Turns what /blocks sends (DiffImages.encode in the plugin) into Sponge schematics for the 3D view.
import { Buffer } from 'buffer';
import { encode, Int, Short } from '@enginehub/nbt-ts';

// nbt-ts and the schematic viewer use Node's Buffer, which browsers do not have.
globalThis.Buffer ??= Buffer;

export type Kind = 'ADDED' | 'REMOVED' | 'CHANGED';

/** What /blocks sends: run-length palette indexes in y, z, x order, which is also a Sponge schematic's order. */
export interface BlockData {
  box: number[];
  // Older plugins sent colour/shape objects.
  palette: (string | { state: string })[];
  /** Same indexes as palette, with default properties restored for model selection. */
  models?: string[];
  before: [number, number][];
  after: [number, number][];
  changes: [number, Kind][];
}

export interface Diff {
  /** World coordinates of the box's lowest corner. */
  origin: [number, number, number];
  width: number;
  height: number;
  length: number;
  palette: string[];
  models: string[];
  air: number;
  before: Uint32Array;
  after: Uint32Array;
  kinds: Map<number, Kind>;
  /** Height in the box of the highest change. */
  topChange: number;
}

export type Mode = 'before' | 'after' | 'changes';

export interface View {
  mode: Mode;
  /** In changes mode, leave out the blocks that did not change. */
  onlyChanged: boolean;
  /** Highest layer shown, as a height in the box. */
  cut: number;
}

function expand(runs: [number, number][], volume: number): Uint32Array {
  const out = new Uint32Array(volume);
  let i = 0;
  for (const [index, count] of runs) {
    out.fill(index, i, i + count);
    i += count;
  }
  if (i !== volume) throw new Error(`the blocks cover ${i} of ${volume} cells`);
  return out;
}

export function decode(data: BlockData): Diff {
  const [x1, y1, z1, x2, y2, z2] = data.box;
  const width = x2 - x1 + 1, height = y2 - y1 + 1, length = z2 - z1 + 1;
  const volume = width * height * length;
  const palette = data.palette.map(state => typeof state === 'string' ? state : state.state);
  let air = palette.indexOf('air');
  if (air < 0) air = palette.push('air') - 1;
  const models = data.models ? [...data.models] : [...palette];
  if (models.length < palette.length) models.push('air');
  const kinds = new Map(data.changes);
  let topChange = 0;
  for (const index of kinds.keys()) topChange = Math.max(topChange, Math.floor(index / (width * length)));
  return {
    origin: [x1, y1, z1], width, height, length, palette, models, air, kinds, topChange,
    before: expand(data.before, volume),
    after: expand(data.after, volume),
  };
}

/** Palette indexes of what a view shows, cell by cell. Removed blocks show as they were, so they can be marked. */
export function cells(diff: Diff, view: View): Uint32Array {
  const out = (view.mode === 'before' ? diff.before : diff.after).slice();
  const layer = diff.width * diff.length;
  if (view.mode === 'changes') {
    for (const [index, kind] of diff.kinds) if (kind === 'REMOVED') out[index] = diff.before[index];
    if (view.onlyChanged) for (let i = 0; i < out.length; i++) if (!diff.kinds.has(i)) out[i] = diff.air;
  }
  out.fill(diff.air, (view.cut + 1) * layer);
  return out;
}

/** A gzipped Sponge schematic (version 2) of {@code cells}, in base64, which is what the viewer takes. */
export async function toSchematic(diff: Diff, cells: Uint32Array): Promise<string> {
  // Only the states in use, so the viewer loads no models it does not draw.
  const ids = new Map<string, number>();
  const blocks = new Uint8Array(cells.length * 5);
  let n = 0;
  for (const cell of cells) {
    const state = diff.models[cell];
    let id = ids.get(state);
    if (id === undefined) ids.set(state, id = ids.size);
    for (; id > 127; id >>>= 7) blocks[n++] = (id & 127) | 128;
    blocks[n++] = id;
  }
  const palette: Record<string, Int> = {};
  for (const [state, id] of ids) palette[state] = new Int(id);
  const nbt = encode('Schematic', {
    Version: new Int(2),
    Width: new Short(diff.width),
    Height: new Short(diff.height),
    Length: new Short(diff.length),
    PaletteMax: new Int(ids.size),
    Palette: palette,
    // Int8Array is recognised across browser Buffer polyfills (whose constructors may differ between bundles).
    BlockData: new Int8Array(blocks.buffer, 0, n),
  });
  const gzipped = await new Response(new Blob([new Uint8Array(nbt)]).stream().pipeThrough(new CompressionStream('gzip'))).arrayBuffer();
  return Buffer.from(gzipped).toString('base64');
}
