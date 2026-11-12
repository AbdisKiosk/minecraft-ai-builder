'use client';

import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { api, message } from '../lib/api';
import { parseRoute, serverHref, useHash } from '../lib/routes';
import { usePanel } from '../lib/usePanel';
import Dashboard from './Dashboard';
import DiffPage from './DiffPage';
import DraftPage from './DraftPage';
import ServerPage from './ServerPage';

type Crumb = [label: string, href?: string];

export default function Panel() {
  const { session, servers, live, login, logout } = usePanel();
  const route = parseRoute(useHash());
  // Keeps "connected 3m ago" and ETAs fresh.
  const [, tick] = useState(0);
  useEffect(() => {
    if (!live) return;
    const timer = setInterval(() => tick(n => n + 1), 5000);
    return () => clearInterval(timer);
  }, [live]);

  const crumbs: Crumb[] = [];
  let page: ReactNode = <p className="muted">Loading…</p>;
  if (session === 'login') {
    page = <Login onDone={login} />;
  } else if (session === 'ready') {
    if (route.page === 'dashboard') {
      crumbs.push(['Servers']);
      page = <Dashboard servers={servers} />;
    } else {
      const server = servers.get(route.server);
      const name = server ? server.name || server.id : route.server;
      crumbs.push(['Servers', '#/']);
      if (route.page === 'server') {
        crumbs.push([name]);
        page = <ServerPage key={route.server} id={route.server} server={server} servers={servers} />;
      } else if (route.page === 'diff') {
        crumbs.push([name, serverHref(route.server)], [route.id]);
        page = <DiffPage key={`${route.server}/${route.id}`} server={route.server} id={route.id} servers={servers} />;
      } else {
        crumbs.push([name, serverHref(route.server)], ['draft ' + route.id]);
        page = <DraftPage key={`${route.server}/${route.id}`} server={route.server} id={route.id} servers={servers} />;
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
          AIBuilder Panel
        </a>
        <nav id="crumbs">
          {crumbs.map(([label, href]) => <span key={label}>{href ? <a href={href}>{label}</a> : label}</span>)}
        </nav>
        <span className={`live ${live ? 'on' : ''}`} title="Live updates" />
        {session === 'ready' && <button className="ghost" onClick={logout}>Log out</button>}
      </header>
      <main>{page}</main>
    </>
  );
}

function Login({ onDone }: { onDone: () => void }) {
  const [error, setError] = useState('');
  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setError('');
    const password = new FormData(e.currentTarget).get('password');
    try {
      await api('/api/login', { method: 'POST', body: JSON.stringify({ password }) });
      onDone();
    } catch (ex) {
      setError(message(ex));
    }
  };
  return (
    <form className="login card" onSubmit={submit}>
      <h1>Log in</h1>
      <label>Password <input type="password" name="password" autoComplete="current-password" required autoFocus /></label>
      <button type="submit">Log in</button>
      <p className="error">{error}</p>
    </form>
  );
}
