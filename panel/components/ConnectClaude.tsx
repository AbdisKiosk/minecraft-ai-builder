import { useEffect, useState } from 'react';
import { api, message, serverApi } from '../lib/api';
import { ago } from '../lib/format';
import type { Server } from '../lib/types';
import { CopyButton } from './shared';

/** A name for the server in the assistant: letters, digits and dashes, e.g. minecraft-survival-smp. */
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

/** What an assistant needs to reach the server: the MCP address and token, and the command that gives Claude Code both. */
export function ConnectionDetails({ server, token }: { server: { id: string; name: string }; token: string }) {
  const address = `${window.location.origin}/s/${server.id}/mcp`;
  const header = `Authorization: Bearer ${token}`;
  const command = claudeCommand(server, token);
  return (
    <>
      <p className="connect-lead">Add it to your assistant as a remote (HTTP) MCP server:</p>
      <dl className="connect-details">
        <dt>Address</dt><dd><code>{address}</code><CopyButton text={address} /></dd>
        <dt>Header</dt><dd><code>{header}</code><CopyButton text={header} /></dd>
      </dl>
      <p className="connect-lead">Using Claude Code? This one command does the same:</p>
      <div className="command"><pre>{command}</pre><CopyButton text={command} label="Copy command" /></div>
    </>
  );
}

/** The steps from nothing to an AI assistant building on this server, with the user's token in the details. */
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
    if (renew && !confirm('Make a new token for this server? The details you used before stop working on every computer '
      + 'that has them, until you connect again with the new ones.')) return;
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
  return (
    <div className="connect-claude">
      <ol className="howto">
        <li>
          <b>Open your AI assistant.</b> Any assistant that can use MCP servers works, Claude Code for example.
        </li>
        <li>
          <b>Connect it to your server.</b> This tells your assistant where your server is and lets it build as you.
          {saved === undefined ? <p className="muted">Loading…</p> : !token ? (
            <div className="toolbar step-action">
              <button onClick={() => make(false)} disabled={busy}>{busy ? 'Making your token…' : 'Show my connection details'}</button>
            </div>
          ) : (
            <>
              <ConnectionDetails server={server} token={token} />
              <p className="muted small-print">These details contain your personal token for <b>{server.name}</b>: don&apos;t share them.
                The token works on this server only, and you can come back here to copy it again.{' '}
                {saved?.lastUsed ? <>Last used {ago(saved.lastUsed)}. </> : null}
                If Claude Code says <code>{name}</code> already exists, run <code>claude mcp remove {name}</code> first.
                Shared it by mistake? <button type="button" className="link" onClick={() => make(true)} disabled={busy}>Make a new token</button>.
              </p>
            </>
          )}
          {error && <p className="error">{error}</p>}
        </li>
        <li>
          <b>Ask it to build.</b> Join your server in Minecraft and ask your assistant something
          like: <q>Build a small oak cabin with a garden next to me.</q> You&apos;ll see each edit appear on this page,
          and you can undo any of them.
        </li>
      </ol>
      <details className="more">
        <summary>Using build scripts?</summary>
        <p>Scripts send the same token as <code>Authorization: Bearer …</code>.
          The Python SDK and the repository&apos;s <code>mcp.json</code> read these two variables:</p>
        <pre>export AIBUILDER_URL={url} AIBUILDER_TOKEN={token || '<your token>'}</pre>
      </details>
    </div>
  );
}
