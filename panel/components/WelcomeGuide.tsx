import type { ReactNode } from 'react';
import { resetTips } from '../lib/tips';
import Modal from './Modal';
import { useTips } from './Tip';

function Point({ icon, title, children }: { icon: string; title: string; children: ReactNode }) {
  return (
    <li>
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d={icon} /></svg>
      <div><b>{title}</b><span>{children}</span></div>
    </li>
  );
}

/**
 * The welcome sheet a new account sees once, and the header's Guide button opens again: what the panel does, in four
 * lines. The steps themselves are on the dashboard and the setup page underneath.
 */
export default function WelcomeGuide({ hasServers, onClose }: { hasServers: boolean; onClose: () => void }) {
  const seen = useTips().done.length;
  return (
    <Modal className="card guide" onClose={onClose}>
      <svg className="guide-logo" viewBox="0 0 16 16" aria-hidden="true">
        <path d="M8 1 15 4.5v7L8 15 1 11.5v-7z" className="c1" /><path d="M8 8v7L1 11.5v-7z" className="c2" /><path d="M8 8 15 4.5v7L8 15z" className="c3" />
      </svg>
      <h1>Welcome to AIBuilder</h1>
      <p className="muted guide-lead">Your AI assistant builds in your Minecraft world. This page is where you watch, check and undo it.</p>
      <ul className="guide-points">
        <Point title="Connect in three steps" icon="M9 7H7a5 5 0 0 0 0 10h2M15 7h2a5 5 0 0 1 0 10h-2M8 12h8">
          Add your server, drop in the plugin made for it, and give your AI the address. No ports, no config files.</Point>
        <Point title="Ask in plain words" icon="M4 5h16v11H9l-5 4z">
          &ldquo;Build a small oak cabin next to me.&rdquo; Claude Code or any assistant that speaks MCP works.</Point>
        <Point title="See every edit, undo any of them" icon="M4 12a8 8 0 1 0 2.300-5.600M4 4v5h5M12 8v4l3 2">
          Each build is recorded with pictures of what changed, and can be reverted.</Point>
        <Point title="Guide it as you go" icon="M12 21s-6-5.300-6-10a6 6 0 0 1 12 0c0 4.700-6 10-6 10zM12 9v4M10 11h4">
          Mark areas on the map and save lessons, so it builds where and how you want.</Point>
      </ul>
      <button type="button" className="guide-go" data-autofocus onClick={onClose}>{hasServers ? 'Got it' : 'Get started'}</button>
      <p className="muted guide-foot">Tips point out the important parts the first time you see them.
        {seen > 0 && <> <button type="button" className="link" onClick={() => { resetTips(); onClose(); }}>Show the tips again</button></>}</p>
    </Modal>
  );
}
