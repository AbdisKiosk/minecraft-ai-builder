import { useState } from 'react';
import { api, message, serverApi } from '../lib/api';
import { ago, boxText, dateTime, num } from '../lib/format';
import type { Draft, Placed } from '../lib/types';
import { useFetched } from '../lib/useFetched';
import type { Servers } from '../lib/usePanel';
import Pictures from './Pictures';
import PromoteDialog from './PromoteDialog';
import { Badge, ChangesCard, Conflicts, Pills, ServerPageLink, Stats, TypesCard } from './shared';

/** A draft: the edits waiting in it, pictures, and approve or reject. */
export default function DraftPage({ server, id, servers }: { server: string; id: string; servers: Servers }) {
  const base = `${serverApi(server)}/drafts/${encodeURIComponent(id)}`;
  const { data: d, error } = useFetched<Draft>(base);
  const [force, setForce] = useState(false);
  const [busy, setBusy] = useState<'approve' | 'reject' | null>(null);
  const [approved, setApproved] = useState<Placed | null>(null);
  const [rejected, setRejected] = useState(false);
  const [decisionError, setDecisionError] = useState('');
  const [promote, setPromote] = useState(false);

  if (error) return <div className="card"><p className="error">{error}</p></div>;
  if (!d) return <p className="muted">Loading draft…</p>;
  const open = d.status === 'open';
  const total = d.added + d.removed + d.changed;

  const decide = async (what: 'approve' | 'reject') => {
    const question = what === 'approve'
      ? `Place draft ${d.id} on ${server}? ${num(total)} blocks change.${force ? ' Blocks changed since will be overwritten.' : ''}`
      : `Reject draft ${d.id}? Its edits are dropped and nothing is placed.`;
    if (!confirm(question)) return;
    setBusy(what);
    setDecisionError('');
    try {
      const r = await api<Placed>(`${base}/${what}`, { method: 'POST', body: JSON.stringify(what === 'approve' ? { force } : {}) });
      if (what === 'approve') setApproved(r);
      else setRejected(true);
    } catch (ex) {
      setDecisionError(message(ex));
      setBusy(null);
    }
  };

  return (
    <>
      <div className="page-head"><div>
        <h1>Draft <span className="muted mono" style={{ fontSize: 15 }}>{d.id}</span></h1>
        <div className="summary">
          <div>Started <b>{dateTime(d.created)}</b></div>
          <div>World <b className="mono">{d.world}</b></div>
          <div>Edits <b>{num(d.edits)}</b></div>
          {d.bounds && <div>Where <b className="mono">{boxText(d.bounds)}</b></div>}
          <div>Status <Badge kind={open ? 'running' : d.status}>{open ? 'waiting for approval' : d.status}</Badge></div>
          {d.closedBy && <div>By <b>{d.closedBy}</b> {d.closedAt ? ago(d.closedAt) : ''}</div>}
          {d.job && <div>Placed by job <span className="mono">{d.job}</span></div>}
        </div>
      </div>
        <Pills of={d} /></div>
      <div className="stack">
        <Pictures base={base} whose="the draft's" mc={servers.get(server)?.info?.mc}
          none={total > 0 ? undefined : 'The draft changes no blocks.'} />
        <section className="card"><h2>{open ? 'Approve' : 'Decision'}</h2>
          {open ? (
            <>
              <p className="muted">Approving places exactly the blocks shown, as one job you can revert from the history. Blocks changed in the world since the draft was made are left alone and listed, unless you force it.</p>
              <div className="toolbar">
                <button disabled={!!busy || total === 0} onClick={() => decide('approve')}>Approve and place</button>
                <button className="ghost" disabled={!!busy} onClick={() => decide('reject')}>Reject</button>
                <label><input type="checkbox" checked={force} onChange={ev => setForce(ev.target.checked)} /> also overwrite blocks changed since</label>
                <span className="spacer" />
                <button className="ghost" disabled={!d.bounds} onClick={() => setPromote(true)}>Promote to…</button>
              </div>
            </>
          ) : <p>This draft was {d.status}{d.closedBy ? ' by ' + d.closedBy : ''}.</p>}
          {busy === 'approve' && !approved && <p className="muted">Reading the region…</p>}
          {decisionError && <p className="error">{decisionError}</p>}
          {rejected && <p>Rejected. Nothing was placed.</p>}
          {approved && (
            <>
              {approved.job
                ? <p>Approved. Placing {num(approved.blocks)} blocks as job <span className="mono">{approved.job}</span>; follow it on <ServerPageLink server={server} />.</p>
                : <p>Approved. Nothing to place: every block already matches the draft or changed since.</p>}
              <Conflicts result={approved} what="the draft was made" placedLabel="Draft expected" />
            </>
          )}
        </section>
        <section className="card"><h2>Edits in this draft</h2><div className="table-wrap"><table>
          <thead><tr><th>When</th><th>Tool</th><th>Who</th><th>Changes</th></tr></thead>
          <tbody>
            {d.entries.map((x, n) => (
              <tr key={n}><td title={dateTime(x.time)}>{ago(x.time)}</td>
                <td>{x.tool}{x.label && <> <Badge>{x.label}</Badge></>}</td>
                <td>{x.caller} <span className="muted">· {x.source}</span></td><td><Stats of={x} /></td></tr>
            ))}
            {!d.entries.length && <tr><td colSpan={4} className="empty">No edits.</td></tr>}
          </tbody></table></div></section>
        <TypesCard of={d} />
        <ChangesCard of={d} />
      </div>
      {promote && <PromoteDialog from={server} servers={servers} onClose={() => setPromote(false)}
        source={{ world: d.world, bounds: d.bounds, fromDraft: true }} />}
    </>
  );
}
