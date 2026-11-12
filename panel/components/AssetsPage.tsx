import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { anchorInside, cornerFromAnchor, parseAnchor, parsePosition } from '../lib/anchor';
import { api, assetApi, assetsApi, message } from '../lib/api';
import { ago, num } from '../lib/format';
import { capped, placeWarning } from '../lib/mcVersion';
import { draftHref, go } from '../lib/routes';
import { can, type Asset, type AssetList, type Facing, type LibraryAsset } from '../lib/types';
import type { Servers } from '../lib/usePanel';
import Modal from './Modal';
import { Viewer3d } from './Pictures';
import { AnchorFields, parseTags } from './SaveAssetDialog';
import { Badge } from './shared';
import ShareAssetDialog from './ShareAssetDialog';
import UploadAssetDialog from './UploadAssetDialog';

const NAME = /^[a-z0-9_-]{1,40}$/;
const mb = (bytes: number) => (bytes / 1024 ** 2).toFixed(bytes < 10 * 1024 ** 2 ? 1 : 0) + ' MB';

type Filter = 'all' | 'mine' | 'shared';

/**
 * The user's asset library: pieces saved from any server, placed into a draft on any server they build on, and other
 * users' assets shared with servers they build on (read-only).
 */
export default function AssetsPage({ servers }: { servers: Servers }) {
  const [list, setList] = useState<AssetList | null>(null);
  const [error, setError] = useState('');
  const [open, setOpen] = useState<string | null>(null);
  const [editing, setEditing] = useState<Asset | null>(null);
  const [placing, setPlacing] = useState<LibraryAsset | null>(null);
  const [sharing, setSharing] = useState<LibraryAsset | null>(null);
  const [uploading, setUploading] = useState(false);
  const [filter, setFilter] = useState<Filter>('all');
  // The 3D view needs room to drag in.
  const [small, setSmall] = useState(false);
  useEffect(() => setSmall(window.matchMedia('(max-width: 700px)').matches), []);

  const load = useCallback(() => {
    api<AssetList>(assetsApi).then(r => { setList(r); setError(''); }, ex => setError(message(ex)));
  }, []);
  useEffect(load, [load]);

  const remove = async (a: LibraryAsset) => {
    const shared = a.shared.length ? ' It is also taken away from the servers it is shared with.' : '';
    if (!confirm(`Delete the asset "${a.name}"?${shared} This cannot be undone.`)) return;
    try {
      await api(assetApi(a.id), { method: 'DELETE' });
      if (open === a.id) setOpen(null);
      load();
    } catch (ex) {
      setError(message(ex));
    }
  };

  const shown = list?.assets.find(a => a.id === open);
  const others = list?.assets.filter(a => !a.mine).length ?? 0;
  const listed = list?.assets.filter(a => filter === 'all' || a.mine === (filter === 'mine')) ?? [];
  const serverName = (id: string) => servers.get(id)?.name ?? id;
  return (
    <>
      <div className="page-head"><div><h1>Assets</h1>
        <div className="muted">Pieces you saved (a window, a pillar, a tree), kept with your account rather than a server.
          Save one from a server&apos;s <b>Map</b> with <b>Save as asset</b>, or upload a WorldEdit <span className="mono">.schem</span>.
          <b> Share…</b> one with a server and its builders can use it too.
          {list && <> {list.quota.count} of {list.quota.maxCount} assets, {mb(list.quota.bytes)} of {mb(list.quota.maxBytes)}.</>}</div></div>
        <button onClick={() => setUploading(true)}>Upload .schem</button></div>
      {error && <p className="error">{error}</p>}
      {shown && (
        <section className="card" style={{ marginBottom: 16 }}>
          <div className="toolbar"><h2 style={{ margin: 0 }} className="mono">{shown.name}</h2>
            {!shown.mine && <span className="muted">from {shown.owner}</span>}<span className="spacer" />
            <button className="ghost small" onClick={() => setOpen(null)}>Close</button></div>
          <div className="renders one">{small ? <p className="muted">The 3D view needs a wider screen.</p>
            : shown.mc ? <Viewer3d key={shown.id} url={`${assetApi(shown.id)}/blocks`} mc={shown.mc} asset
              anchor={shown.anchor} facing={shown.facing} />
            : <p className="muted">3D needs the Minecraft version the asset was saved on, which is not known.</p>}</div>
          <p className="muted" style={{ margin: '10px 0 0' }}>Coordinates are the asset&apos;s own: 0 0 0 is its lowest corner.
            {(shown.anchor || shown.facing) && ' The anchor block is outlined in blue; the blue arrow points the way the asset faces.'}</p>
        </section>
      )}
      {!list ? (!error && <p className="muted">Loading…</p>)
        : !list.assets.length ? <section className="card"><p className="empty">No assets yet. Open a server&apos;s Map, press
          <b> Save as asset</b> and click two corners around what you want to keep.</p></section>
        : (<>
          {others > 0 && (
            <div className="toolbar">
              {(['all', 'mine', 'shared'] as const).map(f => (
                <button key={f} className={filter === f ? 'small' : 'ghost small'} onClick={() => setFilter(f)}>
                  {f === 'all' ? 'All' : f === 'mine' ? 'Mine' : `Shared with me (${others})`}</button>
              ))}
            </div>
          )}
          {!listed.length && <section className="card"><p className="empty">{filter === 'mine'
            ? 'You have no assets of your own yet.' : 'Nobody has shared an asset with a server you build on.'}</p></section>}
          <div className="grid">
            {listed.map(a => (
              <section className="card" key={a.id}>
                <div className="server-head"><h2 className="mono">{a.name}</h2>
                  <Badge>{a.mc ? 'Minecraft ' + a.mc : 'version unknown'}</Badge></div>
                {(!a.mine || a.shared.length > 0) && (
                  <div className="pills" style={{ marginBottom: 8 }}>
                    {!a.mine && <Badge kind="running">shared by {a.owner}</Badge>}
                    {a.mine && <Badge kind="running">shared</Badge>}
                    <span className="muted" style={{ fontSize: 12 }}>{a.mine ? 'with ' : 'on '}{a.shared.map(serverName).join(', ')}</span>
                  </div>
                )}
                <div className="meta">{a.size.join(' × ')} · {num(a.blocks)} blocks · {mb(a.bytes)} · saved {ago(a.created)}
                  {(a.anchor || a.facing) && <> · anchor {a.anchor ? <span className="mono">{a.anchor.join(' ')}</span> : 'lowest corner'}
                    {a.facing && <>, faces {a.facing}</>}</>}</div>
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
                  {a.mine && <>
                    <button className="ghost small" onClick={() => setSharing(a)}>Share…</button>
                    <button className="ghost small" onClick={() => setEditing(a)}>Edit</button>
                    <button className="ghost small" onClick={() => remove(a)}>Delete</button>
                  </>}
                </div>
              </section>
            ))}
          </div>
        </>)}
      {editing && <EditAssetDialog asset={editing} small={small} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); load(); }} />}
      {placing && <PlaceAssetDialog asset={placing} servers={servers} onClose={() => setPlacing(null)} />}
      {sharing && <ShareAssetDialog asset={sharing} servers={servers} onClose={() => setSharing(null)} onChanged={load} />}
      {uploading && <UploadAssetDialog onClose={() => setUploading(false)} onSaved={load} />}
    </>
  );
}

