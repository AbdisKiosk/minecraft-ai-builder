import { useState, type FormEvent } from 'react';
import { api, message } from '../lib/api';
import { draftHref, go } from '../lib/routes';
import type { Servers } from '../lib/usePanel';
import Modal from './Modal';

export interface PromoteSource { world: string; bounds?: number[] | null; fromDraft?: boolean }

/** Copies a region into a draft on another server (or elsewhere on this one). */
export default function PromoteDialog({ from, source, servers, onClose }:
    { from: string; source: PromoteSource; servers: Servers; onClose: () => void }) {
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  // The source itself is listed last: copying a region elsewhere on the same server is a promote too.
  const targets = [...servers.values()].sort((a, b) => Number(a.id === from) - Number(b.id === from) || a.id.localeCompare(b.id));
  const b = source.bounds || [0, 0, 0, 0, 0, 0];

  const submit = async (ev: FormEvent<HTMLFormElement>) => {
    ev.preventDefault();
    const f = new FormData(ev.currentTarget);
    const text = (name: string) => String(f.get(name) ?? '');
    const nums = (name: string) => text(name).trim().split(/[\s,]+/).map(Number);
    const box = nums('box'), origin = nums('origin');
    if (box.length !== 6 || origin.length !== 3 || [...box, ...origin].some(Number.isNaN)) {
      setError('Region needs 6 numbers and the corner 3.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const to = text('to');
      const r = await api('/api/promote', { method: 'POST', body: JSON.stringify({
        from, to, world: text('world'), toWorld: text('toWorld'), box, origin,
        air: f.has('air'), skipUnknown: f.has('skipUnknown') }) });
      if (r.error) throw new Error(r.error);
      onClose();
      if (r.draft) go(draftHref(to, r.draft));
      else alert('Nothing changed on the target: it already has these blocks.');
    } catch (ex) {
      setError(message(ex));
      setBusy(false);
    }
  };

  return (
    <Modal className="card form-dialog" onClose={onClose}>
      <form onSubmit={submit}>
        <h2>Promote region</h2>
        <p className="muted">Copies the blocks in this region
          {source.fromDraft ? ' as they are in the world now (approve this draft first if it should be included)' : ' as they are now'}
          {' '}into a draft on the target server. Nothing is placed there until someone approves that draft.</p>
        <label>Target server <select name="to" required defaultValue={targets.find(s => s.online)?.id}>
          {targets.map(s => (
            <option key={s.id} value={s.id} disabled={!s.online}>
              {s.name || s.id}{s.id === from ? ' (same server)' : ''}{s.online ? '' : ' (offline)'}
            </option>
          ))}
        </select></label>
        <div className="row3">
          <label>From world <input type="text" name="world" defaultValue={source.world} required /></label>
          <label>Target world <input type="text" name="toWorld" defaultValue={source.world} required /></label>
        </div>
        <label>Region <input type="text" name="box" defaultValue={b.join(' ')} className="mono" required /></label>
        <label>Target corner (min x y z) <input type="text" name="origin" className="mono" required
          defaultValue={`${Math.min(b[0], b[3])} ${Math.min(b[1], b[4])} ${Math.min(b[2], b[5])}`} /></label>
        <label className="check"><input type="checkbox" name="air" defaultChecked /> copy air too (clears what is in the way)</label>
        <label className="check"><input type="checkbox" name="skipUnknown" /> leave out blocks the target server does not have</label>
        <p className="error">{error}</p>
        <div className="toolbar">
          <button type="submit" disabled={busy}>{busy ? 'Copying…' : 'Create draft'}</button>
          <button type="button" className="ghost" onClick={onClose}>Cancel</button>
        </div>
      </form>
    </Modal>
  );
}
