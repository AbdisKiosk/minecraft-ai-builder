# Panel

`panel/` is the web panel: a hosted, multi-user Next.js app. People sign up, add their Minecraft servers, invite others
and make access tokens for Claude Code. Each server's AIBuilder plugin connects **out** to the panel over a WebSocket,
so no port is opened on the Minecraft server. The panel then serves that server's MCP and REST endpoints under
`/s/<server>/`, shows live job progress, and keeps a git-like history of every edit with before/after pictures and
revert.

```
Claude Code / SDK ──HTTPS──▶ panel  /s/<server>/mcp, /s/<server>/api/<tool>   (access token aibc_…)
Browser           ──HTTPS──▶ panel  pages, /api/…, /api/events               (Auth0 session)
Paper server      ──wss────▶ panel  /relay                                   (server token aibs_…)
                              │
                              ├── MySQL: users, servers, memberships, invites, access tokens, history index
                              └── R2: archived history, Minecraft client jars
```

Next.js alone cannot hold WebSockets, so `server.ts` runs Next.js inside a plain Node HTTP server and takes `/relay`
and `/s/` itself. Next attaches its own upgrade listener for hot reload; `server.ts` must not hand upgrades to it too.

## Run it locally

```bash
cd panel
cp .env.example .env.local   # then fill it in
npm install
npm run dev                  # http://localhost:3000
```

The Auth0 application is a Regular Web Application with `http://localhost:3000/auth/callback` as a callback URL and
`http://localhost:3000` as a logout URL. Database migrations in `migrations/` run when the app starts; add a new file
for every change and never edit one that has run. All times are UTC: each connection sets its time zone, whatever the
MySQL server's own is.

`npm run typecheck` checks the TypeScript and `npm test` runs the unit tests (Node 22.18+).

## Deploy

`panel/Dockerfile` builds the image; the build context is the `panel` directory (in Coolify, set `panel` as the base
directory):

```bash
docker build -t aibuilder-panel panel
docker run --env-file panel.env -p 3000:3000 aibuilder-panel
```

Set the variables from `.env.example`, with `APP_BASE_URL` the public `https://` address; the plugins then use the
matching `wss://` address. The container listens on port 3000 and answers `GET /api/health`. Put TLS in front of it:
tokens and the session cookie travel in every request.

## Connect a Minecraft server

Add a server on the dashboard. The panel shows its `relay` settings once; put them in the server's
`plugins/AIBuilder/config.yml` and restart it:

```yaml
http:
  enabled: false        # optional: no local port at all
relay:
  url: "wss://panel.example.com"
  token: "aibs_..."
```

Run `/aibuilder` in the console to see the relay status. If the panel is unreachable, the plugin keeps retrying,
backing off up to one minute. Edits that are running keep going while it is disconnected. The server's owner can make
a new relay token on the server page; the old one stops working.

For a dev server (`./gradlew :core:runServer` in `plugin/`), the file is
`plugin/core/run/<version>/plugins/AIBuilder/config.yml`, with `url: "ws://127.0.0.1:3000"`.

## Connect Claude Code

Make an access token under **Access tokens**. For one server, the repo's `mcp.json` works as it is:

```bash
export AIBUILDER_URL=https://panel.example.com/s/<server> AIBUILDER_TOKEN=aibc_...
```

For several servers in one session, add one entry per server (all use the same access token):

```json
{
  "mcpServers": {
    "survival": { "type": "http", "url": "https://panel.example.com/s/<server id>/mcp",
                  "headers": { "Authorization": "Bearer ${AIBUILDER_TOKEN}" } },
    "creative": { "type": "http", "url": "https://panel.example.com/s/<other server id>/mcp",
                  "headers": { "Authorization": "Bearer ${AIBUILDER_TOKEN}" } }
  }
}
```

The Python SDK works the same way, through `AIBUILDER_URL=https://panel.example.com/s/<server>`, so build scripts can
run against several servers in parallel.

## Roles

| Role | Can |
| --- | --- |
| owner | everything, including deleting the server and making a new relay token |
| admin | approve and reject drafts, revert edits, rename, invite and manage builders and viewers |
| builder | build through Claude Code and scripts with an access token, promote regions |
| viewer | see jobs, history and drafts |

Invite links are single use and last seven days. Owners invite admins, builders and viewers; admins invite builders
and viewers. A member can be changed or removed only by someone ranked above them, and anyone but the owner can leave.

An access token (`aibc_`) builds on every server where its user is builder or above, so treat it like an SSH key. A
server token (`aibs_`) only lets a server connect as itself.

## History

Every edit job reads the blocks it will touch before it starts and again when it finishes, and stores the
difference in `plugins/AIBuilder/history/`. For each entry, the panel shows:

- a diffstat: blocks added, removed and changed, by block type
- before, after and changes pictures (isometric, top-down or one face)
- the changed blocks with their coordinates
- **Revert**: puts the old blocks back as a new job. Blocks that something else changed after the edit are left
  alone and listed as conflicts, unless you tick "also overwrite later changes". Reverting does not use WorldEdit's
  undo history, so it still works after a restart.

