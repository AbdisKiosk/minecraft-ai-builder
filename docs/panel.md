# Panel

`panel/` is the web panel: a hosted, multi-user Next.js app. People sign up, add their Minecraft servers, invite others
and get an access token for each server to connect Claude Code. Each server's AIBuilder plugin connects **out** to the panel over a WebSocket
and never opens a port on the Minecraft server: the panel is the only way to reach it. The panel serves that server's
MCP and REST endpoints under `/s/<server>/`, shows live job progress, and keeps a git-like history of every edit with before/after pictures and
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

Outside the image, the plugin download serves the newest jar in `plugin/core/build/libs/` (run `./gradlew build` in
`plugin/` first), or in `PLUGIN_JAR_DIR` when that is set; without a jar it answers 404.

## Deploy

`panel/Dockerfile` builds the image. Its build context is the repository root, because it also builds the plugin jar
from `plugin/` for the panel to offer as a download (in Coolify: base directory `/`, Dockerfile location
`/panel/Dockerfile`):

```bash
docker build -f panel/Dockerfile --build-arg GIT_COMMIT=$(git rev-parse --short HEAD) -t aibuilder-panel .
docker run --env-file panel.env -p 3000:3000 aibuilder-panel
```

Set the variables from `.env.example`, with `APP_BASE_URL` the public `https://` address; the plugins then use the
matching `wss://` address. `GIT_COMMIT` (or `SOURCE_COMMIT`, which Coolify passes) is the commit in the plugin jar's
name; without it the downloads are named without one. The container listens on port 3000 and answers `GET /api/health`. Put TLS in front of it:
tokens and the session cookie travel in every request.

## Connect a Minecraft server

On the dashboard, name the server and press **Add server & download plugin**. The panel makes the server and downloads
`AIBuilder-<server>-<commit>.jar` with the server's relay settings inside (`aibuilder-setup.properties`, added by
`lib/server/jarSetup.ts`). Put it in the server's `plugins` folder next to WorldEdit and restart: on start the plugin
writes those settings into `plugins/AIBuilder/config.yml` (`JarSetup`) and connects. The setup page waits for that
connection live and then shows how to connect Claude Code.

The panel does not store relay tokens, only their hash and a seed: a token is `HMAC(RELAY_SECRET or AUTH0_SECRET, seed)`
(`lib/server/relayToken.ts`), so the same jar can be downloaded again from the server's settings and keeps working.
**Disconnect and make a new key** gives the server a new seed: the old jar stops working until a fresh download is
installed. Changing `AUTH0_SECRET` (without a `RELAY_SECRET`) has the same effect on the next download of each server.

The plugin applies the jar's settings once; a marker file (`plugins/AIBuilder/.jar-setup`) remembers which. Edits to
`config.yml` after that win, until a jar with different settings is installed. To set a server up by hand, take the
plain jar from `/download/AIBuilder.jar` and the settings from **Restarted, but it doesn't connect?** on the setup page:

```yaml
relay:
  url: "wss://panel.example.com"
  token: "aibs_..."
```

Run `/aibuilder` in the console to see the relay status. If the panel is unreachable, the plugin keeps retrying,
backing off up to one minute. Edits that are running keep going while it is disconnected.

For a dev server (`./gradlew :core:runServer` in `plugin/`), run the panel locally too (`npm run dev`), add the server
there and put the downloaded jar's settings, or the jar itself, in `plugin/core/run/<version>/plugins/`; the relay URL is
`ws://127.0.0.1:3000`. Claude Code, `tools/mcp_cli.py` and the SDK then use `AIBUILDER_URL=http://127.0.0.1:3000/s/<server>`.

## Connect Claude Code

Each user has one access token (`aibc_`) per server, and it opens that server only. **Connect Claude** (on the setup
page and the server page) makes it the first time and shows one command with it inside:

```bash
claude mcp add --transport http --scope user minecraft-<server name> https://panel.example.com/s/<server>/mcp --header "Authorization: Bearer aibc_..."
```

Like relay tokens, access tokens are derived from a stored seed (`lib/server/accessTokens.ts`), so the command can be
shown again: Connect Claude shows the same one every time, and **Access tokens** lists a user's tokens with **Show
command** and **Revoke**. **Make a new token** replaces it; the old one stops working. A token goes when its user leaves
the server, and is refused there while their role is below builder.

