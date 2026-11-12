import { useEffect, useRef, useState } from 'react';
import { api, message } from '../lib/api';
import type { Renders } from '../lib/types';
import type { BlockData, Viewer } from '../lib/viewer3d';
import Modal from './Modal';

const SLOTS = ['before', 'after', 'changes'] as const;

/**
 * Before/after/changes pictures of a history entry or draft, or the interactive 3D view.
 * `base` is the entry's API path; `none` says why there is nothing to draw, if so.
 */
export default function Pictures({ base, whose, none }: { base: string; whose: string; none?: string }) {
  const [view, setView] = useState('iso');
  const [corner, setCorner] = useState('se');
  // The 3D view needs room to drag in: on a phone the pictures are the better view.
  const [small, setSmall] = useState(false);
  useEffect(() => setSmall(window.matchMedia('(max-width: 700px)').matches), []);

  return (
    <section className="card">
      <div className="toolbar"><h2 style={{ margin: 0 }}>Pictures</h2>
        <select aria-label="View" value={view} onChange={e => setView(e.target.value)}>
          <option value="iso">Isometric</option><option value="top">Top-down</option>
          <option value="north">North face</option><option value="south">South face</option>
          <option value="east">East face</option><option value="west">West face</option>
          <option value="3d" disabled={small}>{small ? '3D (needs a wider screen)' : '3D (interactive)'}</option>
        </select>
        <select aria-label="Camera corner" value={corner} onChange={e => setCorner(e.target.value)} disabled={view !== 'iso'}>
          <option>se</option><option>sw</option><option>ne</option><option>nw</option>
        </select>
        <span className="spacer" />
        <span className="legend">
          <span><i style={{ background: 'var(--added)' }} />added</span>
          <span><i style={{ background: 'var(--removed)' }} />removed</span>
          <span><i style={{ background: 'var(--changed)' }} />changed</span>
        </span>
      </div>
      {none ? <div className="renders"><p className="muted">{none}</p></div>
        : view === '3d' ? <div className="renders one"><Viewer3d url={`${base}/blocks`} /></div>
        : <Rendered key={`${view}/${corner}`} url={`${base}/render?view=${view}&corner=${corner}`} />}
      <p className="muted" style={{ margin: '10px 0 0' }}>Drawn from the world as it is now, with {whose} old and new blocks put in
        place. Changes hidden inside solid blocks do not show; see the list below.</p>
    </section>
  );
}

function Rendered({ url }: { url: string }) {
  const [renders, setRenders] = useState<Renders | null>(null);
  const [error, setError] = useState('');
  const [zoomed, setZoomed] = useState<string | null>(null);
  useEffect(() => {
    let current = true;
    api<Renders>(url).then(r => { if (current) setRenders(r); }, ex => { if (current) setError(message(ex)); });
    return () => { current = false; };
  }, [url]);
  return (
    <div className="renders">
      {SLOTS.map(slot => {
        const src = renders && 'data:image/png;base64,' + renders[slot];
        return (
          <figure className="render" key={slot}><figcaption>{slot}</figcaption>
            <div className="img">
              {src ? <img alt={slot} src={src} onClick={() => setZoomed(src)} />
                : <span className="note">{error || 'Rendering…'}</span>}
            </div>
          </figure>
        );
      })}
      {zoomed && (
        <Modal className="zoom" onClose={() => setZoomed(null)} onClick={() => setZoomed(null)}>
          <img alt="" src={zoomed} />
        </Modal>
      )}
    </div>
  );
}

/** The 3D view. Its code (with three.js) is only loaded here, and its WebGL context is given back on unmount. */
function Viewer3d({ url }: { url: string }) {
  const holder = useRef<HTMLDivElement>(null);
  const [note, setNote] = useState('Loading blocks…');
  useEffect(() => {
    let current = true;
    let viewer: Viewer | null = null;
    Promise.all([import('../lib/viewer3d'), api<BlockData>(url)]).then(([module, data]) => {
      if (!current || !holder.current) return;
      if (!module.supported()) throw new Error('This browser has no WebGL 2, which the 3D view needs.');
      setNote('');
      viewer = module.mount(holder.current, data);
    }).catch(ex => { if (current) setNote(message(ex)); });
    return () => {
      current = false;
      if (viewer) viewer.dispose();
    };
  }, [url]);
  return (
    <div className="viewer3d">
      {note && <span className="note">{note}</span>}
      {/* Filled by viewer3d.mount, not by React. */}
      <div ref={holder} style={{ display: 'contents' }} />
    </div>
  );
}