/** Edits an asset; with room for it, a 3D view shows the anchor and facing as typed, and a click there sets the anchor. */
function EditAssetDialog({ asset, small, onClose, onSaved }: { asset: Asset; small: boolean; onClose: () => void; onSaved: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [anchorText, setAnchorText] = useState(asset.anchor?.join(' ') ?? '');
  const [facing, setFacing] = useState<string>(asset.facing ?? '');
  const typed = parseAnchor(anchorText);
  const viewer = !small && asset.mc;

  const submit = async (ev: FormEvent<HTMLFormElement>) => {
    ev.preventDefault();
    const f = new FormData(ev.currentTarget);
    const name = String(f.get('name') || '').trim().toLowerCase();
    if (!NAME.test(name)) return setError('Names are 1-40 of a-z, 0-9, _ and -.');
    if (typed === undefined) return setError('The anchor needs 3 whole numbers of 0 or more, or nothing.');
    if (typed && !anchorInside(typed, asset.size)) return setError(`The anchor must lie within 0 0 0 and ${asset.size.map(n => n - 1).join(' ')}.`);
    setBusy(true);
    setError('');
    try {
      await api(assetApi(asset.id), { method: 'PATCH', body: JSON.stringify({
        name, description: String(f.get('description') || ''), tags: parseTags(String(f.get('tags') || '')),
        anchor: typed, facing: facing || null }) });
      onSaved();
    } catch (ex) {
      setError(message(ex));
      setBusy(false);
    }
  };

  return (
    <Modal className={viewer ? 'form-dialog wide' : 'form-dialog'} onClose={onClose}>
      <form onSubmit={submit}>
        <div className="dialog-head"><h2>Edit asset</h2></div>
        <label>Name <input type="text" name="name" defaultValue={asset.name} maxLength={40} pattern="[a-zA-Z0-9_\-]{1,40}" required data-autofocus /></label>
        <label>Description <textarea name="description" maxLength={1000} rows={3} defaultValue={asset.description} /></label>
        <label>Tags <input type="text" name="tags" defaultValue={asset.tags.join(', ')} placeholder="window, oak" /></label>
        {viewer && <Viewer3d url={`${assetApi(asset.id)}/blocks`} mc={viewer} asset anchor={typed ?? null}
          facing={(facing || null) as Facing | null} onPickCell={cell => setAnchorText(cell.join(' '))} />}
        <AnchorFields anchor={anchorText} facing={facing} onAnchor={setAnchorText} onFacing={setFacing} pickable={!!viewer} />
        <p className="muted" style={{ margin: 0 }}>Within {asset.size.map(n => n - 1).join(' ')} for this asset.</p>
        {error && <p className="error">{error}</p>}
        <div className="toolbar" style={{ margin: 0, justifyContent: 'flex-end' }}>
          <button type="button" className="ghost" onClick={onClose}>Cancel</button>
          <button type="submit" disabled={busy}>{busy ? 'Saving…' : 'Save'}</button>
        </div>
      </form>
    </Modal>
  );
}

/**
 * Places an asset into a draft on a server, through the same import as promote: an own asset on any server the user
 * builds on, another user's only on the servers it is shared with.
 */
function PlaceAssetDialog({ asset, servers, onClose }: { asset: LibraryAsset; servers: Servers; onClose: () => void }) {
  const targets = [...servers.values()].filter(s => can(s.role, 'builder') && (asset.mine || asset.shared.includes(s.id)))
    .sort((a, b) => a.name.localeCompare(b.name) || a.id.localeCompare(b.id));
  const [to, setTo] = useState(targets.find(s => s.online)?.id ?? '');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  // Shown instead of the form when the import left blocks out, before going to the draft.
  const [placed, setPlaced] = useState<{ draft?: string; skipped: string[] } | null>(null);
  const worlds = servers.get(to)?.info?.worlds ?? [];
  const warning = to ? placeWarning(asset.mc, servers.get(to)?.info?.mc) : null;
  // With an anchor, the spot typed can be where the anchor block goes; the import itself always takes the lowest corner.
  const [by, setBy] = useState<'corner' | 'anchor'>(asset.anchor ? 'anchor' : 'corner');
  const [spot, setSpot] = useState('');
  const typed = parsePosition(spot);
  const corner = typed && (by === 'anchor' && asset.anchor ? cornerFromAnchor(typed, asset.anchor) : typed);

  const submit = async (ev: FormEvent<HTMLFormElement>) => {
    ev.preventDefault();
    const f = new FormData(ev.currentTarget);
    const origin = corner;
    if (!origin) return setError(`The ${by === 'anchor' ? 'anchor position' : 'corner'} needs 3 whole numbers.`);
    setBusy(true);
    setError('');
    try {
      const r = await api<{ draft?: string; error?: string; skipped?: string[] }>(`${assetApi(asset.id)}/place`, { method: 'POST',
        body: JSON.stringify({ server: to, world: String(f.get('world') || ''), origin, air: f.has('air'), skipUnknown: f.has('skipUnknown') }) });
      if (r.error) throw new Error(r.error);
      if (r.skipped?.length) return setPlaced({ draft: r.draft, skipped: r.skipped });
      onClose();
      if (r.draft) go(draftHref(to, r.draft));
      else alert('Nothing changed on that server: it already has these blocks there.');
    } catch (ex) {
      setError(message(ex));
      setBusy(false);
    }
  };

  if (placed) {
    const { shown, more } = capped(placed.skipped, 20);
    return (
      <Modal className="form-dialog" onClose={onClose}><div style={{ display: 'grid', gap: 12 }}>
        <div className="dialog-head"><h2>Placed {asset.name}</h2></div>
        <p style={{ margin: 0 }}>{placed.draft ? 'The draft is ready.' : 'Nothing changed on that server.'} It leaves out
          {' '}{placed.skipped.length === 1 ? 'one block state' : `${num(placed.skipped.length)} block states`} that server
          does not have; where they were, the world keeps what is there.</p>
        <ul className="mono" style={{ margin: 0, overflowWrap: 'anywhere' }}>
          {shown.map(s => <li key={s}>{s}</li>)}
          {more > 0 && <li className="muted">and {num(more)} more</li>}
        </ul>
        <div className="toolbar" style={{ margin: 0, justifyContent: 'flex-end' }}>
          {placed.draft ? <>
            <button type="button" className="ghost" onClick={onClose}>Close</button>
            <button type="button" onClick={() => { onClose(); go(draftHref(to, placed.draft!)); }}>Open draft</button>
          </> : <button type="button" onClick={onClose}>Close</button>}
        </div>
      </div></Modal>
    );
  }

  return (
    <Modal className="form-dialog" onClose={onClose}>
      <form onSubmit={submit}>
        <div className="dialog-head"><h2>Place {asset.name}</h2></div>
        <p className="muted" style={{ margin: 0 }}>Puts the asset ({asset.size.join(' × ')} blocks) into a draft on the server.
          Nothing is placed in the world until someone approves that draft.</p>
        {!targets.length ? <p className="error">You are not a builder on any server{asset.mine ? '' : ' it is shared with'}.</p> : <>
          <label>Server <select name="to" required value={to} onChange={e => setTo(e.target.value)}>
            {targets.map(s => <option key={s.id} value={s.id} disabled={!s.online}>{s.name}{s.online ? '' : ' (offline)'}</option>)}
          </select></label>
          {warning && <p className="warn" style={{ margin: 0 }}>{warning}</p>}
          <label>World {worlds.length
            ? <select key={to} name="world" required defaultValue={worlds[0]}>{worlds.map(w => <option key={w}>{w}</option>)}</select>
            : <input type="text" name="world" defaultValue="world" required />}</label>
          {asset.anchor && (
            <div className="pills" role="radiogroup" aria-label="Place by">
              <label className="check"><input type="radio" name="by" checked={by === 'anchor'} onChange={() => setBy('anchor')} />
                {' '}anchor position</label>
              <label className="check"><input type="radio" name="by" checked={by === 'corner'} onChange={() => setBy('corner')} />
                {' '}lowest corner</label>
            </div>
          )}
          <label>{by === 'anchor' ? 'Anchor block goes at (x y z)' : 'Lowest corner (x y z)'}
            <input type="text" name="origin" className="mono" placeholder="100 64 -20" required value={spot}
              onChange={e => setSpot(e.target.value)} /></label>
          {by === 'anchor' && asset.anchor && <p className="muted" style={{ margin: 0 }}>The anchor is block
            {' '}<span className="mono">{asset.anchor.join(' ')}</span> of the asset
            {corner && <>, so its lowest corner goes at <span className="mono">{corner.join(' ')}</span></>}.
            {asset.facing && <> It is placed as saved, facing {asset.facing}: the panel does not turn assets (ask Claude to
              paste it with a facing for that).</>}</p>}
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