In this repository, `mcp.json` works for one server as it is:

```bash
export AIBUILDER_URL=https://panel.example.com/s/<server> AIBUILDER_TOKEN=aibc_...
```

For several servers in one session, add one entry per server, each with that server's token:

```json
{
  "mcpServers": {
    "survival": { "type": "http", "url": "https://panel.example.com/s/<server id>/mcp",
                  "headers": { "Authorization": "Bearer ${SURVIVAL_TOKEN}" } },
    "creative": { "type": "http", "url": "https://panel.example.com/s/<other server id>/mcp",
                  "headers": { "Authorization": "Bearer ${CREATIVE_TOKEN}" } }
  }
}
```

The Python SDK works the same way, through `AIBUILDER_URL=https://panel.example.com/s/<server>` and that server's
`AIBUILDER_TOKEN`, so build scripts can run against several servers in parallel.

## Roles

| Role | Can |
| --- | --- |
| owner | everything, including downloading the server's plugin, giving it a new key and deleting it |
| admin | approve and reject drafts, revert edits, rename, invite and manage builders and viewers |
| builder | build through Claude Code and scripts with their access token for the server, promote regions |
| viewer | see jobs, history and drafts |

Invite links are single use and last seven days. Owners invite admins, builders and viewers; admins invite builders
and viewers. A member can be changed or removed only by someone ranked above them, and anyone but the owner can leave.

An access token (`aibc_`) builds on one server as its user, so treat it like an SSH key. A server token (`aibs_`) only
lets a server connect as itself.

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

## Map

**Map** on a server's page shows a top-down map of each of its worlds: drag to move, scroll or pinch to zoom, from
one pixel per 64 blocks up to 24 pixels per block. Spawn, online players and `/aibuilder mark` markers are on it.

The plugin draws it from the world's region files on disk (`region/r.<x>.<z>.mca`, every format from 1.8 to 26.x), on
its own low-priority threads, so viewing the map never loads a chunk into the server and never touches the main thread
for unedited chunks. One region (512x512 blocks) takes about 50 ms. A zoom-0 tile is one region; each zoom level
halves four tiles into one, up to 64x64 regions per tile. The nether is drawn below its bedrock roof.
Since 26.1 worlds keep their chunks in `dimensions/<namespace>/<name>/region`; when an upgraded world still has its
old folder too, the map reads whichever the server wrote to last. The side panel shows which folder that is.

Tiles are versioned by their region file's time stamp. The plugin caches them in `plugins/AIBuilder/map/` and draws a
tile again only when its region changes; the panel keeps them in memory (`MAP_CACHE_MB`, default 256) and the browser
caches each version for good, so a tile crosses the relay once per change. Region files lag behind the world until the
server saves, so chunks an edit job changed are drawn from a snapshot of the loaded chunk until it unloads: Claude's
builds show at once, players' changes after the next autosave. While a server is offline the panel shows the last map
it saw.

**Mark area** (builders): click two opposite corners on the map (drag between the clicks to move it) or drag across the area, then name it and say what should go there. Claude sees areas
in `get_context` with their notes and can use `"@name"` wherever a region goes. The height runs from the lowest ground
in the area to 32 blocks above the highest, unless you give one. Areas live in the plugin's `areas.json` and share
their names with markers.

Settings under `map:` in the plugin's `config.yml`: `enabled` (default true) and `threads` (default 2).

## Lessons

A lesson is a short rule (at most 160 characters) that Claude reads in `get_context` at the start of every session
on a server. Lessons apply as soon as they are saved; there is no approval step.

- After an admin rejects a draft or reverts an edit on the panel, the panel asks "Do you want to give feedback on this
  build?". The text (at most 300 characters) reaches Claude once, in `get_context` as `feedback`, with the draft or
  entry id. With **Remember this for future builds** (ticked by default) it is also saved as a lesson. Skip asks
  nothing more; rejects and reverts in the game or by Claude never ask.
- **Changed since** on a history entry shows what people changed in the blocks it placed since (substitutions,
  removals and changed block states, with pictures and the 3D view on request), while the server is online and still
  keeps the entry. Admins and the owner can save it as a lesson linked to the entry, starting from a sentence made
  from the largest change.
