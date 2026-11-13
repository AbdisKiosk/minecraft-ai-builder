import { memo, useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { api, message, serverApi } from '../lib/api';
import { num } from '../lib/format';
import { MAX_SCALE, minScale, TileLayer, type MapIndex, type View } from '../lib/mapLayer';
import { go, mapHref } from '../lib/routes';
import { can, type Server } from '../lib/types';
import Modal from './Modal';
import SaveAssetDialog from './SaveAssetDialog';

/** An inclusive block rectangle on the map. */
interface Rect { x1: number; z1: number; x2: number; z2: number }
type Area = MapIndex['areas'][string];

const REFRESH_MS = 20_000;
const NAME = /^[a-z0-9_-]{1,24}$/;
// Where each map was left, so coming back to it does not start over at spawn.
const lastViews = new Map<string, View>();

const enc = encodeURIComponent;
const norm = (a: { x: number; z: number }, b: { x: number; z: number }): Rect =>
  ({ x1: Math.min(a.x, b.x), z1: Math.min(a.z, b.z), x2: Math.max(a.x, b.x), z2: Math.max(a.z, b.z) });
const areaRect = (a: Area): Rect => ({ x1: a.box[0], z1: a.box[2], x2: a.box[3], z2: a.box[5] });

/** The height of the smallest area the box lies in, if any: the map itself has no heights to offer. */
function heightOf(index: MapIndex, r: Rect): [number, number] | null {
  const inside = Object.values(index.areas)
    .map(a => ({ a, o: areaRect(a) }))
    .filter(({ o }) => o.x1 <= r.x1 && o.z1 <= r.z1 && o.x2 >= r.x2 && o.z2 >= r.z2)
    .sort((p, q) => (p.o.x2 - p.o.x1) * (p.o.z2 - p.o.z1) - (q.o.x2 - q.o.x1) * (q.o.z2 - q.o.z1));
  return inside.length ? [inside[0].a.box[1], inside[0].a.box[4]] : null;
}

function areaColor(name: string): string {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
  return `hsl(${h % 360} 85% 60%)`;
}

export default function MapPage({ server, world: routeWorld }: { server: Server; world: string | null }) {
  const id = server.id;
  const builder = can(server.role, 'builder');
  const [index, setIndex] = useState<MapIndex | null>(null);
  const [error, setError] = useState('');
  const [selected, setSelected] = useState<string | null>(null);
  const [drawMode, setDrawMode] = useState(false);
  // What the box being marked is for: a new area, or an asset saved from it.
  const [drawFor, setDrawFor] = useState<'area' | 'asset'>('area');
  const [newArea, setNewArea] = useState<Rect | null>(null);
  const [newAsset, setNewAsset] = useState<Rect | null>(null);
  const [fly, setFly] = useState<{ x: number; z: number; scale?: number; n: number } | null>(null);
  const world = routeWorld || index?.world || '';
  const request = useRef(0);

  const load = useCallback(async () => {
    const mine = ++request.current;
    try {
      const q = routeWorld ? `?world=${enc(routeWorld)}` : '';
      const ix = await api<MapIndex>(`${serverApi(id)}/map/index${q}`);
      if (mine !== request.current) return;
      setIndex(ix);
      setError('');
    } catch (ex) {
      if (mine === request.current) setError(message(ex));
    }
  }, [id, routeWorld]);

  // A finished job may have changed the world; so may time (players build too, and the server saves).
  const newest = server.recent[0] ? server.recent[0].id + server.recent[0].status : '';
  useEffect(() => { load(); }, [load, newest, server.online]);
  useEffect(() => {
    const timer = setInterval(() => { if (document.visibilityState === 'visible') load(); }, REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);
  useEffect(() => { setSelected(null); setDrawMode(false); }, [routeWorld]);
  useEffect(() => {
    if (!drawMode) return;
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setDrawMode(false); };
    window.addEventListener('keydown', esc);
    return () => window.removeEventListener('keydown', esc);
  }, [drawMode]);

  const flyTo = useCallback((x: number, z: number, scale?: number) => setFly(f => ({ x, z, scale, n: (f?.n || 0) + 1 })), []);
  const onDrawn = useCallback((r: Rect) => {
    setDrawMode(false);
    if (drawFor === 'asset') setNewAsset(r);
    else setNewArea(r);
  }, [drawFor]);
  const startDrawing = (what: 'area' | 'asset') => {
    setDrawFor(what);
    setDrawMode(!(drawMode && drawFor === what));
  };

  const removeArea = async (name: string) => {
    if (!confirm(`Remove the area "${name}"? The AI can no longer use @${name}.`)) return;
    try {
      await api(`${serverApi(id)}/map/areas/${enc(world)}/${enc(name)}`, { method: 'DELETE' });
      if (selected === name) setSelected(null);
      load();
    } catch (ex) {
      alert(message(ex));
    }
  };

  const ready = index && index.world === (routeWorld || index.world);
  return (
    <>
      <div className="page-head">
        <div><h1>Map</h1>
          <div className="muted">{server.name} · drawn from the world&apos;s saved region files, without loading chunks</div></div>
        <div className="toolbar" style={{ margin: 0 }}>
          {index && index.worlds.length > 1 && (
            <select aria-label="World" value={world} onChange={e => go(mapHref(id, e.target.value))}>
              {index.worlds.map(w => <option key={w} value={w}>{w}</option>)}
            </select>
          )}
          {builder && ready && (
            <button className={drawMode && drawFor === 'area' ? '' : 'ghost'} onClick={() => startDrawing('area')}
              aria-pressed={drawMode && drawFor === 'area'}>
              {drawMode && drawFor === 'area' ? 'Cancel marking (Esc)' : 'Mark area'}</button>
          )}
          {builder && ready && (
            <button className={drawMode && drawFor === 'asset' ? '' : 'ghost'} onClick={() => startDrawing('asset')}
              aria-pressed={drawMode && drawFor === 'asset'}>
              {drawMode && drawFor === 'asset' ? 'Cancel saving (Esc)' : 'Save as asset'}</button>
          )}
        </div>
      </div>
      {index?.offline && <p className="warn">The server is offline: this is the map as it was when the panel last saw it.</p>}
      {error && !index && <div className="card"><p className="error">{error}</p></div>}
      {!index && !error && <p className="muted">Loading the map…</p>}
      {ready && (
        <div className="map-page">
          <MapStage key={`${id}|${index.world}`} serverId={id} index={index} drawMode={drawMode} selected={selected}
            onSelect={setSelected} onDrawn={onDrawn} fly={fly} />
          <MapSide index={index} selected={selected} builder={builder} onSelect={name => {
            setSelected(name);
            const r = areaRect(index.areas[name]);
            flyTo((r.x1 + r.x2 + 1) / 2, (r.z1 + r.z2 + 1) / 2);
          }} onFly={flyTo} onRemove={removeArea} />
        </div>
      )}
      {error && index && <p className="error">{error}</p>}
      {newArea && index && (
        <NewAreaDialog serverId={id} world={index.world} rect={newArea} taken={[...Object.keys(index.areas), ...Object.keys(index.markers)]}
          onClose={() => setNewArea(null)} onSaved={name => { setNewArea(null); setSelected(name); load(); }} />
      )}
      {newAsset && index && (
        <SaveAssetDialog serverId={id} world={index.world} rect={newAsset} y={heightOf(index, newAsset)} onClose={() => setNewAsset(null)} />
      )}
    </>
  );
}

// ---- the map itself ----

interface StageProps {
  serverId: string;
  index: MapIndex;
  drawMode: boolean;
  selected: string | null;
  onSelect: (name: string | null) => void;
  onDrawn: (r: Rect) => void;
  fly: { x: number; z: number; scale?: number; n: number } | null;
}

function MapStage({ serverId, index, drawMode, selected, onSelect, onDrawn, fly }: StageProps) {
  const stage = useRef<HTMLDivElement>(null);
  const tilesRoot = useRef<HTMLDivElement>(null);
  const layer = useRef<TileLayer | null>(null);
  const viewKey = `${serverId}|${index.world}`;
  const view = useRef<View>(lastViews.get(viewKey)
    ?? { x: index.spawn?.[0] ?? 0, z: index.spawn?.[1] ?? 0, scale: 1 });
  const size = useRef({ w: 0, h: 0 });
  const [, setFrame] = useState(0);
  const [hover, setHover] = useState<{ x: number; z: number } | null>(null);
  const [drawing, setDrawing] = useState<Rect | null>(null);
  // Marking by clicks: the first corner, waiting for the second.
  const [corner, setCorner] = useState<{ x: number; z: number } | null>(null);
  const cornerRef = useRef(corner);
  cornerRef.current = corner;
  const [tileError, setTileError] = useState('');
  const raf = useRef(0);
  const anim = useRef(0);
  const props = useRef({ drawMode, onSelect, onDrawn, index });
  props.current = { drawMode, onSelect, onDrawn, index };

  const redraw = useCallback(() => {
    if (raf.current) return;
    raf.current = requestAnimationFrame(() => {
      raf.current = 0;
      const { w, h } = size.current;
      layer.current?.draw(view.current, w, h);
      lastViews.set(viewKey, view.current);
      setFrame(f => f + 1);
    });
  }, [viewKey]);

  const setView = useCallback((v: View) => {
    const s = Math.max(minScale(props.current.index.maxZoom), Math.min(MAX_SCALE, v.scale));
    view.current = { x: v.x, z: v.z, scale: s };
    redraw();
  }, [redraw]);

  // The tile layer lives for as long as this map of this world.
  useEffect(() => {
    const url = (l: number, x: number, z: number, v: string) =>
      `${serverApi(serverId)}/map/tile/${enc(index.world)}/${l}/${x}/${z}?v=${enc(v)}`;
    const tl = new TileLayer(tilesRoot.current!, url, setTileError);
    layer.current = tl;
    return () => { tl.clear(); layer.current = null; cancelAnimationFrame(raf.current); cancelAnimationFrame(anim.current); };
  }, [serverId, index.world]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    layer.current?.setIndex(index);
    setTileError('');
    redraw();
  }, [index, redraw]);

  useEffect(() => {
    if (!drawMode) {
      setCorner(null);
      setDrawing(null);
    }
  }, [drawMode]);

  useEffect(() => {
    const el = stage.current!;
    const ro = new ResizeObserver(() => {
      size.current = { w: el.clientWidth, h: el.clientHeight };
      redraw();
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, [redraw]);

  // Glides to a place picked in the side list.
  useEffect(() => {
    if (!fly) return;
    const from = view.current;
    const to = { x: fly.x, z: fly.z, scale: fly.scale ?? Math.max(from.scale, 1) };
    const start = performance.now();
    const step = (now: number) => {
      const t = Math.min(1, (now - start) / 450);
      const e = 1 - (1 - t) ** 3;
      // Zoom in log space, so the speed looks even.
      const scale = Math.exp(Math.log(from.scale) + (Math.log(to.scale) - Math.log(from.scale)) * e);
      setView({ x: from.x + (to.x - from.x) * e, z: from.z + (to.z - from.z) * e, scale });
      if (t < 1) anim.current = requestAnimationFrame(step);
    };
    cancelAnimationFrame(anim.current);
    anim.current = requestAnimationFrame(step);
  }, [fly, setView]);

  const toBlock = useCallback((clientX: number, clientY: number) => {
    const r = stage.current!.getBoundingClientRect();
    const v = view.current;
    return {
      x: Math.floor(v.x + (clientX - r.left - r.width / 2) / v.scale),
      z: Math.floor(v.z + (clientY - r.top - r.height / 2) / v.scale),
    };
  }, []);

  const zoomAt = useCallback((clientX: number, clientY: number, factor: number) => {
    const r = stage.current!.getBoundingClientRect();
    const v = view.current;
    const s = Math.max(minScale(props.current.index.maxZoom), Math.min(MAX_SCALE, v.scale * factor));
    const dx = clientX - r.left - r.width / 2, dy = clientY - r.top - r.height / 2;
    // The block under the pointer stays under it.
    setView({ x: v.x + dx / v.scale - dx / s, z: v.z + dy / v.scale - dy / s, scale: s });
  }, [setView]);

  // Wheel and trackpad pinch; non-passive so the page does not scroll.
  useEffect(() => {
    const el = stage.current!;
    const wheel = (e: WheelEvent) => {
      e.preventDefault();
      cancelAnimationFrame(anim.current);
      const dy = e.deltaMode === 1 ? e.deltaY * 16 : e.deltaY;
      zoomAt(e.clientX, e.clientY, Math.exp(-dy * (e.ctrlKey ? 0.01 : 0.0022)));
    };
    el.addEventListener('wheel', wheel, { passive: false });
    return () => el.removeEventListener('wheel', wheel);
  }, [zoomAt]);

  // Drag to pan (or to draw an area), two fingers to pinch.
  const pointers = useRef(new Map<number, { x: number; y: number }>());
  const gesture = useRef<{ start: { x: number; y: number }; moved: boolean; pinch?: { d: number }; drawFrom?: { x: number; z: number } } | null>(null);

  const onPointerDown = (e: React.PointerEvent) => {
    if (e.button !== 0) return;
    cancelAnimationFrame(anim.current);
    stage.current!.setPointerCapture(e.pointerId);
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.current.size === 2) {
      const [a, b] = [...pointers.current.values()];
      gesture.current = { start: { x: e.clientX, y: e.clientY }, moved: true, pinch: { d: Math.hypot(a.x - b.x, a.y - b.y) } };
      setDrawing(null);
      return;
    }
    // Marking: press where no corner is set yet, then either drag to the other corner or let go and click it.
    // Once a corner is set, dragging moves the map, so the other corner can be anywhere.
    const drawFrom = props.current.drawMode && !cornerRef.current ? toBlock(e.clientX, e.clientY) : undefined;
    gesture.current = { start: { x: e.clientX, y: e.clientY }, moved: false, drawFrom };
    if (drawFrom) setDrawing(norm(drawFrom, drawFrom));
  };

  const onPointerMove = (e: React.PointerEvent) => {
    const b = toBlock(e.clientX, e.clientY);
    setHover(h => (h && h.x === b.x && h.z === b.z ? h : b));
    const c = cornerRef.current;
    if (c) setDrawing(norm(c, b));
    const prev = pointers.current.get(e.pointerId);
    const g = gesture.current;
    if (!prev || !g) return;
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (Math.hypot(e.clientX - g.start.x, e.clientY - g.start.y) > 4) g.moved = true;
    if (g.pinch && pointers.current.size === 2) {
      const [p1, p2] = [...pointers.current.values()];
      const d = Math.hypot(p1.x - p2.x, p1.y - p2.y);
      const v = view.current;
      // Pan by the midpoint's move, zoom by the change in distance.
      const mid = { x: (p1.x + p2.x) / 2, y: (p1.y + p2.y) / 2 };
      const before = { x: mid.x - (e.clientX - prev.x) / 2, y: mid.y - (e.clientY - prev.y) / 2 };
      setView({ ...v, x: v.x - (mid.x - before.x) / v.scale, z: v.z - (mid.y - before.y) / v.scale });
      zoomAt(mid.x, mid.y, d / g.pinch.d);
      g.pinch.d = d;
      return;
    }
    if (g.drawFrom) {
      setDrawing(norm(g.drawFrom, b));
      return;
    }
    const v = view.current;
    setView({ ...v, x: v.x - (e.clientX - prev.x) / v.scale, z: v.z - (e.clientY - prev.y) / v.scale });
  };

  const onPointerUp = (e: React.PointerEvent) => {
    const g = gesture.current;
    pointers.current.delete(e.pointerId);
    if (pointers.current.size > 0) return;
    gesture.current = null;
    if (!g) return;
    const end = toBlock(e.clientX, e.clientY);
    if (g.drawFrom && !g.pinch) {
      if (g.moved) {
        setDrawing(null);
        props.current.onDrawn(norm(g.drawFrom, end));
      } else {
        setCorner(g.drawFrom); // the first of two clicks
      }
      return;
    }
    const c = cornerRef.current;
    if (c && props.current.drawMode && !g.moved && !g.pinch) {
      setCorner(null);
      setDrawing(null);
      props.current.onDrawn(norm(c, end));
      return;
    }
    if (!g.moved && !props.current.drawMode) {
      // A click: select the smallest area under it, or nothing.
      const b = toBlock(e.clientX, e.clientY);
      const hits = Object.entries(props.current.index.areas)
        .map(([name, a]) => ({ name, r: areaRect(a) }))
        .filter(({ r }) => b.x >= r.x1 && b.x <= r.x2 && b.z >= r.z1 && b.z <= r.z2)
        .sort((p, q) => (p.r.x2 - p.r.x1) * (p.r.z2 - p.r.z1) - (q.r.x2 - q.r.x1) * (q.r.z2 - q.r.z1));
      props.current.onSelect(hits[0]?.name ?? null);
    }
  };

  const v = view.current;
  const { w, h } = size.current;
  const sx = (x: number) => (x - v.x) * v.scale + w / 2;
  const sz = (z: number) => (z - v.z) * v.scale + h / 2;
  const rectProps = (r: Rect) => ({ x: sx(r.x1), y: sz(r.z1), width: (r.x2 - r.x1 + 1) * v.scale, height: (r.z2 - r.z1 + 1) * v.scale });
  const showLabels = v.scale >= 1 / 8;

  return (
    <div className={`map-stage ${drawMode ? 'drawing' : ''}`} ref={stage} onPointerDown={onPointerDown} onPointerMove={onPointerMove}
      onPointerUp={onPointerUp} onPointerCancel={onPointerUp} onPointerLeave={() => setHover(null)}
      onDoubleClick={e => zoomAt(e.clientX, e.clientY, 2)} role="application" aria-label={`Map of ${index.world}`}>
      <div ref={tilesRoot} className="map-tiles" />
      {!index.regions.length && <p className="map-empty">No saved chunks in this world yet.</p>}
      <svg className="map-overlay" width={w} height={h}>
        {Object.entries(index.areas).map(([name, a]) => {
          const r = areaRect(a);
          const p = rectProps(r);
          const c = areaColor(name);
          const on = name === selected;
          return (
            <g key={name}>
              <rect {...p} fill={c} fillOpacity={on ? 0.28 : 0.14} stroke={c} strokeWidth={on ? 3 : 1.5} />
              {p.width > 36 && p.height > 16 && (
                <text x={p.x + 6} y={p.y + 15} className="map-label">@{name}</text>
              )}
            </g>
          );
        })}
        {drawing && <rect {...rectProps(drawing)} className="map-draft" />}
        {hover && v.scale >= 4 && !drawing && (
          <rect x={sx(hover.x)} y={sz(hover.z)} width={v.scale} height={v.scale} className="map-hover" />
        )}
        {Object.entries(index.markers).map(([name, [x, , z]]) => (
          <g key={name} transform={`translate(${sx(x + 0.5)} ${sz(z + 0.5)})`}>
            <circle r={4.5} className="map-marker" />
            {showLabels && <text x={8} y={4} className="map-label small">@{name}</text>}
          </g>
        ))}
        {index.spawn && (
          <g transform={`translate(${sx(index.spawn[0] + 0.5)} ${sz(index.spawn[1] + 0.5)})`}>
            <path d="M0 -7 L7 0 L0 7 L-7 0 Z" className="map-spawn" />
            {showLabels && <text x={10} y={4} className="map-label small">spawn</text>}
          </g>
        )}
        {(index.players || []).map(p => (
          <g key={p.name} transform={`translate(${sx(p.x + 0.5)} ${sz(p.z + 0.5)})`}>
            <circle r={6} className="map-player" />
            <text x={10} y={4} className="map-label">{p.name}</text>
          </g>
        ))}
      </svg>
      <div className="map-hud">
        <span className="mono">{hover ? `x ${hover.x}  z ${hover.z}` : ' '}</span>
        <span className="mono muted">{v.scale >= 1 ? `${+v.scale.toFixed(1)} px/block` : `1 px = ${+(1 / v.scale).toFixed(1 / v.scale < 10 ? 1 : 0)} blocks`}</span>
      </div>
      {drawMode && (
        <div className="map-hud top">
          {drawing && <span className="mono">{drawing.x2 - drawing.x1 + 1} × {drawing.z2 - drawing.z1 + 1}</span>}
          <span>{corner ? 'Click the opposite corner' : 'Click one corner, or drag across the area'}</span>
        </div>
      )}
      {tileError && !drawMode && <div className="map-hud top error-hud">Part of the map could not be drawn: {tileError}</div>}
      <div className="map-zoom">
        <button className="ghost small" aria-label="Zoom in" onClick={() => setView({ ...view.current, scale: view.current.scale * 2 })}>+</button>
        <button className="ghost small" aria-label="Zoom out" onClick={() => setView({ ...view.current, scale: view.current.scale / 2 })}>−</button>
      </div>
    </div>
  );
}

// ---- the list beside it ----

interface SideProps {
  index: MapIndex;
  selected: string | null;
  builder: boolean;
  onSelect: (name: string) => void;
  onFly: (x: number, z: number, scale?: number) => void;
  onRemove: (name: string) => void;
}

const MapSide = memo(function MapSide({ index, selected, builder, onSelect, onFly, onRemove }: SideProps) {
  const areas = Object.entries(index.areas);
  const markers = Object.entries(index.markers);
  const players = index.players || [];
  return (
    <aside className="map-side">
      <section className="card">
        <h2>Areas</h2>
        {areas.length ? (
          <ul className="map-list">
            {areas.map(([name, a]) => {
              const r = areaRect(a);
              return (
                <li key={name} className={name === selected ? 'on' : ''}>
                  <button className="map-item" onClick={() => onSelect(name)}>
                    <i style={{ background: areaColor(name) }} />
                    <span><b className="mono">@{name}</b>
                      <span className="muted"> {num(r.x2 - r.x1 + 1)} × {num(r.z2 - r.z1 + 1)}, y {a.box[1]} to {a.box[4]}</span>
                      {a.note && <span className="map-note">{a.note}</span>}</span>
                  </button>
                  {builder && name === selected && (
                    <button className="ghost small" onClick={() => onRemove(name)}>Remove</button>
                  )}
                </li>
              );
            })}
          </ul>
        ) : (
          <p className="muted">{builder
            ? 'None yet. Press Mark area, then click two corners on the map (or drag). The AI sees areas in get_context and can build in "@name".'
            : 'None yet. Builders mark areas for the AI to build in.'}</p>
        )}
      </section>
      {(players.length > 0 || markers.length > 0 || index.spawn) && (
        <section className="card">
          <h2>Places</h2>
          <ul className="map-list">
            {index.spawn && (
              <li><button className="map-item" onClick={() => onFly(index.spawn![0], index.spawn![1], 1)}>
                <i className="spawn" /><span>Spawn <span className="muted mono">{index.spawn[0]}, {index.spawn[1]}</span></span></button></li>
            )}
            {players.map(p => (
              <li key={p.name}><button className="map-item" onClick={() => onFly(p.x, p.z, 2)}>
                <i className="player" /><span>{p.name} <span className="muted mono">{p.x}, {p.y}, {p.z}</span></span></button></li>
            ))}
            {markers.map(([name, [x, y, z]]) => (
              <li key={name}><button className="map-item" onClick={() => onFly(x, z, 2)}>
                <i className="marker" /><span className="mono">@{name} <span className="muted">{x}, {y}, {z}</span></span></button></li>
            ))}
          </ul>
        </section>
      )}
      {index.source && (
        <p className="muted small-print">Drawn from <code>{index.source}</code>, {num(index.regions.length)} region
          file{index.regions.length === 1 ? '' : 's'}.</p>
      )}
      <p className="muted small-print">Drag to move, scroll or pinch to zoom. The map shows what the server has saved; the AI&apos;s edits show
        right away, other changes after the next autosave.</p>
    </aside>
  );
});

// ---- marking an area ----

function NewAreaDialog({ serverId, world, rect, taken, onClose, onSaved }:
    { serverId: string; world: string; rect: Rect; taken: string[]; onClose: () => void; onSaved: (name: string) => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [auto, setAuto] = useState(true);
  const width = rect.x2 - rect.x1 + 1, depth = rect.z2 - rect.z1 + 1;
  const suggestion = useMemo(() => {
    for (let i = 1; ; i++) if (!taken.includes(`area${i}`)) return `area${i}`;
  }, [taken]);

  const submit = async (ev: FormEvent<HTMLFormElement>) => {
    ev.preventDefault();
    const f = new FormData(ev.currentTarget);
    const name = String(f.get('name') || '').trim().toLowerCase();
    if (!NAME.test(name)) return setError('Names are 1-24 of a-z, 0-9, _ and -.');
    const body: Record<string, unknown> = { world, name, rect: [rect.x1, rect.z1, rect.x2, rect.z2], note: String(f.get('note') || '') };
    if (!auto) {
      const y1 = Number(f.get('y1')), y2 = Number(f.get('y2'));
      if (!Number.isInteger(y1) || !Number.isInteger(y2)) return setError('Heights are whole numbers.');
      body.y = [y1, y2];
    }
    if (taken.includes(name) && !confirm(`"${name}" is taken; replace it?`)) return;
    setBusy(true);
    setError('');
    try {
      await api(`${serverApi(serverId)}/map/areas`, { method: 'POST', body: JSON.stringify(body) });
      onSaved(name);
    } catch (ex) {
      setError(message(ex));
      setBusy(false);
    }
  };

  return (
    <Modal className="form-dialog" onClose={onClose}>
      <form onSubmit={submit}>
        <div className="dialog-head"><h2>Mark an area</h2></div>
        <p className="muted" style={{ margin: 0 }}>
          <span className="mono">x {rect.x1}…{rect.x2}, z {rect.z1}…{rect.z2}</span> · {num(width)} × {num(depth)} blocks in {world}</p>
        <label>Name <input type="text" name="name" defaultValue={suggestion} maxLength={24} pattern="[a-zA-Z0-9_\-]{1,24}" required data-autofocus />
          <span className="muted" style={{ fontWeight: 400 }}>The AI uses it as the region &quot;@name&quot;.</span></label>
        <label>What should go here? <textarea name="note" maxLength={300} rows={3} placeholder="A market square with stalls, facing the river" /></label>
        <label className="check"><input type="checkbox" checked={auto} onChange={e => setAuto(e.target.checked)} />
          Height from the ground: the lowest surface up to 32 blocks above the highest</label>
        {!auto && (
          <div className="row3">
            <label>From y <input type="text" name="y1" inputMode="numeric" defaultValue="60" required /></label>
            <label>To y <input type="text" name="y2" inputMode="numeric" defaultValue="100" required /></label>
          </div>
        )}
        {error && <p className="error">{error}</p>}
        <div className="toolbar" style={{ margin: 0, justifyContent: 'flex-end' }}>
          <button type="button" className="ghost" onClick={onClose}>Cancel</button>
          <button type="submit" disabled={busy}>{busy ? 'Saving…' : 'Mark area'}</button>
        </div>
      </form>
    </Modal>
  );
}
