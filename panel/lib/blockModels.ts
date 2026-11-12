import JSZip from 'jszip';

// The 3D viewer picks a block's model by all of its properties, but states saved from a region leave out those that
// equal the block's default (oak_stairs[facing=east] for oak_stairs[facing=east,half=bottom,shape=straight,...]).
// For history the plugin restores them; an asset was saved without them, so the browser fills them in from the
// client jar's blockstates. The defaults below are Minecraft's for nearly every block; a wrong guess only changes
// how the preview draws that block, never what is placed.

/** Property values preferred as the default, by property name, before the general rule. */
const PREFERRED: Record<string, string[]> = {
  axis: ['y'],
  facing: ['north'],
  half: ['bottom', 'lower'],
  type: ['bottom', 'single', 'normal'],
  shape: ['straight', 'north_south'],
  face: ['wall'],
  attachment: ['floor'],
  hinge: ['left'],
  part: ['foot'],
  orientation: ['north_up'],
  thickness: ['tip'],
  vertical_direction: ['up'],
  mode: ['compare'],
  instrument: ['harp'],
};

/** Blocks whose default differs from the rule for their property. */
const BY_TYPE: [RegExp, Record<string, string>][] = [
  [/^hopper$/, { facing: 'down' }],
  [/(^|_)(end_rod|lightning_rod|amethyst_cluster|amethyst_bud|shulker_box)$/, { facing: 'up' }],
  [/^observer$/, { facing: 'south' }],
  [/(^|_)campfire$/, { lit: 'true' }],
  [/_wall$/, { up: 'true' }],
  [/(_mushroom_block|^mushroom_stem)$/, { north: 'true', east: 'true', south: 'true', west: 'true', up: 'true', down: 'true' }],
];

/** The properties a blockstates file mentions, with the values it gives each. */
export function blockProperties(blockstate: unknown): Map<string, string[]> {
  const out = new Map<string, Set<string>>();
  const add = (k: string, v: string) => {
    if (!out.has(k)) out.set(k, new Set());
    for (const one of v.split('|')) out.get(k)!.add(one);
  };
  const b = (blockstate ?? {}) as { variants?: Record<string, unknown>; multipart?: { when?: unknown }[] };
  for (const key of Object.keys(b.variants ?? {})) {
    for (const pair of key.split(',')) {
      const [k, v] = pair.split('=');
      if (k && v !== undefined) add(k, v);
    }
  }
  const when = (w: unknown) => {
    if (!w || typeof w !== 'object') return;
    for (const [k, v] of Object.entries(w as Record<string, unknown>)) {
      if ((k === 'OR' || k === 'AND') && Array.isArray(v)) v.forEach(when);
      else if (typeof v === 'string' || typeof v === 'boolean' || typeof v === 'number') add(k, String(v));
    }
  };
  const fromVariants = new Set(out.keys());
  for (const part of b.multipart ?? []) when(part.when);
  // A multipart file names only the values that add a part (a fence's north=true); the default is often the other one.
  for (const [k, values] of out) {
    if (fromVariants.has(k)) continue;
    if (values.has('true')) values.add('false');
    if (['low', 'tall', 'side', 'up'].some(v => values.has(v))) values.add('none');
  }
  return new Map([...out].map(([k, v]) => [k, [...v]]));
}

/** The value a block most likely has for a property it does not state. */
export function defaultValue(type: string, property: string, values: string[]): string {
  for (const [match, defaults] of BY_TYPE) {
    const v = defaults[property];
    if (v !== undefined && match.test(type) && values.includes(v)) return v;
  }
  const preferred = PREFERRED[property]?.find(v => values.includes(v));
  if (preferred) return preferred;
  if (values.includes('false')) return 'false';
  if (values.includes('none')) return 'none';
  const numbers = values.map(Number).filter(Number.isFinite);
  if (numbers.length === values.length && numbers.length) return String(Math.min(...numbers));
  return values[0];
}

/** `oak_stairs[facing=east]` → type and properties. */
export function parseState(state: string): { type: string; props: Record<string, string> } {
  const open = state.indexOf('[');
  if (open < 0) return { type: state, props: {} };
  const props: Record<string, string> = {};
  for (const pair of state.slice(open + 1, state.lastIndexOf(']')).split(',')) {
    const [k, v] = pair.split('=');
    if (k && v !== undefined) props[k.trim()] = v.trim();
  }
  return { type: state.slice(0, open), props };
}

/** The state with every property its blockstates file uses, sorted by name like the plugin's model states. */
export function completeState(state: string, properties: Map<string, string[]> | undefined): string {
  const { type, props } = parseState(state);
  const all: Record<string, string> = { ...props };
  const plain = type.replace(/^minecraft:/, '');
  for (const [k, values] of properties ?? []) if (!(k in all) && values.length) all[k] = defaultValue(plain, k, values);
  const keys = Object.keys(all).sort();
  return keys.length ? `${type}[${keys.map(k => `${k}=${all[k]}`).join(',')}]` : type;
}

/** Model states for a palette, read from the client jar's blockstates. */
export async function modelStates(palette: string[], jar: ArrayBuffer | Blob): Promise<string[]> {
  const zip = await JSZip.loadAsync(jar);
  const cache = new Map<string, Map<string, string[]> | undefined>();
  const out: string[] = [];
  for (const state of palette) {
    const { type } = parseState(state);
    if (!cache.has(type)) {
      const [ns, path] = type.includes(':') ? type.split(':', 2) : ['minecraft', type];
      const file = zip.file(`assets/${ns}/blockstates/${path}.json`);
      cache.set(type, file ? blockProperties(JSON.parse(await file.async('string'))) : undefined);
    }
    out.push(completeState(state, cache.get(type)));
  }
  return out;
}
