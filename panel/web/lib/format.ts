import type { Box } from './types';

export function ago(ms: number): string {
  const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (s < 60) return s + 's ago';
  if (s < 3600) return Math.round(s / 60) + 'm ago';
  if (s < 86400) return Math.round(s / 3600) + 'h ago';
  return new Date(ms).toLocaleDateString();
}

export function duration(ms: number | undefined | null): string {
  if (ms == null) return '';
  if (ms < 1000) return ms + ' ms';
  const s = ms / 1000;
  return s < 60 ? s.toFixed(1) + ' s' : Math.floor(s / 60) + 'm ' + Math.round(s % 60) + 's';
}

export function num(n: number | undefined | null): string {
  return Number(n || 0).toLocaleString();
}

export function boxText(b: Box | undefined | null): string {
  if (!b) return '';
  if (Array.isArray(b)) return `${b[0]} ${b[1]} ${b[2]} → ${b[3]} ${b[4]} ${b[5]}`;
  return `${b.min.x} ${b.min.y} ${b.min.z} → ${b.max.x} ${b.max.y} ${b.max.z}`;
}

export function dateTime(ms: number): string {
  return new Date(ms).toLocaleString();
}
