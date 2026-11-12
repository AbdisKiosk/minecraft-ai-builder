import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, assetApi, assetsApi, message } from '../lib/api';
import { ago, num } from '../lib/format';
import { draftHref, go } from '../lib/routes';
import { can, type Asset, type AssetList } from '../lib/types';
import type { Servers } from '../lib/usePanel';
import Modal from './Modal';
import { Viewer3d } from './Pictures';
import { parseTags } from './SaveAssetDialog';
import { Badge } from './shared';
import UploadAssetDialog from './UploadAssetDialog';

const NAME = /^[a-z0-9_-]{1,40}$/;
const mb = (bytes: number) => (bytes / 1024 ** 2).toFixed(bytes < 10 * 1024 ** 2 ? 1 : 0) + ' MB';

/** The user's asset library: pieces saved from any server, placed into a draft on any server they build on. */
export default function AssetsPage({ servers }: { servers: Servers }) {
  const [list, setList] = useState<AssetList | null>(null);
  const [error, setError] = useState('');
  const [open, setOpen] = useState<string | null>(null);
  const [editing, setEditing] = useState<Asset | null>(null);
  const [placing, setPlacing] = useState<Asset | null>(null);
  const [uploading, setUploading] = useState(false);
  // The 3D view needs room to drag in.
  const [small, setSmall] = useState(false);
  useEffect(() => setSmall(window.matchMedia('(max-width: 700px)').matches), []);

  const load = useCallback(() => {
    api<AssetList>(assetsApi).then(r => { setList(r); setError(''); }, ex => setError(message(ex)));
  }, []);
  useEffect(load, [load]);

  const remove = async (a: Asset) => {
    if (!confirm(`Delete the asset "${a.name}"? This cannot be undone.`)) return;
    try {
      await api(assetApi(a.id), { method: 'DELETE' });
      if (open === a.id) setOpen(null);
      load();
    } catch (ex) {
      setError(message(ex));
    }
  };

  const shown = list?.assets.find(a => a.id === open);
  return (
    <>
      <div className="page-head"><div><h1>Assets</h1>
        <div className="muted">Pieces you saved (a window, a pillar, a tree), kept with your account rather than a server.
          Save one from a server&apos;s <b>Map</b> with <b>Save as asset</b>, or upload a WorldEdit <span className="mono">.schem</span>.
          {list && <> {list.quota.count} of {list.quota.maxCount} assets, {mb(list.quota.bytes)} of {mb(list.quota.maxBytes)}.</>}</div></div>
        <button onClick={() => setUploading(true)}>Upload .schem</button></div>
      {error && <p className="error">{error}</p>}
      {shown && (
        <section className="card" style={{ marginBottom: 16 }}>
          <div className="toolbar"><h2 style={{ margin: 0 }} className="mono">{shown.name}</h2><span className="spacer" />
            <button className="ghost small" onClick={() => setOpen(null)}>Close</button></div>
          <div className="renders one">{small ? <p className="muted">The 3D view needs a wider screen.</p>
            : shown.mc ? <Viewer3d key={shown.id} url={`${assetApi(shown.id)}/blocks`} mc={shown.mc} asset />
            : <p className="muted">3D needs the Minecraft version the asset was saved on, which is not known.</p>}</div>
          <p className="muted" style={{ margin: '10px 0 0' }}>Coordinates are the asset&apos;s own: 0 0 0 is its lowest corner.</p>
        </section>
      )}
      {!list ? (!error && <p className="muted">Loading…</p>)
        : !list.assets.length ? <section className="card"><p className="empty">No assets yet. Open a server&apos;s Map, press
          <b> Save as asset</b> and click two corners around what you want to keep.</p></section>
        : (
          <div className="grid">
            {list.assets.map(a => (
              <section className="card" key={a.id}>
                <div className="server-head"><h2 className="mono">{a.name}</h2>
                  <Badge>{a.mc ? 'Minecraft ' + a.mc : 'version unknown'}</Badge></div>
                <div className="meta">{a.size.join(' × ')} · {num(a.blocks)} blocks · {mb(a.bytes)} · saved {ago(a.created)}</div>
                {a.description ? <p style={{ margin: '0 0 10px', overflowWrap: 'anywhere' }}>{a.description}</p>
                  : <p className="muted" style={{ margin: '0 0 10px' }}>No description.</p>}
                {a.tags.length > 0 && <div className="pills" style={{ marginBottom: 12 }}>{a.tags.map(t => <Badge key={t}>{t}</Badge>)}</div>}
                <div className="toolbar" style={{ margin: 0 }}>
                  <button className={open === a.id ? '' : 'ghost'} onClick={() => {
                    setOpen(open === a.id ? null : a.id);
                    window.scrollTo({ top: 0, behavior: 'smooth' });
                  }}>3D view</button>
                  <button className="ghost" onClick={() => setPlacing(a)}>Place…</button>
                  <span className="spacer" />
                  <button className="ghost small" onClick={() => setEditing(a)}>Edit</button>
                  <button className="ghost small" onClick={() => remove(a)}>Delete</button>
                </div>
              </section>
            ))}
          </div>
        )}
      {editing && <EditAssetDialog asset={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); load(); }} />}
      {placing && <PlaceAssetDialog asset={placing} servers={servers} onClose={() => setPlacing(null)} />}
      {uploading && <UploadAssetDialog onClose={() => setUploading(false)} onSaved={load} />}
    </>
  );
}

