import { useState } from 'react';
import { serverHref } from '../lib/routes';
import type { Servers } from '../lib/usePanel';
import NewServerDialog from './NewServerDialog';
import { Badge, DraftBanners, Jobs, ServerMeta } from './shared';

export default function Dashboard({ servers: all, onCreated }: { servers: Servers; onCreated: () => void }) {
  const [adding, setAdding] = useState(false);
  const servers = [...all.values()].sort((a, b) => a.name.localeCompare(b.name) || a.id.localeCompare(b.id));
  const online = servers.filter(s => s.online).length;
  const busy = servers.reduce((n, s) => n + s.running.length, 0);
  return (
    <>
      <div className="page-head"><div><h1>Servers</h1>
        {servers.length > 0 && <div className="muted">{online} of {servers.length} online · {busy} job{busy === 1 ? '' : 's'} running</div>}</div>
        <button onClick={() => setAdding(true)}>New server</button></div>
      {!servers.length ? (
        <div className="card"><h2>No servers yet</h2>
          <p>Add your Minecraft server, paste the two lines it gives you into the AIBuilder plugin&apos;s config, and it connects
            here by itself. The server needs no open port.</p>
          <p>Someone else&apos;s server? Ask its owner for an invite link.</p>
          <button onClick={() => setAdding(true)}>Add a server</button></div>
      ) : (
        <div className="grid">
          {servers.map(s => (
            <section className="card" key={s.id}>
              <div className="server-head"><span className={`dot ${s.online ? 'on' : ''}`} /><h2>{s.name}</h2>
                {s.role !== 'owner' && <Badge>{s.role}</Badge>}
                <a className="button ghost small" href={serverHref(s.id)}>Open</a></div>
              <div className="meta"><span className="mono">{s.id}</span> · <ServerMeta server={s} /></div>
              <DraftBanners server={s} />
              <div className="jobs"><Jobs server={s} recent={4} /></div>
            </section>
          ))}
        </div>
      )}
      {adding && <NewServerDialog onClose={() => setAdding(false)} onCreated={onCreated} />}
    </>
  );
}
