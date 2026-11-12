import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, message } from '../lib/api';
import { ago } from '../lib/format';
import { CopyButton } from './shared';

interface Token { id: string; label: string; created: number; lastUsed: number | null }

/** The user's client tokens for Claude Code and scripts. */
export default function TokensPage() {
  const [tokens, setTokens] = useState<Token[] | null>(null);
  const [error, setError] = useState('');
  const [made, setMade] = useState<{ label: string; token: string } | null>(null);

  const load = useCallback(() => {
    api<{ tokens: Token[] }>('/api/tokens').then(r => { setTokens(r.tokens); setError(''); }, ex => setError(message(ex)));
  }, []);
  useEffect(load, [load]);

  const create = async (ev: FormEvent<HTMLFormElement>) => {
    ev.preventDefault();
    const form = ev.currentTarget;
    setError('');
    try {
      const label = String(new FormData(form).get('label') || '');
      setMade(await api('/api/tokens', { method: 'POST', body: JSON.stringify({ label }) }));
      form.reset();
      load();
    } catch (ex) {
      setError(message(ex));
    }
  };

  const revoke = async (t: Token) => {
    if (!confirm(`Revoke "${t.label}"? Claude Code and scripts using it stop working.`)) return;
    try {
      await api(`/api/tokens/${t.id}`, { method: 'DELETE' });
      load();
    } catch (ex) {
      setError(message(ex));
    }
  };

  return (
    <>
      <div className="page-head"><div><h1>Access tokens</h1>
        <div className="muted">A token lets Claude Code and build scripts act as you on every server where you are builder or above.
          Treat it like a password.</div></div></div>
      <div className="stack">
        <section className="card"><h2>New token</h2>
          <form className="toolbar" onSubmit={create}>
            <input type="text" name="label" placeholder="laptop" maxLength={64} required />
            <button type="submit">Create token</button>
          </form>
          {made && (
            <div className="connect">
              <p>Token <b>{made.label}</b>, shown only now:</p>
              <pre>{made.token}</pre>
              <div className="toolbar"><CopyButton text={made.token} /></div>
              <p className="muted">Use it with a server&apos;s address from its page:</p>
              <pre>export AIBUILDER_URL={window.location.origin}/s/&lt;server id&gt; AIBUILDER_TOKEN={made.token}</pre>
            </div>
          )}
          {error && <p className="error">{error}</p>}
        </section>
        <section className="card"><h2>Your tokens</h2><div className="table-wrap"><table>
          <thead><tr><th>Label</th><th>Created</th><th>Last used</th><th /></tr></thead>
          <tbody>
            {!tokens ? <tr><td colSpan={4} className="muted">Loading…</td></tr>
              : !tokens.length ? <tr><td colSpan={4} className="empty">No tokens yet.</td></tr>
              : tokens.map(t => (
                <tr key={t.id}><td>{t.label}</td><td>{ago(t.created)}</td><td>{t.lastUsed ? ago(t.lastUsed) : 'never'}</td>
                  <td className="right"><button className="ghost small" onClick={() => revoke(t)}>Revoke</button></td></tr>
              ))}
          </tbody></table></div></section>
      </div>
    </>
  );
}