The Pictures card on history entries and drafts also offers **3D (interactive)** on wider screens. It draws real
Minecraft block models and textures, with Before / After / Changes modes, coloured change overlays, an only-changed
filter, a cut-height slider and click-to-inspect coordinates. PNG views remain the default. The 3D view needs WebGL 2
and the server's reported Minecraft version. The renderer rebuilds the scene and re-parses resources for each control
change, so large regions can redraw slowly.

**Distribution review:** SchematicWebViewer 5.0.1 is MIT, but its bundled SchematicJS 0.10.0 dependency declares
GPL-3.0. Resolve the resulting distribution/source and third-party notice requirements before shipping this viewer;
the viewer's MIT label alone does not establish that the whole dependency chain is permissively licensed.

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
- Calls to the `undo` tool are not recorded (with review on, undo only changes the draft).
- Two edits running at the same time over the same blocks each see the other's changes.

### History in R2

When R2 is configured, the panel answers a plugin's `hello` with `{"t":"welcome","archive":true}`, and the plugin then
archives every history entry (see `RelayProtocol` for the frames):

1. When a job is recorded, the plugin writes, beside the diff, the 3D view's blocks and the default pictures (isometric,
   from the south-east), made from the job's own snapshot, so this costs no extra world read.
2. It asks for upload URLs, PUTs the files straight to R2 (it never holds R2 keys), then sends the entry's detail.
   The panel checks the files are there, stores the detail in MySQL and drops the oldest entries over 5,000 or 2 GB per
   server. The plugin then deletes its copies of the blocks and pictures, and keeps the diff for revert.
3. Entries recorded before the server was connected, or while it was disconnected, are uploaded on the next connection,
   with their diff only.

The dashboard then lists history and shows each entry, its default pictures and its 3D view from the archive, also
while the server is offline. Other picture views still come from the live server. Reverting needs the server online;
if the server no longer keeps the entry, the panel sends the archived entry and diff with the request.

Deleting a server deletes its rows and everything under `servers/<id>/` in R2.

### Minecraft client jars

The 3D view takes block models and textures from the Minecraft client jar for the server's version. The panel
downloads each version from Mojang once, checks it against Mojang's SHA-1 and keeps it in R2 under
`minecraft/client/`; it serves it only to signed-in users, and no Minecraft assets are bundled with AIBuilder. The
browser also caches the jar (roughly 30 MB, depending on the version). Servers before 1.13 use the 1.13.2 client's
models for their translated block states.

## Drafts: approve edits before they are placed

Turn on review in a server's `plugins/AIBuilder/config.yml` to keep Claude's edits out of the world until someone
approves them:

```yaml
review:
  enabled: true
```

With review on:

- Every edit tool (`fill`, `batch`, `place_blueprint`, `clone`, terrain ops, …) runs through WorldEdit in a dry
  run. Nothing reaches the world: the blocks go into the world's open **draft**, and the result says
  `"placed": false` with the draft id. Other plugins (loggers, region guards) never see the dry run.
- Claude's reads (`read_region`, `check_region`, `render_region`, …) show the world as the draft would leave it, so
  its build-and-check workflow is unchanged. Players see the real world.
- `undo` takes the newest edit out of the draft. `decorate` is refused until the draft is approved, because signs,
  containers and entities would skip the draft.
- The panel shows a "Draft waiting for approval" banner on the server, and a draft page with before/after/changes
  pictures, every edit in it, and the changed blocks. **Approve and place** puts exactly the previewed blocks in the
  world as one job (recorded in the history, so it can be reverted). Blocks changed in the world since the draft was
  made are left alone and listed, unless you tick "also overwrite blocks changed since". **Reject** drops the draft.
- From the server console or in game (permission `aibuilder.approve`): `/aibuilder draft` lists open drafts,
  `/aibuilder draft approve [world] [force]` and `/aibuilder draft reject [world]` decide one.
- Drafts are saved in `plugins/AIBuilder/drafts/` and survive restarts. They stay on the server and are not archived:
  an open draft is working state the plugin's dry runs read from.

| Setting | What it does |
| --- | --- |
| `review.enabled` | Claude's edits go into drafts. It is only in `config.yml`, so it cannot be switched off from the panel |
| `review.max-volume` | Most changed blocks one draft may hold |
| `review.keep` | Approved and rejected drafts kept for the panel |

Drafts need WorldEdit. With FastAsyncWorldEdit, which writes around WorldEdit's world API, every edit is refused
while review is on rather than risk placing it.

## Promote: copy a region between servers

**Copy region to…** on a server page, or **Promote to…** on a history entry or draft, copies a region's blocks from
one server into a draft on another (or the same) server: for example a hole built on a staging server, promoted to the
live one. Nothing is placed on the target until its draft is approved, whether or not review is on there.

- Choose the target server and world, the region, and the target corner (it defaults to the same coordinates).
- **Copy air too** clears what is in the way; untick it to only add blocks.
- Blocks the target server does not have (for example 1.21 blocks going to a 1.12 server) stop the promote with a list,
  or are left out when **leave out blocks the target server does not have** is ticked.
- Only block states are copied: sign text, chest contents and entities are not.
- `promote.max-volume` (default 2,000,000 blocks) limits the region on both servers.
