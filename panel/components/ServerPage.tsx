import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { api, downloadPlugin, message, serverApi } from '../lib/api';
import { ago, boxText, dateTime, num, OFFLINE } from '../lib/format';
import { diffHref, draftHref, go, lessonsHref, mapHref, setupHref } from '../lib/routes';
import { can, type DraftSummary, type HistorySummary, type Server } from '../lib/types';
import type { Servers } from '../lib/usePanel';
import PromoteDialog from './PromoteDialog';
import MembersCard from './MembersCard';
import ConnectClaude from './ConnectClaude';
import { Badge, Jobs, ServerMeta, Stats } from './shared';

interface HistoryPage { entries: HistorySummary[]; total: number }
interface DraftList { drafts: DraftSummary[]; review?: boolean }

export default function ServerPage({ server, servers }: { server: Server; servers: Servers }) {
  const id = server.id;
  const [connect, setConnect] = useState(false);
  const [promote, setPromote] = useState(false);
  const builder = can(server.role, 'builder');
  // Until its first connection, a server has no drafts, jobs or history to show.
  const fresh = !server.online && !server.lastSeen;

  return (
    <>
      <div className="page-head"><div><h1>{server.name} {server.role !== 'owner' && <Badge>{server.role}</Badge>}</h1>
        <div className="muted"><span className={`dot inline ${server.online ? 'on' : ''}`} /> <ServerMeta server={server} /></div></div>
        <div className="toolbar" style={{ margin: 0 }}>
          {!fresh && <a className="button ghost small" href={mapHref(id)}>Map</a>}
          {!fresh && <a className="button ghost small" href={lessonsHref(id)}>Lessons</a>}
          {builder && !fresh && <button className="ghost small" onClick={() => setPromote(true)}>Copy region to…</button>}
          {builder && <button className={connect ? 'ghost small' : 'small'} onClick={() => setConnect(!connect)}>
            {connect ? 'Hide' : 'Connect Claude'}</button>}</div></div>
      {connect && <section className="card"><h2>Connect Claude</h2><ConnectClaude server={server} /></section>}
      {fresh && (
        <a className="card finish-setup big" href={setupHref(id)}>
          <b>{can(server.role, 'owner') ? 'Finish setting up this server' : 'This server hasn\u2019t connected yet'}</b>
          <span className="muted">{can(server.role, 'owner')
            ? 'Install the plugin you downloaded and restart the server. The setup page shows when it connects.'
            : 'Its owner still has to install the plugin.'}</span>
          <span aria-hidden="true">→</span>
        </a>
      )}
      <div className="stack">
        {!fresh && <>
          <section className="card"><h2>Drafts</h2><Drafts id={id} server={server} /></section>
          <section className="card"><h2>Jobs</h2><div><Jobs server={server} recent={10} /></div></section>
          <History id={id} server={server} />
        </>}
        <MembersCard server={server} />
        {can(server.role, 'admin') && <SettingsCard server={server} />}
      </div>
      {promote && <PromoteDialog from={id} servers={servers} onClose={() => setPromote(false)}
        source={{ world: server.info?.worlds?.[0] || 'world' }} />}
    </>
  );
}

/** Rename (admins); download the plugin again, replace its key and delete (owner). */
function SettingsCard({ server }: { server: Server }) {
  const [error, setError] = useState('');
  const [note, setNote] = useState('');
  const owner = server.role === 'owner';
  const base = serverApi(server.id);

  const rename = async (ev: FormEvent<HTMLFormElement>) => {
    ev.preventDefault();
    setError('');
    try {
      const name = String(new FormData(ev.currentTarget).get('name') || '');
      await api(base, { method: 'PATCH', body: JSON.stringify({ name }) });
      setNote('Renamed.');
    } catch (ex) {
      setError(message(ex));
    }
  };
  const download = async () => {
    setError('');
    try {
      await downloadPlugin(server.id);
    } catch (ex) {
      setError(message(ex));
    }
  };
  const rotate = async () => {
    if (!confirm('Disconnect this server and give it a new key? The plugin on the server stops working until you install a '
      + 'fresh download from here. Do this if the plugin file got into the wrong hands.')) return;
    setError('');
    try {
      await api(`${base}/token`, { method: 'POST' });
      setNote('The old key no longer works. Download the plugin again and install it on the server.');
    } catch (ex) {
      setError(message(ex));
    }
  };
  const remove = async () => {
    if (prompt(`Delete "${server.name}" for everyone? Type its id (${server.id}) to confirm.`) !== server.id) return;
    try {
      await api(base, { method: 'DELETE' });
      go('#/');
    } catch (ex) {
      setError(message(ex));
    }
  };

  return (
    <section className="card"><h2>Settings</h2>
      <form className="toolbar" onSubmit={rename}>
        <input type="text" name="name" defaultValue={server.name} maxLength={64} required aria-label="Server name" />
        <button type="submit" className="ghost">Rename</button>
      </form>
      {owner && (
        <>
          <h3>Plugin</h3>
          <p className="muted">Updating the plugin, or moved to a new machine? Download it again and replace the old file in
            the server&apos;s <code>plugins</code> folder. It stays connected to this page.</p>
          <div className="toolbar">
            <button className="ghost" onClick={download}>Download AIBuilder.jar</button>
            <button className="ghost" onClick={rotate}>Disconnect and make a new key</button>
            <span className="spacer" />
            <button className="danger" onClick={remove}>Delete server</button>
          </div>
        </>
      )}
      {note && <p className="muted">{note}</p>}
      {error && <p className="error">{error}</p>}
    </section>
  );
}

function Drafts({ id, server }: { id: string; server: Server }) {
  const [list, setList] = useState<DraftList | null>(null);
  const [error, setError] = useState('');
  // The live state only has the open drafts: when those change, read the list again.
  const key = JSON.stringify(server.drafts || []);
  useEffect(() => {
    if (!server.online) return;
    let current = true;
    api<DraftList>(`${serverApi(id)}/drafts`).then(
      r => { if (current) { setList(r); setError(''); } },
      ex => { if (current) setError(message(ex)); });
    return () => { current = false; };
  }, [id, key, server.online]);

  if (!server.online) return <p className="empty">{OFFLINE}</p>;
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

function History({ id, server }: { id: string; server: Server }) {
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
  // A finished job, or an entry the plugin just archived, changes the first page; so does going on or offline.
  const newest = server.recent[0] ? server.recent[0].id + server.recent[0].status : '';
  useEffect(() => { load(null); }, [load, newest, server.historyRev, server.online]);

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
