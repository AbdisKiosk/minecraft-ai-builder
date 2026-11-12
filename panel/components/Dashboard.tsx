import { useState } from 'react';
import { serverHref, setupHref } from '../lib/routes';
import type { Servers } from '../lib/usePanel';
import { AddServerDialog, AddServerForm } from './AddServer';
import { Badge, DraftBanners, Jobs, ServerMeta } from './shared';

export default function Dashboard({ servers: all, onCreated }: { servers: Servers; onCreated: () => Promise<void> }) {
  const [adding, setAdding] = useState(false);
  const servers = [...all.values()].sort((a, b) => a.name.localeCompare(b.name) || a.id.localeCompare(b.id));
  const online = servers.filter(s => s.online).length;
  const busy = servers.reduce((n, s) => n + s.running.length, 0);

  if (!servers.length) return <Welcome onCreated={onCreated} />;
  return (
    <>
      <div className="page-head"><div><h1>Your servers</h1>
        <div className="muted">{online} of {servers.length} online · {busy} job{busy === 1 ? '' : 's'} running</div></div>
        <button onClick={() => setAdding(true)}>+ Add server</button></div>
      <div className="grid">
        {servers.map(s => {
          const unfinished = !s.online && !s.lastSeen;
          return (
            <section className="card" key={s.id}>
              <div className="server-head"><span className={`dot ${s.online ? 'on' : ''}`} /><h2>{s.name}</h2>
                {s.role !== 'owner' && <Badge>{s.role}</Badge>}
                <a className="button ghost small" href={serverHref(s.id)}>Open</a></div>
              <div className="meta"><ServerMeta server={s} /></div>
              {unfinished && s.role === 'owner' ? (
                <a className="finish-setup" href={setupHref(s.id)}>
                  <b>Finish setting up</b><span className="muted">Install the plugin to connect this server.</span><span aria-hidden="true">→</span>
                </a>
              ) : <>
                <DraftBanners server={s} />
                <div className="jobs"><Jobs server={s} recent={4} /></div>
              </>}
            </section>
          );
        })}
      </div>
      {adding && <AddServerDialog onClose={() => setAdding(false)} onCreated={onCreated} />}
    </>
  );
}

/** A new account's dashboard: the three steps, and the first one right here. */
function Welcome({ onCreated }: { onCreated: () => Promise<void> }) {
  return (
    <div className="welcome">
      <section className="card welcome-card">
        <h1>Let&apos;s get Claude building on your Minecraft server</h1>
        <p className="lead">Name your server and you&apos;ll get a plugin that&apos;s already set up for it. Drop it in, restart,
          and you&apos;re connected. No ports, no config files.</p>
        <AddServerForm onCreated={onCreated} big />
      </section>
      <ol className="overview">
        <li><b>Add your server</b><span>Give it a name. You get a ready-made plugin file.</span></li>
        <li><b>Install the plugin</b><span>Put it in your server&apos;s plugins folder and restart. We&apos;ll see it connect.</span></li>
        <li><b>Connect Claude</b><span>Paste one command into Claude Code, then ask it to build.</span></li>
      </ol>
      <p className="muted center">Joining a friend&apos;s server instead? Ask them for an invite link.</p>
    </div>
  );
}
