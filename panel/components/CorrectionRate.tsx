import { serverApi } from '../lib/api';
import { correctionSeries } from '../lib/corrections';
import { dateTime } from '../lib/format';
import { diffHref } from '../lib/routes';
import type { CorrectionMeasurement, Server } from '../lib/types';
import { useFetched } from '../lib/useFetched';

/** Read stored checks, never trigger chunk loads just to draw a chart. */
export default function CorrectionRate({ server: s }: { server: Server }) {
  const revision = `${s.historyRev ?? 0}-${s.recent[0]?.id ?? ''}-${s.recent[0]?.status ?? ''}`;
  const { data, error } = useFetched<{ entries: CorrectionMeasurement[] }>(
    `${serverApi(s.id)}/history/correctionStats?rev=${encodeURIComponent(revision)}`, s.online);
  const series = correctionSeries(data?.entries ?? []);
  const percent = (n: number) => `${(n * 100).toFixed(1)}%`;
  const label = (e: CorrectionMeasurement) => `${e.id}: ${e.corrected ? percent(e.corrected.share) : 'not checked'} · ${dateTime(e.time)}`;

  return <section className="card"><h2>Correction rate</h2>
    {!s.online ? <p className="muted">Checks are kept on the server. Reconnect it to see current measurements.</p>
      : error ? <p className="muted">{error.includes('unknown history op') ? 'Update the plugin to see correction-rate measurements.' : error}</p>
      : !data ? <p className="muted">Loading measurements…</p>
      : !series.points.length ? <p className="muted">No tracked Claude builds yet.</p> : <>
        <p>{series.checked} of the latest {series.points.length} tracked Claude builds checked.
          {series.rate !== null && <> <b>{percent(series.rate)}</b> of checked builds&apos; original blocks were corrected.</>}</p>
        <svg viewBox="0 0 800 190" role="img" aria-label="Correction rate per build, oldest on the left, from zero to one hundred percent"
          style={{ width: '100%', maxHeight: 240 }}>
          <text x="0" y="16" fill="currentColor" fontSize="12">100%</text>
          <text x="12" y="166" fill="currentColor" fontSize="12">0%</text>
          <line x1="48" x2="796" y1="160" y2="160" stroke="currentColor" opacity="0.25" />
          {series.points.map((e, i) => {
            const step = 748 / series.points.length;
            const height = e.corrected ? Math.max(2, Math.min(1, Math.max(0, e.corrected.share)) * 145) : 4;
            return <a key={e.id} href={diffHref(s.id, e.id)} aria-label={label(e)}><title>{label(e)}</title>
              <rect x={48 + i * step} y={160 - height} width={Math.max(1, step - 1)} height={height}
                fill="currentColor" opacity={e.corrected ? 0.8 : 0.2} />
            </a>;
          })}
          <text x="48" y="184" fill="currentColor" fontSize="12">Older</text>
          <text x="748" y="184" fill="currentColor" fontSize="12">Newer</text>
        </svg>
        <p className="muted">Faint marks have not been checked; they are not zeroes. Nearby additions are counted separately.
          Checks happen after builds have aged at least ten minutes, at session start or when opening a build. Natural changes and
          below-threshold corrections are excluded; a falling rate is a signal, not proof a lesson worked.</p>
        <details><summary>Measurements</summary><div className="table-wrap"><table>
          <thead><tr><th>Build</th><th>Correction rate</th><th>Nearby additions</th><th>Last checked</th></tr></thead>
          <tbody>{series.points.map(e => <tr key={e.id}>
            <td><a className="mono" href={diffHref(s.id, e.id)}>{e.id}</a></td>
            <td>{e.corrected ? percent(e.corrected.share) : 'Not checked'}</td>
            <td>{e.corrected ? e.corrected.additions ?? 0 : '—'}</td>
            <td>{e.corrected ? dateTime(e.corrected.time) : '—'}</td>
          </tr>)}</tbody>
        </table></div></details>
      </>}
  </section>;
}
