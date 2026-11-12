import { gunzipSync, gzipSync } from 'node:zlib';
import { MAX_UPLOAD_BYTES } from '../schemUpload.ts';
import {
  AssetError, checkDescription, checkName, checkTags, MAX_REGION_BYTES, MAX_REGION_VOLUME, saveAssetData,
  type Asset, type Deps, type StateRuns,
} from './assets.ts';
import type { Json } from './hub.ts';

// Uploading a WorldEdit `.schem` (Sponge schematic, versions 2 and 3) into the asset library. The file comes from a
// browser and is read as hostile: every length is checked against what is left before anything is allocated from it,
// and only the fields a schematic's blocks need are kept. Block entities, entities and biomes are skipped, as saved
// blueprints leave them out. No MySQL, R2 or relay here; the asset is saved through saveAssetData like any other.

/** The most a file may unpack to. */
export const MAX_NBT_BYTES = 64 * 1024 * 1024;
/** Most entries in a schematic's palette; StateArrays.java refuses more. */
export const MAX_PALETTE = 65536;
/** Deepest nesting of lists and compounds. A schematic's blocks are 3 deep; chest contents in block entities more. */
export const MAX_DEPTH = 64;
/** Longest block state, with its properties. */
export const MAX_STATE_LENGTH = 256;
/** All palette states that are used, in characters; StateArrays.java refuses more. */
const MAX_PALETTE_CHARS = 4 * 1024 * 1024;
/** Room for the form's other fields and part headers around the file. */
const FORM_OVERHEAD = 64 * 1024;

/**
 * Minecraft release versions by data version, from 1.13 (the first with block states, which Sponge needs) on. A data
 * version not listed here (a snapshot, or a release newer than the table) is stored as unknown, never guessed.
 */
export const DATA_VERSIONS: Readonly<Record<number, string>> = {
  1519: '1.13', 1628: '1.13.1', 1631: '1.13.2',
  1952: '1.14', 1957: '1.14.1', 1963: '1.14.2', 1968: '1.14.3', 1976: '1.14.4',
  2225: '1.15', 2227: '1.15.1', 2230: '1.15.2',
  2566: '1.16', 2567: '1.16.1', 2578: '1.16.2', 2580: '1.16.3', 2584: '1.16.4', 2586: '1.16.5',
  2724: '1.17', 2730: '1.17.1',
  2860: '1.18', 2865: '1.18.1', 2975: '1.18.2',
  3105: '1.19', 3117: '1.19.1', 3120: '1.19.2', 3218: '1.19.3', 3337: '1.19.4',
  3463: '1.20', 3465: '1.20.1', 3578: '1.20.2', 3698: '1.20.3', 3700: '1.20.4', 3837: '1.20.5', 3839: '1.20.6',
  3953: '1.21', 3955: '1.21.1', 4080: '1.21.2', 4082: '1.21.3', 4189: '1.21.4', 4325: '1.21.5', 4435: '1.21.6',
  4438: '1.21.7', 4440: '1.21.8', 4554: '1.21.9', 4556: '1.21.10',
  5023: '26.3',
};

export const mcVersion = (dataVersion: number | null): string | null =>
  dataVersion !== null && Object.hasOwn(DATA_VERSIONS, dataVersion) ? DATA_VERSIONS[dataVersion] : null;

const bad = (message: string) => new AssetError(400, message);

// ---- NBT ----

const END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6, BYTE_ARRAY = 7, STRING = 8, LIST = 9,
  COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;
/** Payload bytes of the fixed-size types, BYTE to DOUBLE. */
const FIXED = [0, 1, 2, 4, 8, 4, 8];
/** The fewest bytes one list element of each type takes, so a list's count is checked before it is read. */
const MIN_SIZE = [0, 1, 2, 4, 8, 4, 8, 4, 2, 5, 1, 4, 4];

type Value = number | bigint | string | Uint8Array | Fields;
interface Tag { type: number; value?: Value }
type Fields = Map<string, Tag>;
/**
 * The fields of a compound to keep, by name: a nested Want for a compound, 'value' for a number, string or byte array
 * (a view, not a copy), 'mark' to note only that it is there and its type, 'scalars' for a compound with any field
 * names whose numbers and strings are kept (a palette). Every other field is checked and skipped, so nothing is
 * allocated for it.
 */
