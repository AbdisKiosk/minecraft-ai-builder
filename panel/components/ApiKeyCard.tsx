import { useEffect, useState, type FormEvent } from 'react';
import { api, message } from '../lib/api';
import { ago } from '../lib/format';

interface Key { hint: string; created: number; usable: boolean }

const KEY = '/api/account/api-key';

/** The user's Anthropic API key for chatting with Claude on the panel. Only its last four characters come back. */
export default function ApiKeyCard() {
  const [key, setKey] = useState<Key | null | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    api<{ key: Key | null }>(KEY).then(r => setKey(r.key), ex => setError(message(ex)));
  }, []);

  const save = async (ev: FormEvent<HTMLFormElement>) => {
    ev.preventDefault();
    const form = ev.currentTarget;
    setBusy(true);
    setError('');
    try {
      const r = await api<{ key: Key }>(KEY, { method: 'PUT', body: JSON.stringify({ key: String(new FormData(form).get('key') || '') }) });
      setKey(r.key);
      form.reset();
    } catch (ex) {
      setError(message(ex));
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!confirm('Remove your API key from the panel? Chatting with Claude here stops working until you add one again.')) return;
    try {
      await api(KEY, { method: 'DELETE' });
      setKey(null);
    } catch (ex) {
      setError(message(ex));
    }
  };

  return (
    <section className="card">
      <h2>Anthropic API key</h2>
      <p className="muted">Chatting with Claude on a server&apos;s page uses your own Anthropic API key, and Anthropic bills you for
        what it uses. Make one in the <a href="https://platform.claude.com/settings/keys" target="_blank" rel="noreferrer">Claude
        Console</a>; a Claude subscription does not include one. The panel stores it encrypted and never shows it again.</p>
      {key === undefined ? <p className="muted">Loading…</p> : key && (
        <div className="toolbar">
          <span>Key ending in <code>{key.hint}</code>, added {ago(key.created)}.</span>
          {!key.usable && <span className="error">It can no longer be read. Enter it again.</span>}
          <span className="spacer" />
          <button className="ghost small" onClick={remove}>Remove</button>
        </div>
      )}
      <form className="toolbar" onSubmit={save}>
        <input type="password" name="key" placeholder="sk-ant-…" autoComplete="off" required aria-label="Anthropic API key"
          style={{ flex: 1, minWidth: 220 }} />
        <button type="submit" disabled={busy}>{busy ? 'Checking…' : key ? 'Replace key' : 'Save key'}</button>
      </form>
      {error && <p className="error">{error}</p>}
    </section>
  );
}
