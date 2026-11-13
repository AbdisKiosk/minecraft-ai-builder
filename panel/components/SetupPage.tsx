import { useEffect, useState, type ReactNode } from 'react';
import { api, downloadPlugin, message, pluginDownload, serverApi } from '../lib/api';
import { serverHref } from '../lib/routes';
import { can, type Server } from '../lib/types';
import ConnectClaude from './ConnectClaude';
import { RelaySetup, type RelayConfig } from './shared';

type Download = 'idle' | 'busy' | 'done' | 'failed';

/** Watches the plugin download that adding the server started, and starts new ones. */
function useDownload(id: string) {
  const [state, setState] = useState<Download>('idle');
  const [error, setError] = useState('');
  const [name, setName] = useState('');
  const follow = (p: Promise<string>) => {
    setState('busy');
    setError('');
    p.then(n => { setName(n); setState('done'); }, ex => { setState('failed'); setError(message(ex)); });
  };
  useEffect(() => {
    const p = pluginDownload(id);
    if (p) follow(p);
  }, [id]);
  return { state, error, name, start: () => follow(downloadPlugin(id)) };
}

function Step({ n, title, done, children }: { n: number; title: string; done?: boolean; children?: ReactNode }) {
  return (
    <li className={`setup-step ${done ? 'done' : ''}`}>
      <span className="num" aria-hidden="true">{done ? '✓' : n}</span>
      <div className="body"><h2>{title}{done && <span className="sr-only"> (done)</span>}</h2>{children}</div>
    </li>
  );
}

/** The guide from a freshly added server to Claude building on it, with the connection shown live. */
export default function SetupPage({ server }: { server: Server }) {
  const owner = can(server.role, 'owner');
  const connected = server.online || !!server.lastSeen;
  const download = useDownload(server.id);

  return (
    <div className="setup">
      <div className="page-head"><div>
        <h1>Set up {server.name}</h1>
        <div className="muted">Three steps. Most people are done in five minutes.</div>
      </div></div>

      <ol className="setup-steps">
        <Step n={1} title="Add your server" done>
          <p className="muted"><b>{server.name}</b> is on your account.</p>
        </Step>

        <Step n={2} title="Install the plugin on your server" done={connected}>
          {owner ? <>
            <ol className="howto">
              <li>Your server needs to run <b>Paper</b> (or Spigot) with
                the <a href="https://modrinth.com/plugin/worldedit" target="_blank" rel="noreferrer">WorldEdit</a> plugin.
                Most Minecraft hosts can install both for you.</li>
              <li>
                {download.state === 'done' ? <>Find <b>{download.name}</b> in your downloads. </>
                  : download.state === 'busy' ? <>Your download is starting… </> : <>Download your copy of the plugin. </>}
                It&apos;s made for <b>{server.name}</b> and already knows how to connect here.
                <div className="toolbar step-action">
                  <button className={download.state === 'done' ? 'ghost' : ''} onClick={download.start} disabled={download.state === 'busy'}>
                    {download.state === 'busy' ? 'Downloading…' : download.state === 'done' ? 'Download again' : 'Download the plugin'}
                  </button>
                </div>
                {download.error && <p className="error">Download failed: {download.error}</p>}
              </li>
              <li>Put <b>{download.name || 'the AIBuilder jar'}</b> in your server&apos;s <code>plugins</code> folder, next to WorldEdit. On a hosted server,
                use the host&apos;s file manager or FTP. Replace any older AIBuilder file that&apos;s already there.</li>
              <li><b>Restart the server.</b></li>
            </ol>
            <p className="muted small-print">Keep this file to yourself: it contains your server&apos;s private key.</p>
          </> : <p className="muted">The server&apos;s owner installs the plugin.</p>}

          <Status server={server} />
          {owner && !connected && <Troubleshooting server={server} />}
        </Step>

        <Step n={3} title="Connect your AI">
          {can(server.role, 'builder') ? <>
            {!connected && <p className="muted">You can do this now. Your AI can build once your server has connected.</p>}
            <ConnectClaude server={server} />
          </> : <p className="muted">You&apos;re a viewer on this server, so an AI can&apos;t build here as you. Ask an admin for the
            builder role.</p>}
        </Step>
      </ol>

      <div className="toolbar setup-done">
        <a className={`button ${connected ? '' : 'ghost'}`} href={serverHref(server.id)}>
          {connected ? 'Go to your server' : 'Skip for now'}</a>
      </div>
    </div>
  );
}

function Status({ server }: { server: Server }) {
  if (server.online) {
    const i = server.info || {};
    return (
      <div className="status ok" role="status">
        <span className="dot on" />
        <span><b>Connected!</b> <span className="muted">{[i.mc && 'Minecraft ' + i.mc, i.bridge].filter(Boolean).join(' · ')}</span></span>
      </div>
    );
  }
  if (server.lastSeen) {
    return <div className="status" role="status"><span className="dot" />
      <span>It has connected before, but is offline right now. Is the server running?</span></div>;
  }
  return (
    <div className="status waiting" role="status">
      <span className="spinner" aria-hidden="true" />
      <span>Waiting for <b>{server.name}</b> to connect… This page updates by itself.</span>
    </div>
  );
}

function Troubleshooting({ server }: { server: Server }) {
  const [relay, setRelay] = useState<RelayConfig | null>(null);
  const [error, setError] = useState('');
  const show = async () => {
    try {
      setRelay((await api<{ relay: RelayConfig }>(`${serverApi(server.id)}/token`)).relay);
    } catch (ex) {
      setError(message(ex));
    }
  };
  return (
    <details className="more">
      <summary>Restarted, but it doesn&apos;t connect?</summary>
      <ul>
        <li>Look in the server console for lines starting with <code>[AIBuilder]</code>. If it says WorldEdit is missing, install
          WorldEdit and restart again.</li>
        <li>Make sure there is only one AIBuilder file in <code>plugins</code>, and that it&apos;s the one you downloaded here.</li>
        <li>Type <code>aibuilder</code> in the server console to see the connection status.</li>
        <li>Your server must be able to reach <code>{window.location.host}</code> on the internet. Some hosts block outgoing
          connections; their support can open it.</li>
      </ul>
      <p>Prefer to set it up by hand? Put these lines in <code>plugins/AIBuilder/config.yml</code> instead.</p>
      {relay ? <div className="connect"><RelaySetup relay={relay} /></div>
        : <button className="ghost small" onClick={show}>Show the settings</button>}
      {error && <p className="error">{error}</p>}
    </details>
  );
}
