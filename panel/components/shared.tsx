import type { ReactNode } from 'react';
import { ago, duration, num } from '../lib/format';
import { PLUGIN_JAR_HREF } from '../lib/pluginJar';
import { diffHref, draftHref, serverHref } from '../lib/routes';
import type { Counts, Detail, Job, Placed, Server } from '../lib/types';

function Count({ n, sign, cls }: { n: number; sign: string; cls: string }) {
  return <span className={n ? cls : 'zero'}>{sign}{num(n)}</span>;
}

/** +added −removed ~changed, or "no diff" for an edit that was not tracked. */
export function Stats({ of, tracked = true }: { of: Counts; tracked?: boolean }) {
  if (!tracked) return <span className="muted stat">no diff</span>;
  return (
    <span className="stat">
      <Count n={of.added} sign="+" cls="add" /> <Count n={of.removed} sign="−" cls="rem" /> <Count n={of.changed} sign="~" cls="chg" />
    </span>
  );
}

export function Pills({ of }: { of: Counts }) {
  return (
    <div className="pills">
      <span className={`pill ${of.added ? 'add' : 'zero'}`}>+{num(of.added)} added</span>
      <span className={`pill ${of.removed ? 'rem' : 'zero'}`}>−{num(of.removed)} removed</span>
      <span className={`pill ${of.changed ? 'chg' : 'zero'}`}>~{num(of.changed)} changed</span>
    </div>
  );
}

export function Badge({ kind, children }: { kind?: string; children: ReactNode }) {
  return <span className={`badge ${kind || ''}`}>{children}</span>;
}

export function ServerMeta({ server: s }: { server: Server }) {
  const i = s.info || {};
  const parts: ReactNode[] = [];
  if (i.mc) parts.push('Minecraft ' + i.mc);
  if (i.bridge) parts.push(i.bridge);
  if (i.plugin) parts.push('plugin ' + i.plugin);
  if (i.review) parts.push(<Badge kind="running">review on</Badge>);
  const when = s.since || s.lastSeen;
  if (s.online) parts.push('connected ' + (when ? ago(when) : ''));
  else parts.push(when ? 'offline, last seen ' + ago(when) : 'never connected');
  return <>{parts.map((p, n) => <span key={n}>{n > 0 && ' · '}{p}</span>)}</>;
}

/** Where to get the plugin, for the places that tell someone to install it. */
export function PluginDownload() {
  return (
    <p><a className="button ghost small" href={PLUGIN_JAR_HREF}>Download the plugin</a>{' '}
      <span className="muted">Put the jar in the server&apos;s <code>plugins</code> folder, next to WorldEdit, and start
        the server once so it writes its config.</span></p>
  );
}

export interface RelayConfig { url: string; token: string }

/** The relay settings for a server's plugin config, with the token that is only shown once. */
export function RelaySetup({ relay }: { relay: RelayConfig }) {
  const yaml = `relay:\n  url: "${relay.url}"\n  token: "${relay.token}"`;
  return (
    <>
      <p>Put this in the server&apos;s <code>plugins/AIBuilder/config.yml</code> and restart it:</p>
      <pre>{yaml}</pre>
      <div className="toolbar"><CopyButton text={yaml} /></div>
      <p className="muted">The token is shown only now. If you lose it, make a new one from the server&apos;s page.</p>
    </>
  );
}

export function CopyButton({ text, label = 'Copy' }: { text: string; label?: string }) {
  return (
    <button type="button" className="ghost small" onClick={ev => {
      const b = ev.currentTarget;
      navigator.clipboard.writeText(text).then(() => {
        b.textContent = 'Copied';
        setTimeout(() => { b.textContent = label; }, 1500);
      });
    }}>{label}</button>
  );
}

