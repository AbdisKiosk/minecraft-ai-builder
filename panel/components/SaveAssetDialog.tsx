import { useState, type FormEvent } from 'react';
import { api, assetsApi, message } from '../lib/api';
import { num } from '../lib/format';
import { assetsHref } from '../lib/routes';
import { FACINGS, type Asset } from '../lib/types';
import Modal from './Modal';

const NAME = /^[a-z0-9_-]{1,40}$/;

/** The tags typed as "window, oak, small". */
export const parseTags = (text: string) => text.split(',').map(t => t.trim().toLowerCase()).filter(Boolean);

/** The anchor typed as "1 0 2" (null when empty), or undefined when it is not three whole numbers. */
export function parseAnchor(text: string): [number, number, number] | null | undefined {
  const t = text.trim();
  if (!t) return null;
  const v = t.split(/[\s,]+/).map(Number);
  return v.length === 3 && v.every(n => Number.isInteger(n) && n >= 0) ? v as [number, number, number] : undefined;
}

/** Anchor and facing inputs (named anchor and facing), for saving and editing an asset. */
export function AnchorFields({ anchor, facing }: { anchor: string; facing: string }) {
  return (
    <>
      <div className="row3">
        <label>Anchor (x y z) <input type="text" name="anchor" className="mono" defaultValue={anchor} placeholder="none" /></label>
        <label>Facing <select name="facing" defaultValue={facing}>
          <option value="">none</option>
          {FACINGS.map(f => <option key={f}>{f}</option>)}
        </select></label>
      </div>
      <p className="muted" style={{ margin: 0 }}>The anchor is the block Claude puts at a given spot (0 0 0 is the lowest corner;
        clicking a block in the 3D view shows its position). Facing is the way the piece faces, away from the wall or floor it
        sits on; Claude turns it to face north, south, east or west. Leave both empty to place by the lowest corner.</p>
    </>
  );
}

/**
 * Saves a box of a server's world to the user's asset library. The map gives x and z; the height is asked for here,
 * starting from `y` when the box lies in an area that has one.
 */
export default function SaveAssetDialog({ serverId, world, rect, y, onClose }:
    { serverId: string; world: string; rect: { x1: number; z1: number; x2: number; z2: number }; y: [number, number] | null; onClose: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState<Asset | null>(null);
  const width = rect.x2 - rect.x1 + 1, depth = rect.z2 - rect.z1 + 1;

  const submit = async (ev: FormEvent<HTMLFormElement>) => {
    ev.preventDefault();
    const f = new FormData(ev.currentTarget);
    const name = String(f.get('name') || '').trim().toLowerCase();
    if (!NAME.test(name)) return setError('Names are 1-40 of a-z, 0-9, _ and -.');
    const y1 = Number(f.get('y1')), y2 = Number(f.get('y2'));
    if (!Number.isInteger(y1) || !Number.isInteger(y2)) return setError('Heights are whole numbers.');
    const anchor = parseAnchor(String(f.get('anchor') || ''));
    if (anchor === undefined) return setError('The anchor needs 3 whole numbers of 0 or more, or nothing.');
    setBusy(true);
    setError('');
    try {
      setSaved(await api<Asset>(assetsApi, { method: 'POST', body: JSON.stringify({
        server: serverId, world, box: [rect.x1, Math.min(y1, y2), rect.z1, rect.x2, Math.max(y1, y2), rect.z2], name,
        description: String(f.get('description') || ''), tags: parseTags(String(f.get('tags') || '')),
        anchor, facing: String(f.get('facing') || '') || null,
      }) }));
    } catch (ex) {
      setError(message(ex));
    } finally {
      setBusy(false);
    }
  };

  if (saved) {
    return (
      <Modal className="form-dialog" onClose={onClose}>
        <form method="dialog">
          <div className="dialog-head"><h2>Saved</h2></div>
          <p style={{ margin: 0 }}><b className="mono">{saved.name}</b> is in your library: {saved.size.join(' × ')} blocks,
            {' '}{num(saved.blocks)} of them not air. You can place it on any server you build on.</p>
          <div className="toolbar" style={{ margin: 0, justifyContent: 'flex-end' }}>
            <button type="button" className="ghost" onClick={onClose}>Close</button>
            <a className="button" href={assetsHref}>Open the library</a>
          </div>
        </form>
      </Modal>
    );
  }

  return (
    <Modal className="form-dialog" onClose={onClose}>
      <form onSubmit={submit}>
        <div className="dialog-head"><h2>Save as asset</h2></div>
        <p className="muted" style={{ margin: 0 }}>
          <span className="mono">x {rect.x1}…{rect.x2}, z {rect.z1}…{rect.z2}</span> · {num(width)} × {num(depth)} blocks in {world}.
          The blocks are saved as they are in the world now (not an open draft), to your own library.</p>
        <label>Name <input type="text" name="name" maxLength={40} pattern="[a-zA-Z0-9_\-]{1,40}" required data-autofocus
          placeholder="oak-window" />
          <span className="muted" style={{ fontWeight: 400 }}>a-z, 0-9, _ and -</span></label>
        <label>Description <textarea name="description" maxLength={1000} rows={2} placeholder="Two-high oak window with shutters" /></label>
        <label>Tags <input type="text" name="tags" placeholder="window, oak" /></label>
        <div className="row3">
          <label>From y <input type="text" name="y1" inputMode="numeric" defaultValue={y?.[0]} required /></label>
          <label>To y <input type="text" name="y2" inputMode="numeric" defaultValue={y?.[1]} required /></label>
        </div>
        <AnchorFields anchor="0 0 0" facing="" />
        {error && <p className="error">{error}</p>}
        <div className="toolbar" style={{ margin: 0, justifyContent: 'flex-end' }}>
          <button type="button" className="ghost" onClick={onClose}>Cancel</button>
          <button type="submit" disabled={busy}>{busy ? 'Reading the region…' : 'Save'}</button>
        </div>
      </form>
    </Modal>
  );
}
