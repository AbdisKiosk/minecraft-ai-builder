// The shapes the panel's /api and /events endpoints send (PanelServer, ServerState, and the plugin's History and Drafts).

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

export interface ServerInfo { mc?: string; bridge?: string; plugin?: string; review?: boolean; worlds?: string[] }

export interface Server {
  id: string;
  name?: string;
  online: boolean;
  since?: number;
  info?: ServerInfo;
  running: Job[];
  recent: Job[];
  drafts?: DraftSummary[];
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
