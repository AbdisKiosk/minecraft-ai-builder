import { useEffect, useRef, type MouseEventHandler, type ReactNode } from 'react';

/** A modal <dialog> that is open for as long as it is rendered. */
export default function Modal({ className, onClose, onClick, children }:
    { className?: string; onClose: () => void; onClick?: MouseEventHandler; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    // No close() on cleanup: removing the element closes it, and a close event here would call onClose.
    const dialog = ref.current!;
    if (!dialog.open) dialog.showModal();
  }, []);
  return <dialog ref={ref} className={className} onClose={onClose} onClick={onClick}>{children}</dialog>;
}
