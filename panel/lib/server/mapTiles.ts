import type { Json } from './hub';

/**
 * Map tiles the plugins drew, kept in memory so a tile crosses the relay once per version, however many people look
 * at it. A tile's URL carries its version, so browsers keep them too. The last tile of each position is also served,
 * marked stale, while its server is offline, and so is the last index of each world.
 */
export class TileCache {
  private readonly tiles = new Map<string, { v: string; png: Uint8Array }>();
  private readonly indexes = new Map<string, Json>();
  private readonly inflight = new Map<string, Promise<Uint8Array | null>>();
  private bytes = 0;
  private readonly maxBytes: number;

  constructor(maxBytes: number) {
    this.maxBytes = maxBytes;
  }

  /** The cached tile at this position: any version, or null. Marks it recently used. */
  get(key: string): { v: string; png: Uint8Array } | null {
    const hit = this.tiles.get(key);
    if (!hit) return null;
    this.tiles.delete(key);
    this.tiles.set(key, hit);
    return hit;
  }

  put(key: string, v: string, png: Uint8Array) {
    const old = this.tiles.get(key);
    if (old) {
      this.bytes -= old.png.byteLength;
      this.tiles.delete(key);
    }
    if (png.byteLength > this.maxBytes) return;
    this.tiles.set(key, { v, png });
    this.bytes += png.byteLength;
    for (const [k, e] of this.tiles) {
      if (this.bytes <= this.maxBytes) break;
      this.tiles.delete(k);
      this.bytes -= e.png.byteLength;
    }
  }

  /** Runs `load` once for concurrent requests of the same tile version. */
  once(key: string, load: () => Promise<Uint8Array | null>): Promise<Uint8Array | null> {
    let p = this.inflight.get(key);
    if (!p) {
      p = load().finally(() => this.inflight.delete(key));
      this.inflight.set(key, p);
    }
    return p;
  }

  index(key: string): Json | undefined {
    return this.indexes.get(key);
  }

  setIndex(key: string, index: Json) {
    this.indexes.set(key, index);
  }

  /** Forgets a server's tiles and indexes: it was deleted. */
  drop(serverId: string) {
    const prefix = serverId + '|';
    for (const [k, e] of this.tiles) {
      if (k.startsWith(prefix)) {
        this.tiles.delete(k);
        this.bytes -= e.png.byteLength;
      }
    }
    for (const k of this.indexes.keys()) if (k.startsWith(prefix)) this.indexes.delete(k);
  }

  get size() {
    return { tiles: this.tiles.size, bytes: this.bytes };
  }
}

// Route bundles each load their own copy of this module (as with hub.ts), so the cache lives on globalThis.
const g = globalThis as { __aibTiles?: TileCache };

export function tileCache(): TileCache {
  if (!g.__aibTiles) {
    const mb = Number(process.env.MAP_CACHE_MB || 256);
    g.__aibTiles = new TileCache((Number.isFinite(mb) && mb > 0 ? mb : 256) * 1024 * 1024);
  }
  return g.__aibTiles;
}

export const tileKey = (server: string, world: string, zoom: number, x: number, z: number) =>
  `${server}|${world}|${zoom}|${x}|${z}`;
