# Web panel

The panel lets Claude Code build on several Minecraft servers at once without opening a port on any of them. Each
server's AIBuilder plugin connects **out** to the panel over a WebSocket. The panel then serves that server's MCP and
REST endpoints under `/s/<server>/`, shows live job progress, and keeps a git-like history of every edit with
before/after pictures and revert.

```
Claude Code / SDK ──HTTPS──▶ Caddy (TLS) ──▶ panel 127.0.0.1:8080   /s/<id>/mcp, /s/<id>/api/<tool>, web UI
                                                   ▲
Paper server(s) ──wss, outbound, reconnecting──────┘   /relay
```

## Build

```bash
./gradlew build
```

This produces `panel/build/libs/AIBuilderPanel-<version>.jar` next to the plugin jar. The panel needs Java 17+.

## Set up on the VPS

Copy the jar to `/opt/aibuilder-panel/` and run the commands below there. They read and write `panel.json` in the
current directory (pass `--config <file>` to use another file). A running panel picks up changes within a few seconds.

```bash
java -jar AIBuilderPanel.jar set-password            # web UI login
java -jar AIBuilderPanel.jar add-server survival "Survival SMP"
java -jar AIBuilderPanel.jar add-server creative
java -jar AIBuilderPanel.jar add-client laptop       # token for Claude Code and scripts
java -jar AIBuilderPanel.jar list
```

`add-server` and `add-client` print each token once. Only its SHA-256 hash is stored. Running `add-server` again for
the same id replaces that server's token.

### Run it as a service

`/etc/systemd/system/aibuilder-panel.service`:

```ini
[Unit]
Description=AIBuilder panel
After=network.target

[Service]
User=aibuilder
WorkingDirectory=/opt/aibuilder-panel
ExecStart=/usr/bin/java -jar AIBuilderPanel.jar
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

### TLS with Caddy

The panel listens on `127.0.0.1:8080` (`bind`/`port` in `panel.json`). Put Caddy in front of it, which gets HTTPS
certificates by itself and passes WebSockets through:

```
panel.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Do not expose the panel without TLS. Tokens and the login cookie travel in every request.

## Connect a Minecraft server

In `plugins/AIBuilder/config.yml`, paste what `add-server` printed, then restart the server:

```yaml
http:
  enabled: false        # optional: no local port at all
relay:
  url: "wss://panel.example.com"
  token: "aibs_..."
```

Run `/aibuilder` in the console to see the relay status. If the panel is unreachable, the plugin keeps retrying,
backing off up to one minute. Edits that are running keep going while it is disconnected.

## Connect Claude Code

For one server, the repo's `mcp.json` works as it is:

```bash
export AIBUILDER_URL=https://panel.example.com/s/survival AIBUILDER_TOKEN=aibc_...
```

For several servers in one session, add one entry per server (all use the same client token):

```json
{
  "mcpServers": {
    "survival": { "type": "http", "url": "https://panel.example.com/s/survival/mcp",
                  "headers": { "Authorization": "Bearer ${AIBUILDER_TOKEN}" } },
    "creative": { "type": "http", "url": "https://panel.example.com/s/creative/mcp",
                  "headers": { "Authorization": "Bearer ${AIBUILDER_TOKEN}" } }
  }
}
```

The Python SDK works the same way, through `AIBUILDER_URL=https://panel.example.com/s/<id>`, so build scripts can
run against several servers in parallel.

## History

Every edit job reads the blocks it will touch before it starts and again when it finishes, and stores the
difference in `plugins/AIBuilder/history/`. For each entry, the panel shows:

- a diffstat: blocks added, removed and changed, by block type
- before, after and changes pictures (isometric, top-down or one face), drawn from the world as it is now
- the changed blocks with their coordinates
- **Revert**: puts the old blocks back as a new job. Blocks that something else changed after the edit are left
  alone and listed as conflicts, unless you tick "also overwrite later changes". Reverting does not use WorldEdit's
  undo history, so it still works after a restart.

Settings live under `history:` in the plugin's `config.yml`:

| Setting | What it does |
| --- | --- |
| `enabled` | Turns history on or off |
| `max-volume` | Edits larger than this are logged without a diff |
| `keep`, `max-mb` | Limits on the number of entries and their size; the oldest entries go first |
| `reads-per-tick` | How many blocks a snapshot reads per tick, so large edits don't lag the server |
| `max-render-volume` | Largest region the panel will draw for one entry |

Limits:

- Only block states are recorded. Sign text, chest contents, item frames and armor stands (`decorate`) are not.
- Calls to the `undo` tool are not recorded.
- Two edits running at the same time over the same blocks each see the other's changes.

## Security

- Server tokens (`aibs_`) only let a server register itself. Client tokens (`aibc_`) can build on every server, so
  treat them like SSH keys. Revoke one with `remove-client <label>`.
- The web UI uses a password (PBKDF2), an `HttpOnly`, `SameSite=Strict` session cookie, an origin check on writes,
  and a limit of 10 failed logins per minute per address.
- Sessions are kept in memory, so restarting the panel logs everyone out.
