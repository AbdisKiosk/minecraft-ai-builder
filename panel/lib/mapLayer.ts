// The map page's tile layer: <img> tiles placed by hand, outside React, so panning and zooming touch only the
// transforms of the few dozen tiles in view. Tiles come from /api/servers/<id>/map/tile/... with their version in
// the URL, so the browser caches each version for good.

/** Where the map looks: the block at the middle of the view, and screen pixels per block. */
export interface View { x: number; z: number; scale: number }

/** A world's map as the plugin describes it. */
export interface MapIndex {
  world: string;
  worlds: string[];
  tile: number;
  maxZoom: number;
  minY: number;
  maxY: number;
  /** [rx, rz, version] for each region file. */
  regions: [number, number, string][];
  /** The folder the plugin reads, relative to the server's worlds. */
  source?: string;
  spawn?: [number, number];
  players?: { name: string; x: number; y: number; z: number }[];
  markers: Record<string, [number, number, number]>;
  areas: Record<string, { box: number[]; note?: string }>;
  offline?: boolean;
}

export const MIN_SCALE_ZOOM = 1; // levels of zoom past the coarsest tile level
export const MAX_SCALE = 24;

/** The tile level that draws about one tile pixel per screen pixel at this scale. */
export function levelFor(scale: number, maxZoom: number): number {
  return Math.max(0, Math.min(maxZoom, Math.floor(Math.log2(1 / scale) + 0.25)));
}

export function minScale(maxZoom: number): number {
  return 1 / 2 ** (maxZoom + MIN_SCALE_ZOOM);
}

/** FNV-1a over a string, as base 36: tile versions above zoom 0, which only need to change when a region does. */
function hash(s: string): string {
  let h1 = 0x811c9dc5, h2 = 0x01000193;
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    h1 = Math.imul(h1 ^ c, 0x01000193);
    h2 = Math.imul(h2 ^ c, 0x5bd1e995);
  }
  return (h1 >>> 0).toString(36) + (h2 >>> 0).toString(36);
}

/** For each zoom level, the tiles that exist ("x,z") and their versions. */
export function tileVersions(regions: MapIndex['regions'], maxZoom: number): Map<string, string>[] {
  const levels: Map<string, string>[] = [new Map()];
  for (const [x, z, v] of regions) levels[0].set(`${x},${z}`, v);
  for (let l = 1; l <= maxZoom; l++) {
    const p = new Map<string, string[]>();
    // Sorted, so the same regions always give the same version.
    for (const [x, z, v] of [...regions].sort((a, b) => a[0] - b[0] || a[1] - b[1])) {
      const k = `${x >> l},${z >> l}`;
      let list = p.get(k);
      if (!list) p.set(k, list = []);
      list.push(`${x},${z},${v}`);
    }
    levels.push(new Map([...p].map(([k, list]) => [k, hash(list.join(';'))])));
  }
  return levels;
}

interface Tile {
  img: HTMLImageElement;
  level: number;
  x: number;
  z: number;
  loaded: boolean;
  failed: boolean;
  used: number;
}

const KEEP = 300;

export class TileLayer {
  private readonly tiles = new Map<string, Tile>();
  private versions: Map<string, string>[] = [];
  private maxZoom = 0;
  private size = 512;
  private frame = 0;
  private last: { view: View; w: number; h: number } | null = null;

  private readonly root: HTMLElement;
  private readonly url: (level: number, x: number, z: number, v: string) => string;
  private readonly onError: (message: string) => void;

  /** @param onError hears why a tile could not be drawn, when the server says */
  constructor(root: HTMLElement, url: (level: number, x: number, z: number, v: string) => string,
      onError: (message: string) => void = () => {}) {
    this.root = root;
    this.url = url;
    this.onError = onError;
  }

  setIndex(index: MapIndex) {
    this.maxZoom = index.maxZoom;
    this.size = index.tile;
    this.versions = tileVersions(index.regions, index.maxZoom);
    // A tile whose version changed gets a new URL: drop the old one once the new one has loaded (see draw).
    if (this.last) this.draw(this.last.view, this.last.w, this.last.h);
  }

  clear() {
    for (const t of this.tiles.values()) t.img.remove();
    this.tiles.clear();
  }

