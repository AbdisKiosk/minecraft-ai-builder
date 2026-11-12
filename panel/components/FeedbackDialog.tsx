import { useState, type FormEvent } from 'react';
import { api, message, serverApi } from '../lib/api';
import { lessonsHref } from '../lib/routes';
import Modal from './Modal';

/**
 * Asked after a draft is rejected or an edit reverted, never before: Claude gets the text once, at the start of its
 * next session, and with "Remember" ticked it also becomes a lesson every later session reads.
 */
export default function FeedbackDialog({ server, id, onClose }: { server: string; id: string; onClose: () => void }) {
  const [text, setText] = useState('');
  const [remember, setRemember] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [sent, setSent] = useState(false);

  const submit = async (ev: FormEvent<HTMLFormElement>) => {
    ev.preventDefault();
    if (!text.trim()) return setError('Write something first, or skip.');
    setBusy(true);
    setError('');
    try {
      await api(`${serverApi(server)}/lessons/feedback`, { method: 'POST', body: JSON.stringify({ id, text, remember }) });
      setSent(true);
    } catch (ex) {
      setError(message(ex));
      setBusy(false);
    }
  };

  if (sent) {
    return (
      <Modal className="form-dialog" onClose={onClose}>
        <form method="dialog">
          <div className="dialog-head"><h2>Thanks</h2></div>
          <p style={{ margin: 0 }}>Claude reads it at the start of its next session on this server.
            {remember && <> It is also saved as a lesson; see <a href={lessonsHref(server)} onClick={onClose}>Lessons</a>.</>}</p>
          <div className="toolbar" style={{ margin: 0, justifyContent: 'flex-end' }}>
            <button type="submit" data-autofocus>Close</button>
          </div>
        </form>
      </Modal>
    );
  }

  return (
    <Modal className="form-dialog" onClose={onClose}>
      <form onSubmit={submit}>
        <div className="dialog-head"><h2>Do you want to give feedback on this build?</h2></div>
        <label>What should Claude do differently?
          <textarea value={text} onChange={ev => setText(ev.target.value)} maxLength={300} rows={4} data-autofocus
            placeholder="Floors should be spruce, not oak. Keep towers below 30 blocks." /></label>
        <label className="check"><input type="checkbox" checked={remember} onChange={ev => setRemember(ev.target.checked)} />
          Remember this for future builds</label>
        {remember && text.trim().length > 160 && <p className="muted" style={{ margin: 0 }}>The lesson keeps the first 160 characters.</p>}
        {error && <p className="error">{error}</p>}
        <div className="toolbar" style={{ margin: 0, justifyContent: 'flex-end' }}>
          <button type="button" className="ghost" onClick={onClose}>Skip</button>
          <button type="submit" disabled={busy}>{busy ? 'Sending…' : 'Send'}</button>
        </div>
      </form>
    </Modal>
  );
}
