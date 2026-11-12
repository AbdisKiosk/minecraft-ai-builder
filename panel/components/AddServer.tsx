import { useState, type FormEvent } from 'react';
import { api, downloadPlugin, message } from '../lib/api';
import { go, setupHref } from '../lib/routes';
import Modal from './Modal';

/**
 * The whole of adding a server: a name and one button. It makes the server, downloads the plugin that is already set
 * up for it, and opens the setup page, which waits for the server to connect.
 */
export function AddServerForm({ onCreated, big }: { onCreated: () => Promise<void> | void; big?: boolean }) {
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (ev: FormEvent<HTMLFormElement>) => {
    ev.preventDefault();
    setBusy(true);
    setError('');
    try {
      const name = String(new FormData(ev.currentTarget).get('name') || '');
      const made = await api<{ id: string }>('/api/servers', { method: 'POST', body: JSON.stringify({ name }) });
      // The setup page shows how this went; it offers the download again if the browser blocked it.
      downloadPlugin(made.id).catch(() => {});
      await onCreated();
      go(setupHref(made.id));
    } catch (ex) {
      setError(message(ex));
      setBusy(false);
    }
  };

  return (
    <form className={`add-server ${big ? 'big' : ''}`} onSubmit={submit}>
      <label htmlFor="server-name">What do you call your server?</label>
      <div className="add-row">
        <input id="server-name" type="text" name="name" maxLength={64} required autoFocus data-autofocus placeholder="e.g. Survival SMP" />
        <button type="submit" disabled={busy}>{busy ? 'Setting up…' : 'Add server & download plugin'}</button>
      </div>
      {error && <p className="error">{error}</p>}
    </form>
  );
}

export function AddServerDialog({ onClose, onCreated }: { onClose: () => void; onCreated: () => Promise<void> | void }) {
  return (
    <Modal className="card form-dialog" onClose={onClose}>
      <div className="dialog-head"><h2>Add a server</h2>
        <button type="button" className="ghost small" onClick={onClose} aria-label="Close">✕</button></div>
      <p className="muted">You get a copy of the plugin made for this server. Put it in the server&apos;s <code>plugins</code> folder,
        restart, and you&apos;re connected.</p>
      <AddServerForm onCreated={onCreated} />
    </Modal>
  );
}