- In the background, when a Claude Code session starts (at most every ten minutes), the plugin checks up to five of
  Claude's builds from the last seven days that were not checked in the last day, one at a time, and stops when the
  server is short of memory. Claude hears of what people changed once, in `get_context` as `corrections`.
- Claude saves its own lessons with the `lesson` tool, which is not in the tool list Claude gets every session. It
  may only save one from a correction or from feedback it was given: `entry` names that history entry or draft.
- **Lessons** on a server's page lists them with where they came from (feedback, Claude or written there), who wrote
  them and the build they came from. Every member sees them, and lessons nobody has seen yet are marked new until
  someone opens the page. Admins and the owner add, edit and delete them.
- New tracked builds keep a compressed after-snapshot locally, including a one-block shell when it fits under
  `history.max-volume`. **Changed since** also finds blocks added into originally empty cells directly touching
  the build. Later recorded edits and natural changes are excluded. These are candidates, not proof of intent;
  review them before making a rule. Older builds without snapshots still compare their original changed cells.
  Snapshot bytes count toward `history.max-bytes` and are deleted when their entry is pruned.
- **Correction rate** on the server page charts up to 200 retained tracked Claude builds, oldest to newest.
  Unchecked builds are distinct from checked zeroes. The overall rate is weighted by the number of original
  changed cells; nearby additions are separate and cannot push the rate above 100%. Viewing the chart reads
  stored measurements only, never world chunks. The server must be online; archive metadata is not a live check.
- A lesson naming both block types in a source substitution shows **May not be working** when that substitution
  recurs in two distinct later checked Claude builds in the same world. Builds must have started after the lesson
  was saved or edited; marking it seen does not reset this period. The warning links to up to five builds for
  review, never edits or deletes a lesson automatically, and cannot assess missing source entries or arbitrary prose.
- A server keeps at most 12. A new lesson then replaces the oldest one Claude wrote; when people wrote every one,
  Claude's is refused and a person's replaces the oldest.

Lessons and pending feedback live in the plugin's `lessons.json`, beside `areas.json`. A plugin from before lessons
answers the Lessons page with "too old"; update it with the jar from the panel.

## Assets

**Assets** (top bar) is a user's own library of saved pieces: a window, a pillar, a tree. Assets belong to the account,
not to a server, so they can be placed on every server where the user is a builder.

- **Save as asset** on a server's map (builders): click two corners, then give a name (a-z, 0-9, `_`, `-`, as for
  saved blueprints), a description, tags and the height. The height starts from the area the box lies in, if any. The
  panel reads the blocks through the same `draft export` frame as promote, so `promote.max-volume` limits it, and it
  saves the world as it is, not an open draft. A region that is only air is refused.
- The Assets page shows each asset with its size, block count and the Minecraft version it was saved on, a 3D view in
  the asset's own coordinates, and Edit, Delete and **Place…**, which puts it into a draft on a chosen server, world
  and corner through the `draft import` frame (as promote does). Nothing is placed until that draft is approved.
- Other Minecraft versions: **Place…** warns when the asset was saved on a newer version than the chosen server
  reports, or when either version is unknown (`lib/mcVersion.ts`). The warning is advisory; the import still refuses
  block states the server does not have unless **leave out blocks that server does not have** is ticked, and then the
  dialog lists the states it left out (the first 20 and a count) before opening the draft. Those cells keep what is in
  the world. Only states the server does not know are caught: nothing is renamed or remapped, and a state that exists
  on both versions but means something else on the older one is placed as it is.
- **Upload .schem** on the Assets page (any signed-in user, no server role): a WorldEdit `.schem` of at most 8 MB, with
  a name (prefilled from the file name), description and tags as above. See "Upload a .schem" below.
- Limits per user: 200 assets and 256 MB (`lib/server/assets.ts`). Each asset is at most 2,000,000 blocks and 5 MB
  of compressed region data, whether saved on the panel or by Claude. Nobody can see, change or place another user's
  asset; saving and placing need the builder role on the server, as promote does.