interface Want { [field: string]: Want | 'value' | 'mark' | 'scalars' }

/** Reads an uncompressed NBT file whose root is a compound; refuses anything after it. */
function readNbt(raw: Uint8Array, want: Want): Fields {
  const view = new DataView(raw.buffer, raw.byteOffset, raw.byteLength);
  const utf8 = new TextDecoder();
  let at = 0;

  const need = (n: number) => {
    if (n > raw.byteLength - at) throw bad('the file ends in the middle of its data');
  };
  /** Moves past `n` bytes; a function, because `at += f()` would read `at` before f() moves it. */
  const jump = (n: number) => { at += n; };
  const u8 = () => { need(1); return raw[at++]; };
  const tagType = () => {
    const t = u8();
    if (t > LONG_ARRAY) throw bad(`the file has an unknown NBT tag type ${t}`);
    return t;
  };
  const strLength = () => { need(2); const n = view.getUint16(at); at += 2; need(n); return n; };
  const str = () => { const n = strLength(); const s = utf8.decode(raw.subarray(at, at + n)); at += n; return s; };
  /** An array or list length, checked against the bytes left before anything uses it. */
  const count = (elementSize: number) => {
    need(4);
    const n = view.getInt32(at);
    at += 4;
    if (n < 0) throw bad('the file has a negative length');
    if (n * elementSize > raw.byteLength - at) throw bad('the file has an array longer than the file');
    return n;
  };
  const nest = (depth: number) => {
    if (depth >= MAX_DEPTH) throw bad(`the file nests its data more than ${MAX_DEPTH} deep`);
  };

  function skip(type: number, depth: number): void {
    if (type >= BYTE && type <= DOUBLE) { need(FIXED[type]); at += FIXED[type]; return; }
    switch (type) {
      case BYTE_ARRAY: jump(count(1)); return;
      case INT_ARRAY: jump(count(4) * 4); return;
      case LONG_ARRAY: jump(count(8) * 8); return;
      case STRING: jump(strLength()); return;
      case LIST: {
        nest(depth);
        const t = tagType();
        if (t === END) { if (count(0) !== 0) throw bad('the file has a list of nothing that is not empty'); return; }
        const n = count(MIN_SIZE[t]);
        if (t <= DOUBLE) { at += n * FIXED[t]; return; }
        for (let i = 0; i < n; i++) skip(t, depth + 1);
        return;
      }
      case COMPOUND: {
        nest(depth);
        for (let t = tagType(); t !== END; t = tagType()) { jump(strLength()); skip(t, depth + 1); }
        return;
      }
      default: throw bad('the file has a misplaced end tag');
    }
  }

  function read(type: number, depth: number, want: Want[string]): Tag {
    if (want === 'mark') { skip(type, depth); return { type }; }
    if (type === COMPOUND && want !== 'value') return { type, value: compound(depth, want) };
    let value: Value;
    switch (type) {
      case BYTE: need(1); value = view.getInt8(at); at += 1; break;
      case SHORT: need(2); value = view.getInt16(at); at += 2; break;
      case INT: need(4); value = view.getInt32(at); at += 4; break;
      case LONG: need(8); value = view.getBigInt64(at); at += 8; break;
      case FLOAT: need(4); value = view.getFloat32(at); at += 4; break;
      case DOUBLE: need(8); value = view.getFloat64(at); at += 8; break;
      case STRING: value = str(); break;
      case BYTE_ARRAY: { const n = count(1); value = raw.subarray(at, at + n); at += n; break; }
      default: skip(type, depth); return { type };
    }
    return { type, value };
  }

  function compound(depth: number, want: Want | 'scalars'): Fields {
    nest(depth);
    const fields: Fields = new Map();
    for (let type = tagType(); type !== END; type = tagType()) {
      const name = str();
      const w = want === 'scalars' ? (type <= STRING && type !== BYTE_ARRAY ? 'value' : 'mark')
        : Object.hasOwn(want, name) ? want[name] : undefined;
      if (!w) { skip(type, depth + 1); continue; }
      if (fields.has(name)) throw bad(`the file repeats the field '${cut(name)}'`);
      if (fields.size >= MAX_PALETTE) throw bad(`the file has a palette of more than ${MAX_PALETTE.toLocaleString('en-US')} entries`);
      fields.set(name, read(type, depth + 1, w));
    }
    return fields;
  }

  if (tagType() !== COMPOUND) throw bad('the file is not NBT data');
  jump(strLength());
  const root = compound(0, want);
  if (at !== raw.byteLength) throw bad('the file has data after its end');
  return root;
}

