import { useRef, useState, type FormEvent } from 'react';
import { assetsApi, message, signIn } from '../lib/api';
import { num } from '../lib/format';
import { MAX_UPLOAD_BYTES, nameFromFile } from '../lib/schemUpload';
import type { Asset } from '../lib/types';
import Modal from './Modal';

const NAME = /^[a-z0-9_-]{1,40}$/;

/** Uploads a WorldEdit .schem file into the user's asset library (POST /api/assets/upload). */
export default function UploadAssetDialog({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState<Asset | null>(null);
  const nameRef = useRef<HTMLInputElement>(null);
  // The name last filled in from a file, so choosing another file replaces it but never a name the user typed.
  const prefilled = useRef('');

  const chosen = (file: File | undefined) => {
    setError(file && file.size > MAX_UPLOAD_BYTES ? `That file is ${(file.size / 1024 ** 2).toFixed(1)} MB; the most is ${MAX_UPLOAD_BYTES / 1024 ** 2} MB.` : '');
    const input = nameRef.current;
    if (!file || !input || (input.value && input.value !== prefilled.current)) return;
    input.value = prefilled.current = nameFromFile(file.name);
  };

  const submit = async (ev: FormEvent<HTMLFormElement>) => {
    ev.preventDefault();
    const f = new FormData(ev.currentTarget);
    const file = f.get('file');
    if (!(file instanceof File) || !file.size) return setError('Choose a .schem file.');
    if (file.size > MAX_UPLOAD_BYTES) return setError(`The file is larger than ${MAX_UPLOAD_BYTES / 1024 ** 2} MB.`);
    const name = String(f.get('name') || '').trim().toLowerCase();
    if (!NAME.test(name)) return setError('Names are 1-40 of a-z, 0-9, _ and -.');
    f.set('name', name);
    setBusy(true);
    setError('');
    try {
      // Not api(): the browser sets the multipart Content-Type with its boundary.
      const res = await fetch(`${assetsApi}/upload`, { method: 'POST', body: f, credentials: 'same-origin' });
      if (res.status === 401) {
        signIn();
        throw new Error('sign in first');
      }
      const body = await res.json().catch(() => null);
      if (!res.ok) throw new Error(body?.error || `${res.status} ${res.statusText}`);
      setSaved(body as Asset);
      onSaved();
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
          <div className="dialog-head"><h2>Uploaded</h2></div>
          <p style={{ margin: 0 }}><b className="mono">{saved.name}</b> is in your library: {saved.size.join(' × ')} blocks,
            {' '}{num(saved.blocks)} of them not air{saved.mc ? `, from Minecraft ${saved.mc}` : ''}.</p>
          <div className="toolbar" style={{ margin: 0, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose}>Close</button>
          </div>
        </form>
      </Modal>
    );
  }

  return (
    <Modal className="form-dialog" onClose={onClose}>
      <form onSubmit={submit}>
        <div className="dialog-head"><h2>Upload a schematic</h2></div>
        <p className="muted" style={{ margin: 0 }}>A <span className="mono">.schem</span> file as WorldEdit 7 saves it, at
          most {MAX_UPLOAD_BYTES / 1024 ** 2} MB. Only the blocks are kept: sign text, chest contents, entities and biomes are left out.</p>
        <label>File <input type="file" name="file" accept=".schem" required data-autofocus onChange={e => chosen(e.target.files?.[0])} /></label>
        <label>Name <input ref={nameRef} type="text" name="name" maxLength={40} pattern="[a-zA-Z0-9_\-]{1,40}" required placeholder="oak-window" />
          <span className="muted" style={{ fontWeight: 400 }}>a-z, 0-9, _ and -</span></label>
        <label>Description <textarea name="description" maxLength={1000} rows={2} placeholder="Two-high oak window with shutters" /></label>
        <label>Tags <input type="text" name="tags" placeholder="window, oak" /></label>
        {error && <p className="error">{error}</p>}
        <div className="toolbar" style={{ margin: 0, justifyContent: 'flex-end' }}>
          <button type="button" className="ghost" onClick={onClose}>Cancel</button>
          <button type="submit" disabled={busy}>{busy ? 'Uploading…' : 'Upload'}</button>
        </div>
      </form>
    </Modal>
  );
}
