import { useCallback, useEffect, useRef, useState } from 'react';
import { api, message, serverApi } from '../lib/api';
import { ago, boxText, dateTime, num } from '../lib/format';
import { diffHref, draftHref, go } from '../lib/routes';
import type { DraftSummary, HistorySummary, Server } from '../lib/types';
import type { Servers } from '../lib/usePanel';
import PromoteDialog from './PromoteDialog';
import { Badge, Jobs, ServerMeta, Stats } from './shared';

interface HistoryPage { entries: HistorySummary[]; total: number }
interface DraftList { drafts: DraftSummary[]; review?: boolean }

export default function ServerPage({ id, server, servers }: { id: string; server?: Server; servers: Servers }) {
  const [connect, setConnect] = useState(false);
  const [promote, setPromote] = useState(false);
  const name = server ? server.name || id : id;
  const mcp = `${window.location.origin}/s/${id}`;

  return (
    <>
      <div className="page-head"><div><h1>{name}</h1>
        <div className="muted">{server && <ServerMeta server={server} />}</div></div>
        <div className="toolbar" style={{ margin: 0 }}>
          <button className="ghost small" onClick={() => setPromote(true)}>Copy region to…</button>
          <button className="ghost small" onClick={() => setConnect(!connect)}>Connect Claude Code</button></div></div>
      {connect && (
        <div className="card connect">
          <h2>Connect</h2>
          <p className="muted">Make a client token on the panel host with <code>java -jar AIBuilderPanel.jar add-client</code>, then in the repo:</p>
          <pre>export AIBUILDER_URL={mcp} AIBUILDER_TOKEN=&lt;client token&gt;</pre>
          <p className="muted">The repo&apos;s <code>mcp.json</code> and the Python SDK use these as they are. For several servers in one
            Claude Code session, add one MCP entry per server with url <code>{mcp}/mcp</code>.</p>
        </div>
      )}
      <div className="stack">
        <section className="card"><h2>Drafts</h2><Drafts id={id} server={server} /></section>
        <section className="card"><h2>Jobs</h2><div>{server && <Jobs server={server} recent={10} />}</div></section>
        <History id={id} server={server} />
      </div>
      {promote && <PromoteDialog from={id} servers={servers} onClose={() => setPromote(false)}
        source={{ world: server?.info?.worlds?.[0] || 'world' }} />}
    </>
  );
}

function Drafts({ id, server }: { id: string; server?: Server }) {
  const [list, setList] = useState<DraftList | null>(null);
  const [error, setError] = useState('');
  // The live state only has the open drafts: when those change, read the list again.
  const key = JSON.stringify(server?.drafts || []);
  useEffect(() => {
    let current = true;
    api<DraftList>(`${serverApi(id)}/drafts`).then(
      r => { if (current) { setList(r); setError(''); } },
      ex => { if (current) setError(message(ex)); });
    return () => { current = false; };
  }, [id, key]);

  if (error) return <p className="error">{error}</p>;
  if (!list) return <p className="muted">Loading…</p>;
  const rows = list.drafts.slice(0, 10);
  return (
    <>
      {list.review && <p className="muted">Review is on: Claude&apos;s edits wait here until someone approves them.</p>}
      {rows.length ? (
        <div className="table-wrap"><table>
          <thead><tr><th>Started</th><th>World</th><th>Edits</th><th>Changes</th><th>Status</th></tr></thead>
          <tbody>
            {rows.map(d => (
              <tr className="link" key={d.id} onClick={() => go(draftHref(id, d.id))}>
                <td>{ago(d.created)}</td><td className="mono">{d.world}</td><td>{num(d.edits)}</td><td><Stats of={d} /></td>
                <td><Badge kind={d.status === 'open' ? 'running' : d.status}>{d.status === 'open' ? 'waiting' : d.status}</Badge></td>
              </tr>
            ))}
          </tbody>
        </table></div>
      ) : <p className="empty">No drafts. They appear when review is on, or when a region is promoted here.</p>}
    </>
  );
}

function History({ id, server }: { id: string; server?: Server }) {
  const [entries, setEntries] = useState<HistorySummary[] | null>(null);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState('');
  const request = useRef(0);

  const load = useCallback(async (before: string | null) => {
    const mine = ++request.current;
    try {
      const q = new URLSearchParams({ limit: '50' });
      if (before) q.set('before', before);
      const page = await api<HistoryPage>(`${serverApi(id)}/history?${q}`);
      if (mine !== request.current) return;
      setEntries(old => (before && old ? [...old, ...page.entries] : page.entries));
      setTotal(page.total);
      setError('');
    } catch (ex) {
      if (mine === request.current && !before) setError(message(ex));
    }
  }, [id]);

  // A finished job adds a history entry: read the first page again.
  const newest = server?.recent[0] ? server.recent[0].id + server.recent[0].status : '';
  useEffect(() => { load(null); }, [load, newest]);

  const last = entries?.[entries.length - 1];
  return (
    <section className="card"><h2>History</h2><div className="table-wrap"><table>
      <thead><tr><th>When</th><th>Tool</th><th className="hide-sm">Who</th><th>Changes</th><th className="hide-sm">Where</th><th>Status</th></tr></thead>
      <tbody>
        {error && !entries?.length ? <tr><td colSpan={6} className="error">{error}</td></tr>
          : entries && !entries.length ? <tr><td colSpan={6} className="empty">No edits recorded yet.</td></tr>
          : (entries || []).map(e => (
            <tr className="link" key={e.id} onClick={() => go(diffHref(id, e.id))}>
              <td title={dateTime(e.time)}>{ago(e.time)}</td>
              <td>{e.tool}{e.revertOf && <> <Badge>revert of {e.revertOf}</Badge></>}</td>
              <td className="hide-sm">{e.caller} <span className="muted">· {e.source}</span></td>
              <td><Stats of={e} tracked={e.tracked} /></td>
              <td className="hide-sm mono">{e.world} {boxText(e.bounds)}</td>
              <td><Badge kind={e.status}>{e.status}</Badge></td>
            </tr>
          ))}
      </tbody></table></div>
      <p>{last && entries!.length < total && <button className="ghost small" onClick={() => load(last.id)}>Load older</button>}</p>
    </section>
  );
}
