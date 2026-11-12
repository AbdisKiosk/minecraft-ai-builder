import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { api, message, serverApi } from '../lib/api';
import { ago, dateTime } from '../lib/format';
import { diffHref, draftHref } from '../lib/routes';
import { can, type Lesson, type Server } from '../lib/types';
import AddLesson, { MAX_LESSON as MAX_TEXT } from './AddLesson';
import { Badge } from './shared';
const ORIGIN: Record<Lesson['origin'], string> = { feedback: 'feedback', claude: 'Claude', manual: 'written here' };

/** Where a lesson came from: a history entry (h…) or a draft (d…). */
function EntryLink({ server, id }: { server: string; id: string }) {
  const href = id.startsWith('d') ? draftHref(server, id) : id.startsWith('h') ? diffHref(server, id) : null;
  return href ? <a className="mono" href={href}>{id}</a> : <span className="mono">{id}</span>;
}

/**
 * The lessons Claude reads at the start of every session on this server. They apply as soon as they are saved, so
 * this page is where a wrong one is caught: opening it marks the new ones seen.
 */
export default function LessonsPage({ server: s }: { server: Server }) {
  const server = s.id;
  const admin = can(s.role, 'admin');
  const base = `${serverApi(server)}/lessons`;
  const [lessons, setLessons] = useState<Lesson[] | null>(null);
  const [max, setMax] = useState(12);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState<{ id: string; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  // The "new" marks stay for this visit, though opening the page has marked them seen.
  const fresh = useRef<Set<string>>(new Set());
  const marked = useRef(false);

  const load = useCallback(async () => {
    try {
      const r = await api<{ lessons: Lesson[]; max: number }>(base);
      r.lessons.filter(l => !l.seen).forEach(l => fresh.current.add(l.id));
      setLessons(r.lessons);
      setMax(r.max);
      setError('');
      if (!marked.current && r.lessons.some(l => !l.seen)) {
        marked.current = true;
        api(`${base}/seen`, { method: 'POST' }).catch(() => { marked.current = false; });
      }
    } catch (ex) {
      setError(message(ex));
    }
  }, [base]);
  useEffect(() => { if (s.online) load(); }, [load, s.online]);

  const act = async (work: () => Promise<unknown>) => {
    setBusy(true);
    setError('');
    try {
      await work();
      await load();
      return true;
    } catch (ex) {
      setError(message(ex));
      return false;
    } finally {
      setBusy(false);
    }
  };

  const save = async (ev: FormEvent) => {
    ev.preventDefault();
    if (!editing || !editing.text.trim()) return;
    const e = editing;
    if (await act(() => api(`${base}/${encodeURIComponent(e.id)}`, { method: 'PATCH', body: JSON.stringify({ text: e.text }) }))) setEditing(null);
  };
  const remove = (l: Lesson) => {
    if (!confirm(`Delete this lesson? Claude stops reading it from its next session.\n\n${l.text}`)) return;
    act(() => api(`${base}/${encodeURIComponent(l.id)}`, { method: 'DELETE' }));
  };

  return (
    <>
      <div className="page-head"><div>
        <h1>Lessons</h1>
        <p className="muted" style={{ margin: '8px 0 0' }}>Claude reads these at the start of every session on {s.name}. They apply as soon
          as they are saved: edit or delete a wrong one here.</p>
      </div>{lessons && <span className="muted">{lessons.length} of {max}</span>}</div>
      <div className="stack">
          <section className="card">
            <p className="muted">Repeat warnings compare named block substitutions in retained, checked builds in the same world.
              They need two later builds; unchecked builds, missing source entries and rules without named substitutions cannot
              establish whether a lesson works. Editing a lesson starts a new observation period.</p>
          {!s.online && !lessons ? <p className="empty">The server is offline. Lessons are kept on it, so they show again when it reconnects.</p>
            : error && !lessons ? <p className="error">{error}</p>
            : !lessons ? <p className="muted">Loading lessons…</p> : (
              <div className="table-wrap"><table>
                <thead><tr><th>Lesson</th><th>From</th><th className="hide-sm">Who</th><th className="hide-sm">When</th>{admin && <th />}</tr></thead>
                <tbody>
                  {lessons.map(l => (
                    <tr key={l.id}>
                      <td style={{ width: '50%' }}>{editing?.id === l.id ? (
                        <form onSubmit={save} className="lesson-edit">
                          <textarea value={editing.text} maxLength={MAX_TEXT} rows={2} autoFocus
                            onChange={ev => setEditing({ id: l.id, text: ev.target.value })} />
                          <div className="toolbar" style={{ margin: 0 }}>
                            <button type="submit" className="small" disabled={busy}>Save</button>
                            <button type="button" className="ghost small" onClick={() => setEditing(null)}>Cancel</button>
                          </div>
                        </form>
                       ) : <>{l.text}{fresh.current.has(l.id) && <> <Badge kind="running">new</Badge></>}
                         {l.health?.status === 'recurring' && <p className="muted" style={{ marginBottom: 0 }}>
                           <b>May not be working:</b> the same substitution recurred in {l.health.count} later builds after this
                           lesson was saved or edited. Review the context before changing the rule.
                           {l.health.entries.map(e => <span key={e}> <EntryLink server={server} id={e} /></span>)}
                         </p>}
                       </>}</td>
                      <td><Badge>{ORIGIN[l.origin] ?? l.origin}</Badge>
                        {l.entries.map(e => <span key={e}> <EntryLink server={server} id={e} /></span>)}</td>
                      <td className="hide-sm">{l.by}</td>
                      <td className="hide-sm" title={dateTime(l.time)}>{ago(l.time)}</td>
                      {admin && <td className="right" style={{ whiteSpace: 'nowrap' }}>{editing?.id !== l.id && <>
                        <button className="ghost small" disabled={busy} onClick={() => setEditing({ id: l.id, text: l.text })}>Edit</button>{' '}
                        <button className="ghost small" disabled={busy} onClick={() => remove(l)}>Delete</button>
                      </>}</td>}
                    </tr>
                  ))}
                  {!lessons.length && <tr><td colSpan={admin ? 5 : 4} className="empty">
                    No lessons yet. They come from feedback after a rejected draft or a reverted edit{admin ? ', or add one below' : ''}.</td></tr>}
                </tbody>
              </table></div>
            )}
          {error && lessons && <p className="error">{error}</p>}
        </section>
        {admin && lessons && (
          <section className="card"><h2>Add a lesson</h2>
            <AddLesson server={server} max={max} full={lessons.length >= max} onSaved={load} />
            <p className="muted">One short rule per lesson. The list holds {max}: when it is full, a new lesson replaces the oldest one Claude
              wrote, or the oldest of all when people wrote every one.</p>
          </section>
        )}
      </div>
    </>
  );
}
