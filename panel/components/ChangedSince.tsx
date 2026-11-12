import { useState } from 'react';
import { lessonFrom } from '../lib/corrections';
import { num } from '../lib/format';
import { lessonsHref } from '../lib/routes';
import { can, type Corrections, type HistoryEntry, type Server } from '../lib/types';
import { useFetched } from '../lib/useFetched';
import AddLesson from './AddLesson';
import Modal from './Modal';
import Pictures from './Pictures';

/**
 * "Changed since": what people changed in the blocks this entry placed. The plugin compares the entry with the world
 * now on every visit (and stamps the entry, so Claude hears of it in its next session); an entry it no longer keeps,
 * or a server that is offline, cannot be compared.
 */
export default function ChangedSince({ server: s, entry: e, base }: { server: Server; entry: HistoryEntry; base: string }) {
  const placed = e.added + e.removed + e.changed;
  const checkable = e.tracked && placed > 0;
  const { data: c, error } = useFetched<Corrections>(`${base}/corrections`, checkable && s.online);
  const [pictures, setPictures] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  if (!checkable) return null;
  const quiet = (text: string) => <section className="card"><h2>Changed since</h2><p className="muted" style={{ margin: 0 }}>{text}</p></section>;
  if (!s.online) return quiet('The server is offline; what people changed since this edit is checked while it is online.');
  if (error.startsWith('no history entry')) return quiet('The server no longer keeps this entry, so what people changed since cannot be checked.');
  if (error) return quiet(error);
  if (!c) return quiet('Comparing with the world now…');
  if (c.cells === 0) return null;

  return (
    <>
      <section className="card"><h2>Changed since</h2>
        <p>People changed <b>{num(c.cells - (c.additions ?? 0))}</b> of the {num(c.placed)} blocks this edit placed ({Math.round(c.share * 100)}%).
          {!!c.additions && <> They also added <b>{num(c.additions)}</b> nearby blocks.</>}
          {' '}Changes the world makes by itself (leaves, grass, crops, water) are left out.</p>
        <p className="muted">{c.baseline
          ? 'Nearby additions are originally empty cells directly touching this build, within its saved snapshot. They may belong to another project; review before saving a lesson.'
          : 'No saved after-snapshot is available. Only the blocks this edit changed can be compared.'}</p>
        <div className="table-wrap"><table>
          <thead><tr><th>Change</th><th className="right">Blocks</th><th className="hide-sm">For example at</th></tr></thead>
          <tbody>
            {c.substitutions.map(r => (
              <tr key={`s ${r.from} ${r.to}`}><td><span className="mono">{r.from}</span> → <span className="mono">{r.to}</span></td>
                <td className="right">{num(r.count)}</td><td className="hide-sm mono">{r.at[0]?.join(' ')}</td></tr>
            ))}
            {c.removed.map(r => (
              <tr key={`r ${r.type}`}><td><span className="mono">{r.type}</span> removed</td>
                <td className="right">{num(r.count)}</td><td className="hide-sm" /></tr>
            ))}
            {c.states.map(r => (
              <tr key={`p ${r.type}`}><td><span className="mono">{r.type}</span> {r.props?.length ? r.props.join(', ') : 'state'} changed</td>
                <td className="right">{num(r.count)}</td><td className="hide-sm" /></tr>
            ))}
          </tbody>
        </table></div>
        <div className="toolbar">
          <button className="ghost" onClick={() => setPictures(p => !p)}>{pictures ? 'Hide pictures' : 'Show pictures'}</button>
          <span className="spacer" />
          {can(s.role, 'admin') && (saved
            ? <span className="muted">Saved; see <a href={lessonsHref(s.id)}>Lessons</a>.</span>
            : <button onClick={() => setSaving(true)}>Save as lesson</button>)}
        </div>
      </section>
      {pictures && <Pictures base={`${base}/corrections`} whose="people's" mc={s.info?.mc} />}
      {saving && (
        <Modal className="form-dialog" onClose={() => setSaving(false)}>
          <div style={{ display: 'grid', gap: 12 }}>
            <div className="dialog-head"><h2>Save as lesson</h2></div>
            <p className="muted" style={{ margin: 0 }}>Claude reads it at the start of every session on this server; it is
              linked to {e.id}. When the list is full, it replaces the oldest lesson Claude wrote.</p>
            <AddLesson server={s.id} max={12} initial={lessonFrom(c)} entry={e.id}
              onSaved={() => { setSaving(false); setSaved(true); }} onCancel={() => setSaving(false)} />
          </div>
        </Modal>
      )}
    </>
  );
}
