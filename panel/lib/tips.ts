/**
 * First-time tips, in the manner of iOS TipKit: a small popover on a feature the first time it is on screen. One
 * tip shows at a time, the first in `TIPS` order; closing it, or using the feature, ends it for good. After one is
 * closed the next waits for another page, so tips never chain. What was seen is kept in this browser.
 */
export const TIPS = {
  connect: { title: 'Connect your AI', text: 'Get the address and personal token your assistant needs to build on this server.' },
  history: { title: 'Every edit is recorded', text: 'Open an edit to see pictures of what changed. Any of them can be put back.' },
  map: { title: 'See your world from above', text: 'The map shows where the AI has built, and lets you mark where it should build next.' },
  lessons: { title: 'Teach your AI', text: 'Lessons are notes the AI reads before every build here. Add one when it keeps getting something wrong.' },
  revert: { title: 'Don’t like it?', text: 'Revert puts back the blocks this edit changed. Anything built since is left alone.' },
  markArea: { title: 'Mark where to build', text: 'Draw a box and name it. Then ask your AI to build in "@name", with no coordinates.' },
} as const;

export type TipId = keyof typeof TIPS;
const ORDER = Object.keys(TIPS) as TipId[];
const isTip = (v: unknown): v is TipId => typeof v === 'string' && Object.hasOwn(TIPS, v);

export interface TipState { /** The welcome guide was closed. */ welcomed: boolean; /** Tips that were closed or acted on. */ done: TipId[] }
export interface TipView extends TipState { /** The tip to show now. */ active: TipId | null }

/** What is stored, read leniently: a broken or missing value is a new visitor. */
export function parseState(raw: string | null): TipState {
  try {
    const v = JSON.parse(raw || 'null');
    if (v && typeof v === 'object') return { welcomed: v.welcomed === true, done: Array.isArray(v.done) ? v.done.filter(isTip) : [] };
  } catch { /* a new visitor */ }
  return { welcomed: false, done: [] };
}

/** The tip to show: the first of those on screen that is not done. None while the guide is up or after one was just closed. */
export function nextTip(mounted: Iterable<TipId>, state: TipState, quiet: boolean): TipId | null {
  if (quiet || !state.welcomed) return null;
  const on = new Set(mounted);
  return ORDER.find(id => on.has(id) && !state.done.includes(id)) ?? null;
}

// ---- the browser's store ----

const KEY = 'aibuilder.tips';
/** Before the browser's state is read, and on the server: nothing to show. */
export const SERVER_VIEW: TipView = { welcomed: true, done: [], active: null };

let state: TipState | null = null;
let quiet = false;
let view: TipView = SERVER_VIEW;
const mounted = new Map<TipId, number>();
const listeners = new Set<() => void>();

function load(): TipState {
  if (!state) {
    let raw: string | null = null;
    try { raw = localStorage.getItem(KEY); } catch { /* storage is blocked: the guide shows each visit */ }
    state = parseState(raw);
    window.addEventListener('hashchange', () => { if (quiet) { quiet = false; refresh(); } });
    // Not refresh(): this can run while React renders, when nobody may be told.
    view = { ...state, active: nextTip(mounted.keys(), state, quiet) };
  }
  return state;
}

function refresh() {
  const s = state!;
  view = { ...s, active: nextTip(mounted.keys(), s, quiet) };
  listeners.forEach(l => l());
}

function save(next: TipState) {
  state = next;
  try { localStorage.setItem(KEY, JSON.stringify(next)); } catch { /* kept for this visit only */ }
  refresh();
}

export function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

export function getView(): TipView {
  load();
  return view;
}

/** A tip's feature is on screen until the returned function is called. */
export function mountTip(id: TipId) {
  load();
  mounted.set(id, (mounted.get(id) || 0) + 1);
  refresh();
  return () => {
    const n = (mounted.get(id) || 1) - 1;
    if (n) mounted.set(id, n); else mounted.delete(id);
    refresh();
  };
}

/** Ends a tip for good. `closed` when the user closed it rather than used the feature: the next one then waits. */
export function finishTip(id: TipId, closed = false) {
  const s = load();
  if (s.done.includes(id)) return;
  if (closed) quiet = true;
  save({ ...s, done: [...s.done, id] });
}

export function finishWelcome() {
  const s = load();
  if (!s.welcomed) save({ ...s, welcomed: true });
}

/** Shows every tip again. */
export function resetTips() {
  quiet = false;
  save({ ...load(), done: [] });
}
