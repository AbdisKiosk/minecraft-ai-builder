import { useEffect, useState } from 'react';
import { api, assetApi, message } from '../lib/api';
import { can, type AssetShare, type LibraryAsset } from '../lib/types';
import type { Servers } from '../lib/usePanel';
import Modal from './Modal';

/** Shares one of the user's assets with servers they build on (GET/POST/DELETE /api/assets/<id>/shares). */
export default function ShareAssetDialog({ asset, servers, onClose, onChanged }:
    { asset: LibraryAsset; servers: Servers; onClose: () => void; onChanged: () => void }) {
  const [shares, setShares] = useState<AssetShare[] | null>(null);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const url = `${assetApi(asset.id)}/shares`;

  useEffect(() => {
    api<{ shares: AssetShare[] }>(url).then(r => setShares(r.shares), ex => setError(message(ex)));
  }, [url]);

  const targets = [...servers.values()].filter(s => can(s.role, 'builder'))
    .sort((a, b) => a.name.localeCompare(b.name) || a.id.localeCompare(b.id));
  // Shares with servers the user no longer builds on do nothing until they do again, but can still be removed.
  const stale = (shares ?? []).filter(s => !targets.some(t => t.id === s.server));
  const on = new Set((shares ?? []).map(s => s.server));

  const toggle = async (server: string, share: boolean) => {
    setBusy(server);
    setError('');
    try {
      if (share) setShares((await api<{ shares: AssetShare[] }>(url, { method: 'POST', body: JSON.stringify({ server }) })).shares);
      else {
        await api(`${url}?server=${encodeURIComponent(server)}`, { method: 'DELETE' });
        setShares(list => (list ?? []).filter(s => s.server !== server));
      }
      onChanged();
    } catch (ex) {
      setError(message(ex));
    } finally {
      setBusy('');
    }
  };

  return (
    <Modal className="form-dialog" onClose={onClose}>
      <div className="dialog-head"><h2>Share {asset.name}</h2></div>
      <p className="muted" style={{ margin: 0 }}>Builders, admins and the owner of a server you share with can see this asset,
        place it into a draft and let Claude paste it there by name. Viewers cannot. Only you can change, share or delete it,
        and it still counts against your library&apos;s quota.</p>
      {!shares ? (!error && <p className="muted">Loading…</p>)
        : !targets.length && !stale.length ? <p className="error">You are not a builder on any server.</p> : <>
          {targets.map(s => (
            <label className="check" key={s.id}>
              <input type="checkbox" checked={on.has(s.id)} disabled={!!busy} onChange={e => toggle(s.id, e.target.checked)} />
              {s.name}{busy === s.id && <span className="muted"> saving…</span>}
            </label>
          ))}
          {stale.map(s => (
            <div className="toolbar" style={{ margin: 0 }} key={s.server}>
              <span>{s.name} <span className="muted">(not in use: you are no longer a builder there)</span></span>
              <span className="spacer" />
              <button className="ghost small" disabled={!!busy} onClick={() => toggle(s.server, false)}>Stop sharing</button>
            </div>
          ))}
        </>}
      {error && <p className="error">{error}</p>}
      <div className="toolbar" style={{ margin: 0, justifyContent: 'flex-end' }}>
        <button type="button" onClick={onClose}>Done</button>
      </div>
    </Modal>
  );
}
