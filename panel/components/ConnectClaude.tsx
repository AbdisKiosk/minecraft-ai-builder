import { useEffect, useState } from 'react';
import { api, message, serverApi } from '../lib/api';
import { ago } from '../lib/format';
import type { Server } from '../lib/types';
import { CopyButton } from './shared';

/** A name for the server in Claude Code: letters, digits and dashes, e.g. minecraft-survival-smp. */
export function mcpName(server: { id: string; name: string }): string {
  const slug = server.name.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40);
  if (!slug) return 'minecraft-' + server.id;
  return slug.startsWith('minecraft') ? slug : 'minecraft-' + slug;
}

export interface AccessToken { token: string | null; created: number; lastUsed: number | null }

/** The command that adds the server to Claude Code, with the user's access token for it. */
export function claudeCommand(server: { id: string; name: string }, token: string): string {
  return `claude mcp add --transport http --scope user ${mcpName(server)} ${window.location.origin}/s/${server.id}/mcp`
    + ` --header "Authorization: Bearer ${token}"`;
}

/** The steps from nothing to Claude Code building on this server, with a command that has the user's token in it. */
export default function ConnectClaude({ server }: { server: Server }) {
  const [saved, setSaved] = useState<AccessToken | null | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const url = `${window.location.origin}/s/${server.id}`;
  const name = mcpName(server);
  const base = `${serverApi(server.id)}/access-token`;

  useEffect(() => {
    let current = true;
    api<{ accessToken: AccessToken | null }>(base).then(
      r => { if (current) setSaved(r.accessToken); },
      ex => { if (current) { setSaved(null); setError(message(ex)); } });
    return () => { current = false; };
  }, [base]);

  const make = async (renew: boolean) => {
    if (renew && !confirm('Make a new token for this server? The command you used before stops working on every computer '
      + 'that has it, until you run the new one there.')) return;
    setBusy(true);
    setError('');
    try {
      setSaved((await api<{ accessToken: AccessToken }>(base, { method: 'POST', body: JSON.stringify({ renew }) })).accessToken);
    } catch (ex) {
      setError(message(ex));
    } finally {
      setBusy(false);
    }
  };

  const token = saved?.token;
  const command = token ? claudeCommand(server, token) : '';
  return (
    <div className="connect-claude">
      <ol className="howto">
        <li>
          <b>Install Claude Code</b> on your computer, if you haven&apos;t yet. Follow the steps
          at <a href="https://claude.com/claude-code" target="_blank" rel="noreferrer">claude.com/claude-code</a>.
          It runs in a terminal: <i>Terminal</i> on a Mac, <i>PowerShell</i> on Windows.
        </li>
        <li>
          <b>Paste your command into that terminal</b> and press Enter. It tells Claude Code where your server is and lets
          it build as you.
          {saved === undefined ? <p className="muted">Loading…</p> : !token ? (
            <div className="toolbar step-action">
              <button onClick={() => make(false)} disabled={busy}>{busy ? 'Making your command…' : 'Show my command'}</button>
            </div>
          ) : (
            <>
              <div className="command"><pre>{command}</pre><CopyButton text={command} label="Copy command" /></div>
              <p className="muted small-print">This command contains your personal token for <b>{server.name}</b>: don&apos;t share it.
                It works on this server only, and you can come back here to copy it again.{' '}
                {saved?.lastUsed ? <>Last used {ago(saved.lastUsed)}. </> : null}
                If Claude Code says <code>{name}</code> already exists, run <code>claude mcp remove {name}</code> first.
                Shared it by mistake? <button type="button" className="link" onClick={() => make(true)} disabled={busy}>Make a new token</button>.
              </p>
            </>
          )}
          {error && <p className="error">{error}</p>}
        </li>
        <li>
          <b>Ask Claude to build.</b> Join your server in Minecraft, type <code>claude</code> in the terminal and ask
          something like: <q>Build a small oak cabin with a garden next to me.</q> You&apos;ll see each edit appear on this page,
          and you can undo any of them.
        </li>
      </ol>
      <details className="more">
        <summary>Using build scripts or another MCP client?</summary>
        <p>The server&apos;s MCP address is <code>{url}/mcp</code>; send your token for this server (the one in the command above)
          as <code>Authorization: Bearer …</code>.
          The Python SDK and the repository&apos;s <code>mcp.json</code> read these two variables:</p>
        <pre>export AIBUILDER_URL={url} AIBUILDER_TOKEN={token || '<your token>'}</pre>
      </details>
    </div>
  );
}
