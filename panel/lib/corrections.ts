import type { CorrectionMeasurement, Corrections } from './types.ts';

/** Lessons are cut to this many characters by the plugin (Lessons.MAX_TEXT). */
const MAX_LESSON = 160;

/**
 * A lesson sentence from the largest row of a correction, for "Save as lesson" to start from:
 * "Use spruce_planks, not oak_planks.", "Leave out torch.", "Check the facing of oak_stairs.". Empty when nothing
 * was changed.
 */
export function lessonFrom(c: Corrections): string {
  const rows: { count: number; text: string }[] = [
    ...c.substitutions.map(s => ({
      count: s.count,
      text: s.from === 'air' ? `Include ${s.to}.` : `Use ${s.to}, not ${s.from}.`,
    })),
    ...c.removed.map(r => ({ count: r.count, text: `Leave out ${r.type}.` })),
    ...c.states.map(s => ({
      count: s.count,
      text: s.props?.length ? `Check the ${and(s.props)} of ${s.type}.` : `Check the block states of ${s.type}.`,
    })),
  ];
  // Largest first; on a tie, the order above (substitutions, removals, states).
  const top = rows.reduce<(typeof rows)[number] | null>((best, r) => (!best || r.count > best.count ? r : best), null);
  return top ? top.text.slice(0, MAX_LESSON) : '';
}

function and(words: string[]): string {
  return words.length < 2 ? words.join('') : `${words.slice(0, -1).join(', ')} and ${words[words.length - 1]}`;
}

/** Oldest first. A missing check is unknown, not a successful build with a zero correction rate. */
export function correctionSeries(entries: CorrectionMeasurement[]) {
  const points = [...entries].sort((a, b) => a.time - b.time || a.id.localeCompare(b.id));
  const checked = points.filter(e => e.corrected && e.placed > 0);
  const placed = checked.reduce((n, e) => n + e.placed, 0);
  const cells = checked.reduce((n, e) => n + Math.max(0, e.corrected!.cells - (e.corrected!.additions ?? 0)), 0);
  return { points, checked: checked.length, rate: placed ? cells / placed : null };
}
