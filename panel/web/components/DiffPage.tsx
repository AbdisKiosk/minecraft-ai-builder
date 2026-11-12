import { useState } from 'react';
import { api, message, serverApi } from '../lib/api';
import { boxText, dateTime, duration, num } from '../lib/format';
import { diffHref } from '../lib/routes';
import type { HistoryEntry, Placed } from '../lib/types';
import { useFetched } from '../lib/useFetched';
import type { Servers } from '../lib/usePanel';
import Pictures from './Pictures';
import PromoteDialog from './PromoteDialog';
import { Badge, ChangesCard, Conflicts, Pills, ServerPageLink, TypesCard } from './shared';

/** One history entry: what it changed, pictures, and revert. */
export default function DiffPage({ server, id, servers }: { server: string; id: string; servers: Servers }) {
  const base = `${serverApi(server)}/history/${encodeURIComponent(id)}`;
  const { data: e, error } = useFetched<HistoryEntry>(base);
  const [force, setForce] = useState(false);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<Placed | null>(null);
  const [revertError, setRevertError] = useState('');
  const [promote, setPromote] = useState(false);

  if (error) return <div className="card"><p className="error">{error}</p></div>;
  if (!e) return <p className="muted">Loading diff…</p>;
  const total = e.added + e.removed + e.changed;

  const revert = async () => {
    if (!confirm(`Revert ${e.id} on ${server}?${force ? ' Later changes to these blocks will be overwritten.' : ''}`)) return;
    setBusy(true);
    setResult(null);
    setRevertError('');
    try {
      setResult(await api<Placed>(`${base}/revert`, { method: 'POST', body: JSON.stringify({ force }) }));
    } catch (ex) {
      setRevertError(message(ex));
      setBusy(false);
    }
  };

  return (
    <>
      <div className="page-head"><div>
        <h1>{e.tool} <span className="muted mono" style={{ fontSize: 15 }}>{e.id}</span></h1>
        <div className="summary">
          <div>When <b>{dateTime(e.time)}</b></div>
          <div>Who <b>{e.caller}</b> via {e.source}</div>
          <div>Took <b>{duration(e.ms)}</b></div>
          <div>Where <b className="mono">{e.world} {boxText(e.bounds)}</b></div>
          <div>Status <Badge kind={e.status}>{e.status}</Badge></div>
          {e.revertOf && <div>Reverts <a href={diffHref(server, e.revertOf)}>{e.revertOf}</a></div>}
        </div>
        {e.error && <p className="error">{e.error}</p>}
      </div>
        <Pills of={e} /></div>
      <div className="stack">
        <Pictures base={base} whose="this edit's" mc={servers.get(server)?.info?.mc}
          none={e.tracked && total > 0 ? undefined : 'No pictures: this edit has no recorded block changes.'} />
        <section className="card"><h2>Revert</h2>
          <p className="muted">Puts back the old blocks as a new job. Blocks changed again since this edit are left alone and listed, unless you force it.</p>
          <div className="toolbar">
            <button className="danger" disabled={busy || !e.tracked || total === 0} onClick={revert}>Revert this edit</button>
            <label><input type="checkbox" checked={force} onChange={ev => setForce(ev.target.checked)} /> also overwrite later changes</label>
            <span className="spacer" />
            <button className="ghost" disabled={!e.bounds} onClick={() => setPromote(true)}>Promote to…</button>
          </div>
          {busy && !result && <p className="muted">Reading the region…</p>}
          {revertError && <p className="error">{revertError}</p>}
          {result && (
            <>
              {result.job
                ? <p>Putting back {num(result.blocks)} blocks as job <span className="mono">{result.job}</span>; follow it on <ServerPageLink server={server} />.</p>
                : <p>Nothing to put back: every block is already as it was, or changed since.</p>}
              <Conflicts result={result} what="this edit" placedLabel="This edit placed" />
            </>
          )}
        </section>
        <TypesCard of={e} />
        <ChangesCard of={e} />
      </div>
      {promote && <PromoteDialog from={server} servers={servers} onClose={() => setPromote(false)}
        source={{ world: e.world, bounds: boxArray(e) }} />}
    </>
  );
}

function boxArray(e: HistoryEntry): number[] | null {
  const b = e.bounds;
  if (!b || Array.isArray(b)) return b || null;
  return [b.min.x, b.min.y, b.min.z, b.max.x, b.max.y, b.max.z];
}
