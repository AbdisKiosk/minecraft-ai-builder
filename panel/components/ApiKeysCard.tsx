import { useEffect, useState, type FormEvent } from 'react';
import { api, message } from '../lib/api';
import { PROVIDERS, type Provider, type ProviderId } from '../lib/chat';
import { ago } from '../lib/format';

interface Key { hint: string; created: number; usable: boolean }
type Keys = Record<ProviderId, Key | null>;

const KEYS = '/api/account/api-keys';

const ABOUT: Record<ProviderId, string> = {
  anthropic: 'Claude from Anthropic’s own API, billed by Anthropic. A Claude subscription does not include an API key.',
  openrouter: 'Claude through OpenRouter, billed from your OpenRouter credit. OpenRouter sees everything the chat sends, as Anthropic does.',
};

/**
 * The user's API keys for chatting with Claude on a server's page, one per provider. Only a key's last four
 * characters come back.
 */
export default function ApiKeysCard() {
  const [keys, setKeys] = useState<Keys | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    api<{ keys: Keys }>(KEYS).then(r => setKeys(r.keys), ex => setError(message(ex)));
  }, []);

  return (
    <section className="card">
      <h2>API keys for the chat</h2>
      <p className="muted">Chatting with Claude on a server&apos;s page uses your own key, and the provider bills you for what it
        uses. Add one for either provider; each chat picks one. The panel stores keys encrypted and never shows them again.</p>
      {error && <p className="error">{error}</p>}
      {!keys ? <p className="muted">Loading…</p> : PROVIDERS.map(p => (
        <ProviderKey key={p.id} provider={p} current={keys[p.id]} onChange={setKeys} />
      ))}
    </section>
  );
}

function ProviderKey({ provider: p, current, onChange }: { provider: Provider; current: Key | null; onChange: (keys: Keys) => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const url = `${KEYS}/${p.id}`;

  const save = async (ev: FormEvent<HTMLFormElement>) => {
    ev.preventDefault();
    const form = ev.currentTarget;
    setBusy(true);
    setError('');
    try {
      const r = await api<{ keys: Keys }>(url, { method: 'PUT', body: JSON.stringify({ key: String(new FormData(form).get('key') || '') }) });
      onChange(r.keys);
      form.reset();
    } catch (ex) {
      setError(message(ex));
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!confirm(`Remove your ${p.name} key from the panel? Chats that use ${p.name} stop working until you add one again.`)) return;
    setError('');
    try {
      await api(url, { method: 'DELETE' });
      onChange(await api<{ keys: Keys }>(KEYS).then(r => r.keys));
    } catch (ex) {
      setError(message(ex));
    }
  };

  return (
    <div className="provider-key">
      <h3>{p.name}</h3>
      <p className="muted">{ABOUT[p.id]} Make a key <a href={p.keysUrl} target="_blank" rel="noreferrer">on {p.name}&apos;s site</a>.</p>
      {current && (
        <div className="toolbar">
          <span>Key ending in <code>{current.hint}</code>, added {ago(current.created)}.</span>
          {!current.usable && <span className="error">It can no longer be read. Enter it again.</span>}
          <span className="spacer" />
          <button className="ghost small" onClick={remove}>Remove</button>
        </div>
      )}
      <form className="toolbar" onSubmit={save}>
        <input type="password" name="key" placeholder={`${p.keyPrefix}…`} autoComplete="off" required aria-label={`${p.name} API key`}
          style={{ flex: 1, minWidth: 220 }} />
        <button type="submit" disabled={busy}>{busy ? 'Checking…' : current ? 'Replace key' : 'Save key'}</button>
      </form>
      {error && <p className="error">{error}</p>}
    </div>
  );
}