  /** Places the tiles for this view; called on every frame the view changes. */
  draw(view: View, w: number, h: number) {
    this.last = { view, w, h };
    const frame = ++this.frame;
    const level = levelFor(view.scale, this.maxZoom);
    const wanted = new Set<string>();
    const covering = (l: number) => {
      const blocks = this.size * 2 ** l;
      const x0 = Math.floor((view.x - w / 2 / view.scale) / blocks), x1 = Math.floor((view.x + w / 2 / view.scale) / blocks);
      const z0 = Math.floor((view.z - h / 2 / view.scale) / blocks), z1 = Math.floor((view.z + h / 2 / view.scale) / blocks);
      return { x0, x1, z0, z1 };
    };
    // The tiles at this level, nearest the middle first so they are asked for first.
    const r = covering(level);
    const exists = this.versions[level] || new Map();
    const want: [number, number, string, number][] = [];
    for (let x = r.x0; x <= r.x1; x++) {
      for (let z = r.z0; z <= r.z1; z++) {
        const v = exists.get(`${x},${z}`);
        if (v === undefined) continue;
        const blocks = this.size * 2 ** level;
        const d = ((x + .5) * blocks - view.x) ** 2 + ((z + .5) * blocks - view.z) ** 2;
        want.push([x, z, v, d]);
      }
    }
    want.sort((a, b) => a[3] - b[3]);
    for (const [x, z, v] of want) {
      const key = `${level}/${x}/${z}/${v}`;
      wanted.add(key);
      let t = this.tiles.get(key);
      if (!t) {
        const img = document.createElement('img');
        img.alt = '';
        img.decoding = 'async';
        img.draggable = false;
        img.className = 'map-tile';
        t = { img, level, x, z, loaded: false, failed: false, used: frame };
        const tile = t;
        img.onload = () => {
          tile.loaded = true;
          img.classList.add('in');
          if (this.last) this.draw(this.last.view, this.last.w, this.last.h);
        };
        img.onerror = () => {
          // A server that was busy or offline: try again on a later frame.
          tile.failed = true;
          img.remove();
          // An <img> cannot read the error; ask once more for the message.
          fetch(img.src, { credentials: 'same-origin' })
            .then(r => (r.ok || r.status === 404 ? null : r.json()))
            .then(b => { if (b && typeof b.error === 'string') this.onError(b.error); })
            .catch(() => {});
          setTimeout(() => { if (this.tiles.get(key) === tile) this.tiles.delete(key); }, 8000);
        };
        img.src = this.url(level, x, z, v);
        this.tiles.set(key, t);
      }
      t.used = frame;
      if (!t.failed) this.place(t, view, w, h, 3);
    }
    // Loaded tiles of other levels (or older versions) stay under the new ones until those load.
    const pending = want.some(([x, z, v]) => !this.tiles.get(`${level}/${x}/${z}/${v}`)?.loaded);
    for (const [key, t] of this.tiles) {
      if (wanted.has(key)) continue;
      const b = this.size * 2 ** t.level;
      const inView = (t.x + 1) * b > view.x - w / 2 / view.scale && t.x * b < view.x + w / 2 / view.scale
        && (t.z + 1) * b > view.z - h / 2 / view.scale && t.z * b < view.z + h / 2 / view.scale;
      if (pending && t.loaded && inView && Math.abs(t.level - level) <= 3) {
        t.used = frame;
        this.place(t, view, w, h, t.level > level ? 2 - (t.level - level) / 4 : 1);
      } else if (!t.loaded) {
        // Not needed any more and not loaded yet: stop the download.
        t.img.removeAttribute('src');
        t.img.remove();
        this.tiles.delete(key);
      } else if (t.img.parentNode) {
        t.img.remove();
      }
    }
    if (this.tiles.size > KEEP) {
      const old = [...this.tiles].filter(([, t]) => t.used !== frame).sort((a, b) => a[1].used - b[1].used);
      for (const [key, t] of old.slice(0, this.tiles.size - KEEP)) {
        t.img.remove();
        this.tiles.delete(key);
      }
    }
  }

  private place(t: Tile, view: View, w: number, h: number, layer: number) {
    const blocks = this.size * 2 ** t.level;
    // Edges rounded to whole pixels, so neighbouring tiles meet without hairline gaps.
    const left = Math.round((t.x * blocks - view.x) * view.scale + w / 2);
    const top = Math.round((t.z * blocks - view.z) * view.scale + h / 2);
    const right = Math.round(((t.x + 1) * blocks - view.x) * view.scale + w / 2);
    const bottom = Math.round(((t.z + 1) * blocks - view.z) * view.scale + h / 2);
    const s = t.img.style;
    s.transform = `translate(${left}px, ${top}px)`;
    s.width = `${right - left}px`;
    s.height = `${bottom - top}px`;
    s.zIndex = String(Math.round(layer * 4));
    // Sharp blocks once a tile pixel is larger than a screen pixel.
    const pixelated = view.scale * 2 ** t.level > 1.01;
    if (t.img.classList.contains('px') !== pixelated) t.img.classList.toggle('px', pixelated);
    if (!t.img.parentNode) this.root.appendChild(t.img);
  }
}
