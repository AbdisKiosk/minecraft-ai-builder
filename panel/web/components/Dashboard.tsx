import { serverHref } from '../lib/routes';
import type { Servers } from '../lib/usePanel';
import { DraftBanners, Jobs, ServerMeta } from './shared';

export default function Dashboard({ servers: all }: { servers: Servers }) {
  const servers = [...all.values()].sort((a, b) => a.id.localeCompare(b.id));
  if (!servers.length) {
    return (
      <div className="card"><h2>No servers yet</h2>
        <p>On the panel host run <code>java -jar AIBuilderPanel.jar add-server &lt;id&gt;</code> and put the printed{' '}
          <code>relay</code> settings in that server&apos;s <code>plugins/AIBuilder/config.yml</code>.</p></div>
    );
  }
  const online = servers.filter(s => s.online).length;
  const busy = servers.reduce((n, s) => n + s.running.length, 0);
  return (
    <>
      <div className="page-head"><div><h1>Servers</h1>
        <div className="muted">{online} of {servers.length} online · {busy} job{busy === 1 ? '' : 's'} running</div></div></div>
      <div className="grid">
        {servers.map(s => (
          <section className="card" key={s.id}>
            <div className="server-head"><span className={`dot ${s.online ? 'on' : ''}`} /><h2>{s.name || s.id}</h2>
              <a className="button ghost small" href={serverHref(s.id)}>History</a></div>
            <div className="meta"><span className="mono">{s.id}</span> · <ServerMeta server={s} /></div>
            <DraftBanners server={s} />
            <div className="jobs"><Jobs server={s} recent={4} /></div>
          </section>
        ))}
      </div>
    </>
  );
}
