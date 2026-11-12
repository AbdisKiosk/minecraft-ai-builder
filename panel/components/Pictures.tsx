import { useEffect, useRef, useState } from 'react';
import { api, message } from '../lib/api';
import type { Cell } from '../lib/anchor';
import type { Facing, Renders } from '../lib/types';
import type { BlockData, Diff, Mode } from '../lib/schematic';
import type { Picked, Viewer } from '../lib/viewer3d';
import Modal from './Modal';

const SLOTS = ['before', 'after', 'changes'] as const;

/**
 * Before/after/changes pictures of a history entry or draft, or the interactive 3D view.
 * `base` is the entry's API path; `none` says why there is nothing to draw, if so.
 */
export default function Pictures({ base, whose, none, mc }: { base: string; whose: string; none?: string; mc?: string }) {
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
        : view === '3d' ? <div className="renders one">{mc
          ? <Viewer3d key={`${base}/${mc}`} url={`${base}/blocks`} mc={mc} />
          : <p className="muted">3D needs the server's Minecraft version.</p>}</div>
        : <Rendered key={`${view}/${corner}`} url={`${base}/render?view=${view}&corner=${corner}`} />}
      <p className="muted" style={{ margin: '10px 0 0' }}>The area around {whose} changes, with its old and new blocks put in place.
        Changes hidden inside solid blocks do not show; see the list below.</p>
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

/**
 * Loaded only when selected; the viewer owns the stage's canvas and releases it on unmount. With `asset`, the blocks
 * are one region without changes (an asset), shown as they are, with their model states filled in from the client jar;
 * its `anchor` cell is marked and an arrow points the way it `faces`. With `onPickCell`, a click on a block hands its
 * cell (from the box's lowest corner) to that, to pick an anchor.
 */
export function Viewer3d({ url, mc, asset, anchor, facing, onPickCell }: {
  url: string; mc: string; asset?: boolean; anchor?: Cell | null; facing?: Facing | null; onPickCell?: (cell: Cell) => void;
}) {
  const holder = useRef<HTMLDivElement>(null);
  const [viewer, setViewer] = useState<Viewer | null>(null);
  const [diff, setDiff] = useState<Diff | null>(null);
  const [note, setNote] = useState('Loading blocks…');
  const [mode, setMode] = useState<Mode>(asset ? 'after' : 'changes');
  const [onlyChanged, setOnlyChanged] = useState(false);
  const [cut, setCut] = useState(0);
  const [picked, setPicked] = useState<Picked | null>(null);
  const drawn = useRef(false);
  const pickCell = useRef(onPickCell);
  useEffect(() => { pickCell.current = onPickCell; });
  useEffect(() => {
    let current = true;
    let v: Viewer | null = null;
    let jarUrl = `/api/minecraft/${encodeURIComponent(mc)}/client.jar`;
    Promise.all([import('../lib/viewer3d'), import('../lib/schematic'), api<BlockData>(url)]).then(async ([module, schematic, data]) => {
      if (!current || !holder.current) return;
      if (!module.supported()) throw new Error('This browser has no WebGL 2, which the 3D view needs.');
      if (asset && !data.models) {
        // The jar is fetched once here and handed to the viewer, which would otherwise fetch it again.
        setNote('Loading Minecraft textures… (first use downloads the client jar)');
        const jar = await fetch(jarUrl);
        if (!jar.ok) throw new Error((await jar.json().catch(() => null))?.error || `Could not load Minecraft textures (${jar.status}).`);
        const blob = await jar.blob();
        const { modelStates } = await import('../lib/blockModels');
        data = { ...data, models: await modelStates(data.palette.map(p => typeof p === 'string' ? p : p.state), blob) };
        if (!current || !holder.current) return;
        jarUrl = URL.createObjectURL(blob);
      }
      if (!data.models) throw new Error('3D needs an updated AIBuilder plugin on this server (complete block model states).');
      const d = schematic.decode(data);
      setDiff(d);
      setCut(asset ? d.height - 1 : Math.min(d.height - 1, d.topChange + 1));
      v = module.createViewer(holder.current, d, jarUrl, p => {
        setPicked(p);
        if (p) pickCell.current?.(p.cell);
      });
      setViewer(v);
    }).catch(ex => { if (current) setNote(message(ex)); });
    return () => {
      current = false;
      v?.dispose();
      if (jarUrl.startsWith('blob:')) URL.revokeObjectURL(jarUrl);
    };
  }, [url, mc, asset]);
  useEffect(() => {
    if (!viewer) return;
    let current = true;
    setPicked(null);
    setNote(drawn.current ? 'Drawing…' : 'Loading Minecraft textures… (first use downloads the client jar)');
    const timer = setTimeout(() => {
      viewer.show({ mode, onlyChanged, cut }).then(() => {
        if (current) { drawn.current = true; setNote(''); }
      }, ex => { if (current) setNote(message(ex)); });
    }, 250);
    return () => { current = false; clearTimeout(timer); };
  }, [viewer, mode, onlyChanged, cut]);
  // A string key, so a new array with the same cell does not redraw the marker.
  const anchorKey = anchor?.join(' ') ?? '';
  useEffect(() => {
    viewer?.setAnchor(anchorKey ? anchorKey.split(' ').map(Number) as Cell : null, facing ?? null);
  }, [viewer, anchorKey, facing]);
  return (
    <div className="viewer3d">
      <div className="v3-bar">
        {!asset && <>
          <div className="seg" role="group" aria-label="3D mode">
            {SLOTS.map(slot => <button key={slot} className={mode === slot ? 'on' : ''} aria-pressed={mode === slot}
              onClick={() => setMode(slot)}>{slot[0].toUpperCase() + slot.slice(1)}</button>)}
          </div>
          <label><input type="checkbox" checked={onlyChanged} disabled={mode !== 'changes'}
            onChange={e => setOnlyChanged(e.target.checked)} /> only changed blocks</label>
        </>}
        <span className="spacer" />
        <label>Cut height <input type="range" min={0} max={diff ? diff.height - 1 : 0} value={cut} disabled={!diff}
          onChange={e => setCut(Number(e.target.value))} /><output>{diff ? diff.origin[1] + cut : '—'}</output></label>
      </div>
      <div className="v3-stage" ref={holder} />
      <div className="v3-info" role="status">{note || (picked
        ? asset ? <><b>{picked.pos.join(' ')}</b>  {picked.after}</>
          : <><b>{picked.pos.join(' ')}</b>  {picked.before} → {picked.after}  ({picked.kind?.toLowerCase() ?? 'unchanged'})</>
        : `Drag to orbit · scroll to zoom · click a block to ${onPickCell ? 'make it the anchor' : 'inspect'}`)}</div>
    </div>
  );
}