function EditAssetDialog({ asset, onClose, onSaved }: { asset: Asset; onClose: () => void; onSaved: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const submit = async (ev: FormEvent<HTMLFormElement>) => {
    ev.preventDefault();
    const f = new FormData(ev.currentTarget);
    const name = String(f.get('name') || '').trim().toLowerCase();
    if (!NAME.test(name)) return setError('Names are 1-40 of a-z, 0-9, _ and -.');
    setBusy(true);
    setError('');
    try {
      await api(assetApi(asset.id), { method: 'PATCH', body: JSON.stringify({
        name, description: String(f.get('description') || ''), tags: parseTags(String(f.get('tags') || '')) }) });
      onSaved();
    } catch (ex) {
      setError(message(ex));
      setBusy(false);
    }
  };

  return (
    <Modal className="form-dialog" onClose={onClose}>
      <form onSubmit={submit}>
        <div className="dialog-head"><h2>Edit asset</h2></div>
        <label>Name <input type="text" name="name" defaultValue={asset.name} maxLength={40} pattern="[a-zA-Z0-9_\-]{1,40}" required data-autofocus /></label>
        <label>Description <textarea name="description" maxLength={1000} rows={3} defaultValue={asset.description} /></label>
        <label>Tags <input type="text" name="tags" defaultValue={asset.tags.join(', ')} placeholder="window, oak" /></label>
        {error && <p className="error">{error}</p>}
        <div className="toolbar" style={{ margin: 0, justifyContent: 'flex-end' }}>
          <button type="button" className="ghost" onClick={onClose}>Cancel</button>
          <button type="submit" disabled={busy}>{busy ? 'Saving…' : 'Save'}</button>
        </div>
      </form>
    </Modal>
  );
}

/** Places an asset into a draft on a server, through the same import as promote. */
function PlaceAssetDialog({ asset, servers, onClose }: { asset: Asset; servers: Servers; onClose: () => void }) {
  const targets = [...servers.values()].filter(s => can(s.role, 'builder')).sort((a, b) => a.name.localeCompare(b.name) || a.id.localeCompare(b.id));
  const [to, setTo] = useState(targets.find(s => s.online)?.id ?? '');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const worlds = servers.get(to)?.info?.worlds ?? [];

  const submit = async (ev: FormEvent<HTMLFormElement>) => {
    ev.preventDefault();
    const f = new FormData(ev.currentTarget);
    const origin = String(f.get('origin') || '').trim().split(/[\s,]+/).map(Number);
    if (origin.length !== 3 || !origin.every(Number.isInteger)) return setError('The corner needs 3 whole numbers.');
    setBusy(true);
    setError('');
    try {
      const r = await api<{ draft?: string; error?: string; skipped?: string[] }>(`${assetApi(asset.id)}/place`, { method: 'POST',
        body: JSON.stringify({ server: to, world: String(f.get('world') || ''), origin, air: f.has('air'), skipUnknown: f.has('skipUnknown') }) });
      if (r.error) throw new Error(r.error);
      onClose();
      if (r.draft) go(draftHref(to, r.draft));
      else alert('Nothing changed on that server: it already has these blocks there.');
    } catch (ex) {
      setError(message(ex));
      setBusy(false);
    }
  };

  return (
    <Modal className="form-dialog" onClose={onClose}>
      <form onSubmit={submit}>
        <div className="dialog-head"><h2>Place {asset.name}</h2></div>
        <p className="muted" style={{ margin: 0 }}>Puts the asset ({asset.size.join(' × ')} blocks) into a draft on the server.
          Nothing is placed in the world until someone approves that draft.</p>
        {!targets.length ? <p className="error">You are not a builder on any server.</p> : <>
          <label>Server <select name="to" required value={to} onChange={e => setTo(e.target.value)}>
            {targets.map(s => <option key={s.id} value={s.id} disabled={!s.online}>{s.name}{s.online ? '' : ' (offline)'}</option>)}
          </select></label>
          <label>World {worlds.length
            ? <select key={to} name="world" required defaultValue={worlds[0]}>{worlds.map(w => <option key={w}>{w}</option>)}</select>
            : <input type="text" name="world" defaultValue="world" required />}</label>
          <label>Lowest corner (x y z) <input type="text" name="origin" className="mono" placeholder="100 64 -20" required /></label>
          <label className="check"><input type="checkbox" name="air" defaultChecked /> place air too (clears what is in the way)</label>
          <label className="check"><input type="checkbox" name="skipUnknown" /> leave out blocks that server does not have</label>
        </>}
        {error && <p className="error">{error}</p>}
        <div className="toolbar" style={{ margin: 0, justifyContent: 'flex-end' }}>
          <button type="button" className="ghost" onClick={onClose}>Cancel</button>
          <button type="submit" disabled={busy || !to}>{busy ? 'Placing…' : 'Create draft'}</button>
        </div>
      </form>
    </Modal>
  );
}
