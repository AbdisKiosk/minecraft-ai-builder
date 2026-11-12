import { useCallback, useEffect, useState } from 'react';
import { api, message, serverApi } from '../lib/api';
import { ago } from '../lib/format';
import { serverHref } from '../lib/routes';
import { claudeCommand, type AccessToken } from './ConnectClaude';
import { CopyButton } from './shared';

interface Row { server: string; name: string; created: number; lastUsed: number | null }

/** The user's access tokens: one per server, each shown again on request. */
export default function TokensPage() {
  const [rows, setRows] = useState<Row[] | null>(null);
  const [shown, setShown] = useState<Record<string, string | null>>({});
  const [error, setError] = useState('');

  const load = useCallback(() => {
    api<{ tokens: Row[] }>('/api/tokens').then(r => { setRows(r.tokens); setError(''); }, ex => setError(message(ex)));
  }, []);
  useEffect(load, [load]);

  const show = async (row: Row) => {
    if (row.server in shown) {
      setShown(({ [row.server]: _, ...rest }) => rest);
      return;
    }
    try {
      const r = await api<{ accessToken: AccessToken | null }>(`${serverApi(row.server)}/access-token`);
      setShown(s => ({ ...s, [row.server]: r.accessToken?.token ?? null }));
    } catch (ex) {
      setError(message(ex));
    }
  };

  const revoke = async (row: Row) => {
    if (!confirm(`Revoke your token for "${row.name}"? Claude Code and scripts using it stop working there. `
      + 'You can make a new one with Connect Claude on the server’s page.')) return;
    try {
      await api(`${serverApi(row.server)}/access-token`, { method: 'DELETE' });
      setShown(({ [row.server]: _, ...rest }) => rest);
      load();
    } catch (ex) {
      setError(message(ex));
    }
  };

  return (
    <>
      <div className="page-head"><div><h1>Access tokens</h1>
        <div className="muted">You have one token per server. It lets Claude Code and build scripts build as you on that server
          only. Get one with <b>Connect Claude</b> on a server&apos;s page.</div></div></div>
      {error && <p className="error">{error}</p>}
      <section className="card"><div className="table-wrap"><table>
        <thead><tr><th>Server</th><th>Made</th><th>Last used</th><th /></tr></thead>
        <tbody>
          {!rows ? <tr><td colSpan={4} className="muted">Loading…</td></tr>
            : !rows.length ? <tr><td colSpan={4} className="empty">No tokens yet. Open a server and press Connect Claude.</td></tr>
            : rows.map(r => {
              const token = shown[r.server];
              return [
                <tr key={r.server}>
                  <td><a href={serverHref(r.server)}>{r.name}</a></td>
                  <td>{ago(r.created)}</td><td>{r.lastUsed ? ago(r.lastUsed) : 'never'}</td>
                  <td className="right"><span className="toolbar" style={{ margin: 0, justifyContent: 'flex-end' }}>
                    <button className="ghost small" onClick={() => show(r)}>{r.server in shown ? 'Hide' : 'Show command'}</button>
                    <button className="ghost small" onClick={() => revoke(r)}>Revoke</button>
                  </span></td>
                </tr>,
                r.server in shown && (
                  <tr key={r.server + '/command'}><td colSpan={4}>
                    {token ? <div className="command"><pre>{claudeCommand({ id: r.server, name: r.name }, token)}</pre>
                      <CopyButton text={claudeCommand({ id: r.server, name: r.name }, token)} label="Copy command" /></div>
                      : <p className="muted">This token can&apos;t be shown any more. Make a new one with Connect Claude on
                        the <a href={serverHref(r.server)}>server&apos;s page</a>.</p>}
                  </td></tr>
                ),
              ];
            })}
        </tbody>
      </table></div></section>
    </>
  );
}
