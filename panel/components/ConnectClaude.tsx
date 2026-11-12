import { useState } from 'react';
import { api, message } from '../lib/api';
import { tokensHref } from '../lib/routes';
import type { Server } from '../lib/types';
import { CopyButton } from './shared';

/** A name for the server in Claude Code: letters, digits and dashes, e.g. minecraft-survival-smp. */
export function mcpName(server: { id: string; name: string }): string {
  const slug = server.name.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40);
  if (!slug) return 'minecraft-' + server.id;
  return slug.startsWith('minecraft') ? slug : 'minecraft-' + slug;
}

/** The steps from nothing to Claude Code building on this server, with a command that has the user's key in it. */
export default function ConnectClaude({ server }: { server: Server }) {
  const [token, setToken] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const url = `${window.location.origin}/s/${server.id}`;
  const name = mcpName(server);

  const create = async () => {
    setBusy(true);
    setError('');
    try {
      const label = `Claude Code · ${server.name}`.slice(0, 64);
      setToken((await api<{ token: string }>('/api/tokens', { method: 'POST', body: JSON.stringify({ label }) })).token);
    } catch (ex) {
      setError(message(ex));
    } finally {
      setBusy(false);
    }
  };

  const command = `claude mcp add --transport http --scope user ${name} ${url}/mcp --header "Authorization: Bearer ${token}"`;
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
          {!token ? (
            <div className="toolbar step-action">
              <button onClick={create} disabled={busy}>{busy ? 'Making your command…' : 'Show my command'}</button>
            </div>
          ) : (
            <>
              <div className="command"><pre>{command}</pre><CopyButton text={command} label="Copy command" /></div>
              <p className="muted small-print">This command contains your personal key: don&apos;t share it. It&apos;s shown only now;
                you can make another any time, and turn old ones off under <a href={tokensHref}>Access tokens</a>.
                If Claude Code says <code>{name}</code> already exists, run <code>claude mcp remove {name}</code> first.</p>
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
        <p>The server&apos;s MCP address is <code>{url}/mcp</code>; send your access token as <code>Authorization: Bearer …</code>.
          The Python SDK and the repository&apos;s <code>mcp.json</code> read these two variables:</p>
        <pre>export AIBUILDER_URL={url} AIBUILDER_TOKEN=&lt;your token&gt;</pre>
      </details>
    </div>
  );
}
