import { useCallback, useEffect, useRef, useState } from 'react';
import { api, message } from '../lib/api';
import { ago } from '../lib/format';
import { CopyButton } from './shared';

interface Link { uuid: string; name: string | null; linked: number }
interface Code { code: string; expires: number }

const LINKS = '/api/account/minecraft';

/**
 * The Minecraft accounts linked to the signed-in user, so `/aibuilder asset save` in game saves into their library.
 * A code is shown once; while it is waiting, the list is polled so a link made in game shows up.
 */
export default function MinecraftAccount() {
  const [links, setLinks] = useState<Link[] | null>(null);
  const [max, setMax] = useState(5);
  const [code, setCode] = useState<Code | null>(null);
  const [now, setNow] = useState(Date.now());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  /** How many links there were when the shown code was made: one more means it was used in game. */
  const before = useRef<number | null>(null);

  const load = useCallback(() => {
    api<{ links: Link[]; max: number }>(LINKS).then(r => {
      setLinks(r.links);
      if (before.current !== null && r.links.length > before.current) {
        before.current = null;
        setCode(null);
      }
      setMax(r.max);
      setError('');
    }, ex => setError(message(ex)));
  }, []);
  useEffect(load, [load]);

  const waiting = !!code && code.expires > now;
  useEffect(() => {
    if (!waiting) return;
    const t = setInterval(() => { setNow(Date.now()); load(); }, 5000);
    return () => clearInterval(t);
  }, [waiting, load]);

  const makeCode = async () => {
    setBusy(true);
    try {
      const made = await api<Code>(LINKS, { method: 'POST' });
      before.current = links?.length ?? 0;
      setCode(made);
      setNow(Date.now());
      setError('');
    } catch (ex) {
      setError(message(ex));
    } finally {
      setBusy(false);
    }
  };

  const unlink = async (link: Link) => {
    if (!confirm(`Unlink ${link.name ?? link.uuid}? Saving assets in game as this player stops working until it is linked again.`)) return;
    try {
      await api(`${LINKS}/${encodeURIComponent(link.uuid)}`, { method: 'DELETE' });
      load();
    } catch (ex) {
      setError(message(ex));
    }
  };

  const command = code ? `/aibuilder link ${code.code}` : '';
  return (
    <section className="card">
      <h2>Minecraft account</h2>
      <p className="muted">Link your Minecraft account to save assets from a WorldEdit selection in game with{' '}
        <code>/aibuilder asset save &lt;name&gt; [description]</code>. Saves go into your library, from servers where you
        are a builder. Make a code, then type the command it shows on such a server within ten minutes.</p>
      {error && <p className="error">{error}</p>}
      {waiting ? (
        <div className="command">
          <pre>{command}</pre>
          <CopyButton text={command} label="Copy command" />
          <span className="muted"> Works once, for {Math.max(1, Math.ceil((code!.expires - now) / 60_000))} more minutes.
            Making another code cancels this one.</span>
        </div>
      ) : (
        <div className="toolbar">
          <button onClick={makeCode} disabled={busy || (links !== null && links.length >= max)}>Make a link code</button>
          {links !== null && links.length >= max && <span className="muted">You have {max} linked; unlink one to add another.</span>}
          {code && <span className="muted">The last code expired.</span>}
        </div>
      )}
      <div className="table-wrap"><table>
        <thead><tr><th>Player</th><th>UUID</th><th>Linked</th><th /></tr></thead>
        <tbody>
          {!links ? <tr><td colSpan={4} className="muted">Loading…</td></tr>
            : !links.length ? <tr><td colSpan={4} className="empty">No Minecraft account linked yet.</td></tr>
            : links.map(l => (
              <tr key={l.uuid}>
                <td>{l.name ?? <span className="muted">unknown</span>}</td>
                <td><code>{l.uuid}</code></td>
                <td>{ago(l.linked)}</td>
                <td className="right"><button className="ghost small" onClick={() => unlink(l)}>Unlink</button></td>
              </tr>
            ))}
        </tbody>
      </table></div>
    </section>
  );
}
