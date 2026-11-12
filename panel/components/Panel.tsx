'use client';

import { useEffect, useState, type ReactNode } from 'react';
import { assetsHref, parseRoute, serverHref, tokensHref, useHash } from '../lib/routes';
import { usePanel } from '../lib/usePanel';
import AssetsPage from './AssetsPage';
import Dashboard from './Dashboard';
import DiffPage from './DiffPage';
import DraftPage from './DraftPage';
import LessonsPage from './LessonsPage';
import MapPage from './MapPage';
import ServerPage from './ServerPage';
import SetupPage from './SetupPage';
import TokensPage from './TokensPage';

type Crumb = [label: string, href?: string];
export interface Me { name: string | null; email: string | null; picture: string | null }

export default function Panel({ me }: { me: Me }) {
  const { servers, loaded, error, live, reload } = usePanel();
  const route = parseRoute(useHash());
  // Keeps "connected 3m ago" and ETAs fresh.
  const [, tick] = useState(0);
  useEffect(() => {
    const timer = setInterval(() => tick(n => n + 1), 5000);
    return () => clearInterval(timer);
  }, []);

  const crumbs: Crumb[] = [];
  let page: ReactNode = error ? <div className="card"><p className="error">{error}</p></div> : <p className="muted">Loading…</p>;
  if (loaded) {
    if (route.page === 'dashboard') {
      crumbs.push(['Servers']);
      page = <Dashboard servers={servers} onCreated={reload} />;
    } else if (route.page === 'tokens') {
      crumbs.push(['Servers', '#/'], ['Access tokens']);
      page = <TokensPage />;
    } else if (route.page === 'assets') {
      crumbs.push(['Servers', '#/'], ['Assets']);
      page = <AssetsPage servers={servers} />;
    } else {
      const server = servers.get(route.server);
      const name = server ? server.name : route.server;
      crumbs.push(['Servers', '#/']);
      if (!server) {
        crumbs.push([route.server]);
        page = <div className="card"><h2>Not found</h2><p>You don&apos;t have access to a server with id <code>{route.server}</code>.</p></div>;
      } else if (route.page === 'setup') {
        crumbs.push([name, serverHref(route.server)], ['Set up']);
        page = <SetupPage key={route.server} server={server} />;
      } else if (route.page === 'server') {
        crumbs.push([name]);
        page = <ServerPage key={route.server} server={server} servers={servers} />;
      } else if (route.page === 'map') {
        crumbs.push([name, serverHref(route.server)], ['Map']);
        page = <MapPage key={route.server} server={server} world={route.world} />;
      } else if (route.page === 'lessons') {
        crumbs.push([name, serverHref(route.server)], ['Lessons']);
        page = <LessonsPage key={route.server} server={server} />;
      } else if (route.page === 'diff') {
        crumbs.push([name, serverHref(route.server)], [route.id]);
        page = <DiffPage key={`${route.server}/${route.id}`} server={server} id={route.id} servers={servers} />;
      } else {
        crumbs.push([name, serverHref(route.server)], ['draft ' + route.id]);
        page = <DraftPage key={`${route.server}/${route.id}`} server={server} id={route.id} servers={servers} />;
      }
    }
  }

  return (
    <>
      <header className="top">
        <a className="brand" href="#/">
          <svg viewBox="0 0 16 16" aria-hidden="true">
            <path d="M8 1 15 4.5v7L8 15 1 11.5v-7z" className="c1" /><path d="M8 8v7L1 11.5v-7z" className="c2" /><path d="M8 8 15 4.5v7L8 15z" className="c3" />
          </svg>
          AIBuilder
        </a>
        <nav id="crumbs">
          {crumbs.map(([label, href]) => <span key={label}>{href ? <a href={href}>{label}</a> : label}</span>)}
        </nav>
        <span className={`live ${live ? 'on' : ''}`} title={live ? 'Live updates on' : 'Reconnecting…'} role="img"
          aria-label={live ? 'Live updates on' : 'Reconnecting'} />
        <a className="button ghost small" href={tokensHref}>Access tokens</a>
        <a className="button ghost small" href={assetsHref}>Assets</a>
        <span className="me hide-sm" title={me.email || ''}>
          {me.picture && <img src={me.picture} alt="" referrerPolicy="no-referrer" />}{me.name || me.email}
        </span>
        <a className="button ghost small" href="/auth/logout">Log out</a>
      </header>
      <main>{page}</main>
    </>
  );
}