- Storage: one MySQL row per asset (`assets`), and in R2 under `users/<user>/assets/<id>/` the export frame's data
  (`region.bin`, which the import frame takes back) and the 3D view's blocks (`blocks.json.gz`). The export leaves out
  properties that equal a block's default, so the browser fills them in from the client jar's blockstates for the 3D
  view; a wrong guess changes only how the preview draws a block, never what is placed. Migration
  `006_asset_usage.sql` adds `last_used_at`, which orders the list Claude sees.

### Upload a .schem

`POST /api/assets/upload` takes a multipart form with `file`, `name`, `description` and `tags` (comma-separated) and
answers 201 with the asset. The file is parsed on the panel (`lib/server/schem.ts`) and saved through the same code as
an asset saved from a server, so quota, name, ownership and the 3D view work the same and the asset can be placed or
pasted by Claude like any other.

- Supported: Sponge schematic versions 2 and 3, which WorldEdit 7 writes. Refused, each with its own message: MCEdit
  `.schematic`, Sponge version 1, Litematica, and anything that is not gzipped NBT.
- Only block states are kept; block entities (sign text, chest contents), entities and biomes are dropped. The
  schematic's `Offset` is ignored: the asset starts at 0 0 0, in the same orientation as a saved region. States lose the
  `minecraft:` namespace; other namespaces stay as written; `cave_air` and `void_air` become `air`.
- The Minecraft version comes from `DataVersion` through a table of releases (1.13 to 1.21.10, and 26.3). Any other
  data version is stored as unknown, so the 3D view is not shown for it.
- Bounds, since the file comes from a browser: 8 MB uploaded (by `Content-Length` before reading, while reading and
  after), 64 MB unpacked, the asset limits (2,000,000 blocks, 5 MB of region data), 65,536 palette entries, 256
  characters per state, NBT nested at most 64 deep. Lengths in the file are checked against the bytes left before
  anything is read from them. A palette index the palette lacks, block data shorter or longer than the region, and
  data after the end are refused. Next.js buffers request bodies through the proxy up to 10 MB, above the 8 MB bound.

### Assets in Claude's calls

Every MCP and REST call through `/s/<server>/` carries the account of its access token, so Claude and SDK scripts use
that account's library on every server where it is a builder.

- `get_context` lists the caller's 30 most recently used assets: name, size and description cut to 80 characters.
  If the library cannot be reached, it says so in `assets_error` and the rest of the context is still returned.
- `paste` with `name=` looks in the server's saved blueprints first, then in the caller's library.
- A library asset may hold block states this server does not have (it was saved on a newer version). `paste` leaves
  those cells as they are in the world and places the rest; the result names them in `skipped` (a missing block by
  its name, a bad property value by the whole state; at most 10, sorted, with `skipped_total` when there are more).
  An asset with nothing but such states and air is refused. Blueprints Claude writes, saved blueprints and files are
  still refused as a whole on an unknown state. As in the panel, nothing is remapped.
- `save_blueprint` (with an optional `description`) saves to the caller's library only, never on the server. A save
  that fails, for example on quota or a taken name, is not saved on the server instead.
- Ownership: for each tool call the panel gives the plugin a random grant that ends with the call (at most 6 minutes).
  The plugin's asset requests carry the grant, and the panel takes the owner from it and checks the builder role
  again; a user id sent by the plugin is never trusted.
- Saves go up the relay as base64. A paste fetches a download URL from R2 that is valid for 60 seconds; the plugin
  checks the file's SHA-256. There is no per-user download rate limit.
- Cache: `plugins/AIBuilder/asset-cache/<user>/`, at most 128 MB per server in all (oldest files go first), not
  configurable. Every paste asks the panel again before using a cached file, so a deleted asset cannot be pasted.
- Without R2 there is no library: the relay `welcome` frame carries `assets: false` and calls carry no grant. Calls
  then work as they do without the panel: `save_blueprint` saves on the server, `paste` with `name=` looks only in the
  server's saved blueprints, and `get_context` has neither `assets` nor `assets_error`. A library that exists but
  fails (quota, taken name, storage error, expired grant) still fails the call and is never replaced by a server save.
- Compatibility: the new frame fields are additive and the protocol version is unchanged. A new plugin with an older
  panel gets no account, so it behaves as before (server blueprints only). An older plugin with a new panel ignores
  the grant and never uses the library.

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
