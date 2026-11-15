import { useEffect, useLayoutEffect, useRef, useSyncExternalStore, type MouseEvent, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { finishTip, getView, mountTip, SERVER_VIEW, subscribe, TIPS, type TipId, type TipView } from '../lib/tips';

export function useTips(): TipView {
  return useSyncExternalStore(subscribe, getView, () => SERVER_VIEW);
}

/** Room kept to the window's edges, and under the sticky header. */
const EDGE = 8, HEADER = 52, GAP = 10;

/**
 * Wraps a feature that has a first-time tip (lib/tips.ts). The tip points at the children while it is this one's
 * turn; using the feature ends it. `when` false keeps it out, for a feature that is not usable yet.
 */
export default function Tip({ id, when = true, children }: { id: TipId; when?: boolean; children: ReactNode }) {
  const anchor = useRef<HTMLSpanElement>(null);
  const active = useTips().active === id && when;
  useEffect(() => (when ? mountTip(id) : undefined), [id, when]);

  // React passes a click in the bubble's portal up to here too: only a click on the feature itself counts.
  const used = (ev: MouseEvent) => {
    if (when && anchor.current!.contains(ev.target as Node)) finishTip(id);
  };
  return (
    <span className="tip-anchor" ref={anchor} onClickCapture={used}>
      {children}
      {active && <Bubble id={id} anchor={anchor} />}
    </span>
  );
}

function Bubble({ id, anchor }: { id: TipId; anchor: { current: HTMLSpanElement | null } }) {
  const ref = useRef<HTMLDivElement>(null);
  const tip = TIPS[id];

  // Follows the feature as the page scrolls, resizes and fills in; below it, or above when there is no room.
  useLayoutEffect(() => {
    const bubble = ref.current!;
    const place = () => {
      const target = anchor.current?.firstElementChild ?? anchor.current;
      if (!target) return;
      const a = target.getBoundingClientRect();
      const w = bubble.offsetWidth, h = bubble.offsetHeight;
      const vw = document.documentElement.clientWidth, vh = window.innerHeight;
      const below = a.bottom + GAP + h <= vh - EDGE || a.top - GAP - h < HEADER;
      const center = a.left + a.width / 2;
      const left = Math.max(EDGE, Math.min(center - w / 2, vw - w - EDGE));
      bubble.style.left = `${left}px`;
      bubble.style.top = `${below ? a.bottom + GAP : a.top - GAP - h}px`;
      bubble.style.setProperty('--arrow', `${Math.max(18, Math.min(center - left, w - 18))}px`);
      bubble.dataset.side = below ? 'below' : 'above';
      // Out of sight with its feature, or it would float over the header.
      bubble.hidden = !a.width || a.bottom < HEADER || a.top > vh;
    };
    place();
    const sizes = new ResizeObserver(place);
    sizes.observe(document.body);
    window.addEventListener('scroll', place, { capture: true, passive: true });
    window.addEventListener('resize', place);
    return () => {
      sizes.disconnect();
      window.removeEventListener('scroll', place, { capture: true });
      window.removeEventListener('resize', place);
    };
  }, [anchor]);

  return createPortal(
    <div className="tip" ref={ref} role="note" aria-label={`Tip: ${tip.title}`}>
      <svg className="tip-icon" viewBox="0 0 24 24" aria-hidden="true">
        <path d="M9 18h6M10 21h4M12 3a6 6 0 0 0-3.6 10.8c.6.5 1 1.2 1.1 2.2h5c.1-1 .5-1.700 1.100-2.200A6 6 0 0 0 12 3z" />
      </svg>
      <div><b>{tip.title}</b><p>{tip.text}</p></div>
      <button type="button" className="tip-close" aria-label="Close tip" onClick={() => finishTip(id, true)}>✕</button>
    </div>,
    document.body);
}
