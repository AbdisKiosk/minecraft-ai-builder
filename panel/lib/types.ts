// The shapes the app's /api endpoints send (lib/server, and the plugin's History and Drafts over the relay).

export type Role = 'owner' | 'admin' | 'builder' | 'viewer';
const RANK: Record<Role, number> = { owner: 4, admin: 3, builder: 2, viewer: 1 };

/** Whether `role` is at least `needed`; the server checks again, this only hides buttons. */
export const can = (role: Role | undefined, needed: Role) => !!role && RANK[role] >= RANK[needed];

export type Box = number[] | { min: Vec; max: Vec };
export interface Vec { x: number; y: number; z: number }

export interface Job {
  id: string;
  tool?: string;
  caller?: string;
  source?: string;
  status: string;
  phase?: string;
  processed: number;
  total: number;
  started: number;
  changed?: number;
  ms?: number;
  error?: string;
  draft?: string;
  history?: string;
}

export interface Counts { added: number; removed: number; changed: number }

export interface DraftSummary extends Counts {
  id: string;
  world: string;
  edits: number;
  status: string;
  created: number;
}

export interface ServerInfo { mc?: string; bridge?: string; plugin?: string; review?: boolean; worlds?: string[]; lessons?: boolean }

export interface Server {
  id: string;
  name: string;
  role: Role;
  /** When its plugin last connected or disconnected, from the database. */
  lastSeen: number | null;
  online: boolean;
  /** When it connected or went offline while this app has been running; 0 when not since the app started. */
  since: number;
  info?: ServerInfo;
  running: Job[];
  recent: Job[];
  drafts?: DraftSummary[];
  /** Bumped when an entry is archived, so the history list reloads. */
  historyRev?: number;
}

export interface HistorySummary extends Counts {
  id: string;
  time: number;
  tool: string;
  caller: string;
  source: string;
  world: string;
  bounds?: Box;
  status: string;
  tracked: boolean;
  revertOf?: string;
}

export interface TypeCount { type: string; added: number; removed: number }
export interface Change { pos: number[]; before: string; after: string }

/** What the "Blocks by type" and "Changed blocks" cards need; history entries and drafts both have it. */
export interface Detail extends Counts { types: TypeCount[]; changes: Change[]; listed: number }

export interface HistoryEntry extends HistorySummary, Detail { ms?: number; error?: string }

export interface DraftEdit extends Counts { time: number; tool: string; label?: string; caller: string; source: string }

export interface Draft extends DraftSummary, Detail {
  bounds?: number[];
  closedBy?: string;
  closedAt?: number;
  job?: string;
  entries: DraftEdit[];
}

/** The answer to a revert or an approve. */
export interface Placed {
  job?: string;
  blocks?: number;
  conflicts?: number;
  conflictList?: { pos: number[]; expected: string; now: string }[];
}

export type Renders = Record<'before' | 'after' | 'changes', string>;

/** What people changed in the blocks a history entry placed (plugin/.../History.java#corrections). */
export interface Corrections {
  cells: number;
  placed: number;
  share: number;
  /** Nearby additions are in cells, but not in the original-cell correction rate. */
  additions?: number;
  baseline?: boolean;
  substitutions: { from: string; to: string; count: number; at: number[][] }[];
  removed: { type: string; count: number }[];
  states: { type: string; count: number; props?: string[] }[];
}

export interface CorrectionMeasurement {
  id: string;
  time: number;
  placed: number;
  corrected?: { cells: number; share: number; time: number; additions?: number; baseline?: boolean };
}

/** A lesson Claude reads every session on a server (plugin/.../Lessons.java). */
export interface Lesson {
  id: string;
  text: string;
  origin: 'feedback' | 'claude' | 'manual';
  /** The history (h…) or draft (d…) ids it came from. */
  entries: string[];
  by: string;
  time: number;
  seen: boolean;
  updated?: number;
  health?: { status: 'unavailable' | 'watching' | 'recurring'; count: number; entries: string[] };
}

/** One of the user's assets (lib/server/assets.ts). */
export interface Asset {
  id: string;
  name: string;
  description: string;
  tags: string[];
  /** Width (x), height (y) and length (z). */
  size: [number, number, number];
  /** Blocks that are not air. */
  blocks: number;
  /** The Minecraft version it was saved on. */
  mc: string | null;
  bytes: number;
  created: number;
  updated: number;
}

/** An asset in the library list: the user's own, or another user's shared with a server they build on. */
export interface LibraryAsset extends Asset {
  /** Only the owner shares, edits or deletes it. */
  mine: boolean;
  /** The owner's name, on another user's asset. */
  owner?: string;
  /** Own asset: the server ids it is shared with. Another's: the server ids the user builds on that it is shared with. */
  shared: string[];
}

/** A server an asset is shared with, as its owner sees it. */
export interface AssetShare { server: string; name: string; created: number }

export interface AssetList {
  assets: LibraryAsset[];
  quota: { count: number; bytes: number; maxCount: number; maxBytes: number };
}