const cut = (s: string) => s.length > 60 ? s.slice(0, 60) + '…' : s;

// ---- Sponge schematics ----

const SCHEMATIC: Want = {
  Version: 'value', DataVersion: 'value', Width: 'value', Height: 'value', Length: 'value',
  // Version 2 keeps the blocks at the top, version 3 in Blocks. An MCEdit .schematic has Blocks as a byte array.
  Palette: 'scalars', BlockData: 'value', Blocks: { Palette: 'scalars', Data: 'value' }, Materials: 'mark',
};
/** Version 3 wraps everything in a Schematic compound; version 2 has it at the root. Litematica has Regions. */
const ROOT: Want = { ...SCHEMATIC, Schematic: SCHEMATIC, Regions: 'mark' };

/** A block state the rest of the panel and the plugin read: lowercase resource name, optional [key=value,...]. */
const STATE = /^(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+(?:\[[a-z0-9_]+=[a-z0-9_.-]+(?:,[a-z0-9_]+=[a-z0-9_.-]+)*\])?$/;
const AIR = new Set(['air', 'cave_air', 'void_air']);

/** A schematic's blocks as StateArrays keeps them, with its size and Minecraft version. */
export interface Schematic extends StateRuns {
  version: 2 | 3;
  dataVersion: number | null;
  mc: string | null;
  /** Width (x), height (y) and length (z). */
  size: [number, number, number];
}

/**
 * Reads a gzipped Sponge schematic (version 2 or 3) into runs of states in y, z, x order, the same order and
 * orientation as a region saved from a server. Its Offset is ignored: 0 0 0 is the lowest corner.
 */
export function parseSchematic(file: Uint8Array): Schematic {
  if (file.byteLength < 2 || file[0] !== 0x1f || file[1] !== 0x8b) {
    throw bad('this file is not gzipped NBT, so it is not a WorldEdit .schem file');
  }
  let raw: Buffer;
  try {
    raw = gunzipSync(file, { maxOutputLength: MAX_NBT_BYTES });
  } catch (e) {
    if ((e as { code?: string }).code === 'ERR_BUFFER_TOO_LARGE') {
      throw bad(`this file unpacks to more than ${MAX_NBT_BYTES / 1024 ** 2} MB, more than an asset can hold`);
    }
    throw bad('this file is damaged: its gzip data does not unpack');
  }
  const root = readNbt(raw, ROOT);
  if (root.has('Regions')) throw bad('this is a Litematica file, which is not supported; save the build with WorldEdit as a .schem file');
  const wrapped = root.get('Schematic');
  const s = wrapped?.type === COMPOUND ? wrapped.value as Fields : root;

  const versionTag = s.get('Version');
  if (!versionTag) {
    if (s.has('Materials') || s.get('Blocks')?.type === BYTE_ARRAY) {
      throw bad('this is an old MCEdit .schematic file, which is not supported; save the build with WorldEdit 7 as a .schem file');
    }
    throw bad('this file is not a Sponge schematic: it has no Version');
  }
  if (versionTag.type !== INT) throw bad('the schematic\'s Version is not a whole number');
  const version = versionTag.value as number;
  if (version === 1) throw bad('this is a version 1 Sponge schematic, which is not supported; save it again with WorldEdit 7.2 or newer');
  if (version !== 2 && version !== 3) throw bad(`Sponge schematic version ${version} is not supported, only versions 2 and 3`);

  const dataTag = s.get('DataVersion');
  const dataVersion = dataTag?.type === INT ? dataTag.value as number : null;
  const size = (['Width', 'Height', 'Length'] as const).map(field => {
    const t = s.get(field);
    // Sponge stores the sizes as unsigned shorts.
    const n = t?.type === SHORT ? (t.value as number) & 0xffff : t?.type === INT ? t.value as number : NaN;
    if (!Number.isSafeInteger(n) || n < 1) throw bad(`the schematic's ${field} is missing or not a positive whole number`);
    return n;
  }) as [number, number, number];
  const volume = size[0] * size[1] * size[2];
  if (volume > MAX_REGION_VOLUME) {
    throw bad(`this schematic is ${size.join(' × ')} = ${volume.toLocaleString('en-US')} blocks; an asset is at most `
      + `${MAX_REGION_VOLUME.toLocaleString('en-US')} blocks`);
  }

  let paletteTag: Tag | undefined, dataTag2: Tag | undefined;
  if (version === 2) {
    paletteTag = s.get('Palette');
    dataTag2 = s.get('BlockData');
  } else {
    const blocks = s.get('Blocks');
    if (blocks?.type !== COMPOUND) throw bad('the schematic has no blocks');
    paletteTag = (blocks.value as Fields).get('Palette');
    dataTag2 = (blocks.value as Fields).get('Data');
  }
  if (paletteTag?.type !== COMPOUND) throw bad('the schematic has no block palette');
  if (dataTag2?.type !== BYTE_ARRAY) throw bad('the schematic has no block data');

  // Palette index -> state, with gaps where the palette has no entry.
  const byIndex: (string | undefined)[] = [];
  for (const [name, t] of paletteTag.value as Fields) {
    if (t.type !== INT) throw bad(`the palette entry '${cut(name)}' is not a whole number`);
    const i = t.value as number;
    if (i < 0 || i >= MAX_PALETTE) throw bad(`the palette entry '${cut(name)}' has index ${i}, outside 0-${MAX_PALETTE - 1}`);
    if (byIndex[i] !== undefined) throw bad(`the palette gives index ${i} to more than one block`);
    byIndex[i] = normalize(name);
  }
  if (!byIndex.length) throw bad('the schematic has an empty block palette');

  const data = dataTag2.value as Uint8Array;
  const palette: string[] = [];
  const paletteOf = new Map<string, number>();
  const remap = new Int32Array(byIndex.length).fill(-1);
  const runs: [number, number][] = [];
  let at = 0, chars = 0;
  for (let cell = 0; cell < volume; cell++) {
    // Little-endian base-128 varint, at most 5 bytes for an int.
    let index = 0, shift = 0, b: number;
    do {
      if (at >= data.length) throw bad(`the block data ends after ${cell.toLocaleString('en-US')} of ${volume.toLocaleString('en-US')} blocks`);
      if (shift > 28) throw bad('the block data has a palette index longer than 5 bytes');
      b = data[at++];
      index += (b & 0x7f) * 2 ** shift;
      shift += 7;
    } while (b & 0x80);
    let p = index < remap.length ? remap[index] : -1;
    if (p < 0) {
      const state = byIndex[index];
      if (state === undefined) throw bad(`block ${cell.toLocaleString('en-US')} uses palette index ${index}, which the palette does not have`);
      p = paletteOf.get(state) ?? -1;
      if (p < 0) {
        p = palette.push(state) - 1;
        paletteOf.set(state, p);
        chars += state.length;
        if (chars > MAX_PALETTE_CHARS) throw bad('the schematic\'s block states are too long in all');
      }
      remap[index] = p;
    }
    const last = runs[runs.length - 1];
    if (last && last[0] === p) last[1]++;
    else runs.push([p, 1]);
  }
  if (at !== data.length) throw bad(`the block data is longer than ${size.join(' × ')} blocks`);
  return { version, dataVersion, mc: mcVersion(dataVersion), size, length: volume, palette, runs };
}

/** "minecraft:oak_stairs[facing=east]" as the rest of the system writes it: "oak_stairs[facing=east]". */
function normalize(name: string): string {
  const state = name.startsWith('minecraft:') ? name.slice('minecraft:'.length) : name;
  if (state.length > MAX_STATE_LENGTH || !STATE.test(state)) throw bad(`the palette entry '${cut(name)}' is not a block state`);
  return AIR.has(state) ? 'air' : state;
}

/** What StateArrays.java encode writes (gzipped), the format of an asset's region.bin. */
export function encodeStates(s: StateRuns): Buffer {
  const header = Buffer.alloc(12);
  header.writeInt32BE(0x53413031, 0);
  header.writeInt32BE(s.length, 4);
  header.writeInt32BE(s.palette.length, 8);
  const parts: Buffer[] = [header];
  for (const state of s.palette) {
    // Block states here are ASCII, where Java's modified UTF-8 and UTF-8 agree.
    const bytes = Buffer.from(state, 'utf8');
    const len = Buffer.alloc(2);
    len.writeUInt16BE(bytes.length);
    parts.push(len, bytes);
  }
  const runs = Buffer.alloc(s.runs.length * 8);
  s.runs.forEach(([p, run], i) => { runs.writeInt32BE(p, i * 8); runs.writeInt32BE(run, i * 8 + 4); });
  parts.push(runs);
  return gzipSync(Buffer.concat(parts));
}

// ---- the upload ----

const tooLarge = () => new AssetError(413, `the file is larger than ${MAX_UPLOAD_BYTES / 1024 ** 2} MB; an uploaded schematic is at most that`);

/**
 * Reads a multipart form with `file` (the .schem), `name`, `description` and `tags` (comma-separated), refusing more
 * than MAX_UPLOAD_BYTES of file: by Content-Length before reading, while reading, and by the file's own size after.
 */
export async function readUpload(req: Request): Promise<{ fields: Json; file: Uint8Array }> {
  const type = req.headers.get('content-type') ?? '';
  if (!/^multipart\/form-data\s*;/i.test(type)) throw new AssetError(415, 'send the file as multipart/form-data');
  const limit = MAX_UPLOAD_BYTES + FORM_OVERHEAD;
  if (Number(req.headers.get('content-length')) > limit) throw tooLarge();
  const chunks: Uint8Array[] = [];
  let total = 0;
  const reader = req.body?.getReader();
  for (let r = await reader?.read(); r && !r.done; r = await reader!.read()) {
    total += r.value.byteLength;
    if (total > limit) {
      await reader!.cancel().catch(() => {});
      throw tooLarge();
    }
    chunks.push(r.value);
  }
  let form: FormData;
  try {
    form = await new Response(Buffer.concat(chunks), { headers: { 'content-type': type } }).formData();
  } catch {
    throw bad('the upload is not a valid form');
  }
  const file = form.get('file');
  if (!(file instanceof Blob)) throw bad('choose a .schem file');
  if (file.size > MAX_UPLOAD_BYTES) throw tooLarge();
  if (!file.size) throw bad('the file is empty');
  const text = (k: string) => { const v = form.get(k); return typeof v === 'string' ? v : undefined; };
  return {
    fields: { name: text('name'), description: text('description'), tags: (text('tags') ?? '').split(',') },
    file: new Uint8Array(await file.arrayBuffer()),
  };
}

/**
 * Saves an uploaded schematic to the user's library: {name, description?, tags?} and the file's bytes. Quota, the name,
 * ownership and the 3D view's blocks go through saveAssetData, as for an asset saved from a server.
 */
export async function uploadSchematic(deps: Deps, userId: number, fields: Json, file: Uint8Array): Promise<Asset> {
  const name = checkName(fields.name), description = checkDescription(fields.description), tags = checkTags(fields.tags);
  if (file.byteLength > MAX_UPLOAD_BYTES) throw tooLarge();
  const schem = parseSchematic(file);
  const region = encodeStates(schem);
  if (region.byteLength > MAX_REGION_BYTES) {
    throw bad(`this schematic's blocks take ${(region.byteLength / 1024 ** 2).toFixed(2)} MB packed; an asset is at most `
      + `${MAX_REGION_BYTES / 1024 ** 2} MB. Upload a smaller one.`);
  }
  return saveAssetData(deps, userId, { name, description, tags, size: schem.size }, new Uint8Array(region), schem.mc);
}
