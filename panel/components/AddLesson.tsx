import { useState, type FormEvent } from 'react';
import { api, message, serverApi } from '../lib/api';

export const MAX_LESSON = 160;

/**
 * Adds a lesson (origin "written here"), from the Lessons page or prefilled from a history entry's corrections, in
 * which case `entry` links the lesson to it. `full` asks first, since a new lesson then replaces an older one.
 */
export default function AddLesson({ server, max, initial = '', entry, full, onSaved, onCancel }: {
  server: string; max: number; initial?: string; entry?: string; full?: boolean; onSaved: () => void; onCancel?: () => void;
}) {
  const [text, setText] = useState(initial.slice(0, MAX_LESSON));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const submit = async (ev: FormEvent) => {
    ev.preventDefault();
    if (!text.trim()) return;
    if (full && !confirm(`There are already ${max} lessons. Saving this one replaces the AI's oldest, or the oldest of all when people wrote every one. Save it?`)) return;
    setBusy(true);
    setError('');
    try {
      await api(`${serverApi(server)}/lessons`, { method: 'POST', body: JSON.stringify(entry ? { text, entry } : { text }) });
      setText('');
      onSaved();
    } catch (ex) {
      setError(message(ex));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} className="lesson-edit">
      <textarea value={text} maxLength={MAX_LESSON} rows={2} onChange={ev => setText(ev.target.value)} data-autofocus
        placeholder="Use spruce_planks, not oak_planks, for floors." />
      <div className="toolbar" style={{ margin: 0 }}>
        <button type="submit" disabled={busy || !text.trim()}>Add lesson</button>
        {onCancel && <button type="button" className="ghost" onClick={onCancel}>Cancel</button>}
        <span className="muted">{text.length}/{MAX_LESSON}</span>
      </div>
      {error && <p className="error">{error}</p>}
    </form>
  );
}