function JobRow({ job, server }: { job: Job; server: string }) {
  const pct = job.total > 0 ? Math.min(100, Math.round(100 * job.processed / job.total)) : 0;
  const running = job.status === 'running';
  const phase = job.phase === 'snapshot' ? 'reading before' : job.phase === 'diff' ? 'reading result' : null;
  // A preview runs like a build (with a percentage) but places nothing.
  const elapsed = Date.now() - job.started;
  let eta = '';
  if (running && !phase && pct > 3 && pct < 100) eta = ' · ~' + duration(Math.round(elapsed * (100 - pct) / pct)) + ' left';
  const who = [job.tool, job.caller && job.caller !== 'console' ? job.caller : null, job.source].filter(Boolean).join(' · ');
  const status = job.draft && job.status === 'done' ? 'in draft' : job.status;
  return (
    <div className="job">
      <div className="job-line">
        <span className="what"><span className="mono">{job.id}</span> {who}</span>
        <span className="muted">
          {running ? (phase || pct + '%') + eta : (
            <>
              <Badge kind={job.draft ? 'running' : job.status}>{status}</Badge> {num(job.changed)} blocks · {duration(job.ms)}{' '}
              {job.draft ? <a href={draftHref(server, job.draft)}>draft</a>
                : job.history ? <a href={diffHref(server, job.history)}>diff</a> : null}
            </>
          )}
        </span>
      </div>
      {running && <div className={`bar ${phase ? 'phase' : ''}`}><i style={{ width: `${phase ? 100 : pct}%` }} /></div>}
      {job.error && <div className="error">{job.error}</div>}
    </div>
  );
}

export function Jobs({ server: s, recent }: { server: Server; recent: number }) {
  const jobs = [...s.running, ...s.recent.slice(0, recent)];
  if (!jobs.length) return <div className="empty">No jobs since the panel started.</div>;
  return <>{jobs.map(j => <JobRow key={j.id} job={j} server={s.id} />)}</>;
}

export function DraftBanners({ server: s }: { server: Server }) {
  return (
    <>
      {(s.drafts || []).map(d => (
        <a key={d.id} className="draft-banner" href={draftHref(s.id, d.id)}>
          <b>Draft waiting for approval</b> <span className="muted">{d.world} · {num(d.edits)} edit{d.edits === 1 ? '' : 's'}</span>
          <span className="spacer" /><Stats of={d} />
        </a>
      ))}
    </>
  );
}

export function TypesCard({ of: e }: { of: Detail }) {
  const maxType = Math.max(1, ...e.types.map(t => t.added + t.removed));
  return (
    <section className="card"><h2>Blocks by type</h2><div className="table-wrap"><table>
      <thead><tr><th>Block</th><th>Added</th><th>Removed</th><th></th></tr></thead>
      <tbody>
        {e.types.map(t => (
          <tr key={t.type}>
            <td className="mono">{t.type}</td>
            <td className={`${t.added ? 'add' : 'zero'} stat`}>+{num(t.added)}</td>
            <td className={`${t.removed ? 'rem' : 'zero'} stat`}>−{num(t.removed)}</td>
            <td><div className="typebar" style={{ width: `${Math.max(4, 100 * (t.added + t.removed) / maxType)}%` }}>
              <span className="a" style={{ flex: t.added }} /><span className="r" style={{ flex: t.removed }} /></div></td>
          </tr>
        ))}
        {!e.types.length && <tr><td colSpan={4} className="empty">Nothing changed.</td></tr>}
      </tbody>
    </table></div></section>
  );
}

export function ChangesCard({ of: e }: { of: Detail }) {
  return (
    <section className="card">
      <h2>Changed blocks <span className="muted">({num(e.listed)} of {num(e.added + e.removed + e.changed)})</span></h2>
      <div className="table-wrap"><table>
        <thead><tr><th>Position</th><th>Before</th><th>After</th></tr></thead>
        <tbody>
          {e.changes.map(c => (
            <tr key={c.pos.join(' ')}><td className="mono">{c.pos.join(' ')}</td>
              <td className="mono rem">{c.before}</td><td className="mono add">{c.after}</td></tr>
          ))}
        </tbody>
      </table></div>
    </section>
  );
}

/** The blocks a revert or an approve left alone because they changed in the meantime. */
export function Conflicts({ result: r, what, placedLabel }: { result: Placed; what: string; placedLabel: string }) {
  if (!r.conflicts) return null;
  return (
    <div className="warn">
      <b>{num(r.conflicts)} block{r.conflicts === 1 ? '' : 's'} changed since {what}</b> and {r.job ? 'were' : 'are'} left alone:
      <table>
        <thead><tr><th>Position</th><th>{placedLabel}</th><th>Now</th></tr></thead>
        <tbody>
          {(r.conflictList || []).map(c => (
            <tr key={c.pos.join(' ')}><td className="mono">{c.pos.join(' ')}</td><td className="mono">{c.expected}</td><td className="mono">{c.now}</td></tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function ServerPageLink({ server }: { server: string }) {
  return <a href={serverHref(server)}>the server page</a>;
}
