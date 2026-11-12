import { useState, type FormEvent } from 'react';
import { api, message } from '../lib/api';
import { go, serverHref } from '../lib/routes';
import Modal from './Modal';
import { PluginDownload, RelaySetup, type RelayConfig } from './shared';

/** Makes a server and shows its relay settings, which are only shown this once. */
export default function NewServerDialog({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [made, setMade] = useState<{ id: string; relay: RelayConfig } | null>(null);

  const submit = async (ev: FormEvent<HTMLFormElement>) => {
    ev.preventDefault();
    setBusy(true);
    setError('');
    try {
      const name = String(new FormData(ev.currentTarget).get('name') || '');
      setMade(await api('/api/servers', { method: 'POST', body: JSON.stringify({ name }) }));
      onCreated();
    } catch (ex) {
      setError(message(ex));
    } finally {
      setBusy(false);
    }
  };

  if (made) {
    return (
      <Modal className="card form-dialog" onClose={onClose}>
        <h2>Connect your server</h2>
        <PluginDownload />
        <RelaySetup relay={made.relay} />
        <div className="toolbar">
          <button onClick={() => { onClose(); go(serverHref(made.id)); }}>Done</button>
        </div>
      </Modal>
    );
  }
  return (
    <Modal className="card form-dialog" onClose={onClose}>
      <form onSubmit={submit}>
        <h2>New server</h2>
        <label>Name <input type="text" name="name" maxLength={64} required autoFocus placeholder="Survival SMP" /></label>
        <p className="error">{error}</p>
        <div className="toolbar">
          <button type="submit" disabled={busy}>{busy ? 'Creating…' : 'Create'}</button>
          <button type="button" className="ghost" onClick={onClose}>Cancel</button>
        </div>
      </form>
    </Modal>
  );
}
