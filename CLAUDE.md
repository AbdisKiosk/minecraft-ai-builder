# minecraft-ai-builder

The Paper plugin (`plugin/`) exposes WorldEdit to Claude Code over MCP and to scripts over REST. Both use the same tools
(`plugin/core/.../tools/Tools.java`). The plugin hosts no server and must never open a port
(`NoOpenPortTest`): it connects out to the panel (`panel/`, see `docs/panel.md`), a hosted, multi-user web app that
serves every server's endpoints under `/s/<server>/` (`POST /s/<server>/mcp`, `POST /s/<server>/api/<tool>`) and passes
the requests down that connection.

## Layout
`plugin/` is a self-contained Gradle build (run `./gradlew` there) with four modules:
- `plugin/common/`: pure Java. Op model, blueprint format (and `StateFlip` for mirrored copies), region summary/encoding, region lint (`RegionLint`, `BlockTraits`), generators that save Claude from listing blocks (`Roofs`, `Lighting`, `Halves`, `Terrain`), PNG renderer, `WorldEditBridge` contract, edit history diffs (`RegionSnapshot`, `BlockDiff`), drafts (`Draft`, `DryRunStore`, `PreviewHandle`), region transfer (`StateArrays`), the map's region-file reader (`map/`: `RegionFile`, `Nbt`, `ChunkColumns`, `MapRaster`) and the relay frame names (`RelayProtocol`), which the plugin shares with the panel's `hub.ts`. Unit tested.
- `plugin/bridge-we7/`: WorldEdit 7.2+ / FAWE (MC 1.13 → 26.x). Compiled against WE 7.2.20 + spigot-api 1.13.2.
  `DryWorld` (in both bridges) is a proxy `World` that records writes for drafts; dry-run sessions use a private event bus.
  `WorldGuardGuard` (in both bridges, WorldGuard 7 / 6.1+) implements `common`'s `RegionGuard`: get_context lists WorldGuard
  regions and "@name" resolves them. With `worldguard.protect` on, `BuildService` also refuses edits where WorldGuard would stop
  the acting player from building, asking WorldGuard once per cell `GuardScan` cuts the box into.
- `plugin/bridge-we6/`: WorldEdit 6.1 (MC 1.8 → 1.12). Modern block states are mapped to legacy id:data via `LegacyBlockTable`, loaded from
  `legacy-blocks.txt`, which `tools/gen_legacy_blocks.py` generates from WorldEdit 7's `legacy.json`. Re-run it only to change aliases, hints or defaults.
- `plugin/core/`: the plugin itself. MCP endpoint (JSON-RPC handling only, no listener), tools, `BuildService` (threading, limits, jobs, progress events), `History` (per-job block diffs, render, revert), `Drafts` (review mode, approve/reject, promote export/import), `Lessons` (lessons.json: lessons Claude reads in get_context, feedback from the panel), `map/WorldMap` (the panel's map tiles, drawn from region files on disk, and its marked areas), `AssetLibrary` (the caller's panel assets for `paste` by name and `save_blueprint`, with a disk cache), `relay/RelayClient` (outbound WebSocket to the panel and the plugin's only transport, JDK only; its test runs against `FakePanel`). Compiled against spigot-api 1.8.8.

Beside it:
- `panel/`: the web panel (Next.js, TypeScript). One Node process (`server.ts`) serves the landing page, the dashboard, the API routes, the `/s/<id>/` proxy and the plugins' `/relay` WebSocket. Auth0 accounts, servers, memberships, invites and access tokens in MySQL through raw SQL (no ORM; `migrations/*.sql` run on startup), archived history, Minecraft client jars and personal assets in Cloudflare R2. Server code is in `lib/server/`; `assetRelay.ts` answers the plugin's `asset` frames, with the owner taken from a per-call grant; `hub.ts` must stay in step with `RelayProtocol`. `panel/Dockerfile` builds its image, from the repository root.
- `sdk/python/aibuilder/`: stdlib-only REST client for build scripts in `builds/`.

Everything in `plugin/` targets Java 17 bytecode (`--release 17`). The plugin core must never import WorldEdit classes;
bridges are loaded reflectively in `AIBuilderPlugin#loadBridge`.

## Commands
- `cd plugin && ./gradlew build`: tests + the shaded jar at `plugin/core/build/libs/AIBuilder-<version>+<commit>.jar`
- `cd plugin && ./gradlew :core:runServer` (1.21.11 + WE 7.3.19), `:core:runLatest` (26.3 + WE 7.4.6), `:core:runLegacy` (1.8.8, add WE 6.1.9 yourself). Run directories are `plugin/core/run/<version>/`. A dev server is reachable only through a panel: run `npm run dev` too and set `relay.url: "ws://127.0.0.1:3000"` and the server's `relay.token` in its `plugins/AIBuilder/config.yml`.
- `cd panel && npm run dev`: the panel on :3000 with hot reload (needs `panel/.env.local`, see `.env.example`). `npm test`, `npm run typecheck`, `npm run build`; `server.ts` itself does not hot reload.
- `docker build -f panel/Dockerfile --build-arg GIT_COMMIT=$(git rev-parse --short HEAD) -t aibuilder-panel .`: the panel's image (port 3000, configured with the variables in `panel/.env.example`). It builds the plugin jar too, which the panel serves at `/download/AIBuilder.jar` (`lib/server/plugin-jar.ts`); in dev that is the jar in `plugin/core/build/libs/`.
- `python3 -m unittest discover -s sdk/python/tests`: SDK unit tests.
- `python3 tools/mcp_cli.py cost|list|call <tool> '<json>'|report`: the MCP endpoint from a shell, as a client sees it. It needs `AIBUILDER_URL` and `AIBUILDER_TOKEN`; with `AIBUILDER_LOG=<file>` it logs what every call costs, which is how a change to the tools is measured.
- `python3 builds/selftest.py --yes`: places directional blocks, reads them back and checks validation against the running server. Use a throwaway server only.
- Connect: add the server on the panel and install the `AIBuilder-<server>-<commit>.jar` it downloads (the relay settings are inside it; `JarSetup` writes them into `plugins/AIBuilder/config.yml` on start), then export `AIBUILDER_URL=https://<panel>/s/<server>` and `AIBUILDER_TOKEN` (your access token for that server, from Connect Claude on its page) before starting Claude Code. `/aibuilder` in the server console shows the relay status. Players use `/aibuilder mark|unmark|marks|note`, and with `aibuilder.asset` `/aibuilder link <code>|unlink|asset save <name>` (`AccountCommands`; see "Save assets in game" in `docs/panel.md`).

## Building in Minecraft
When the aibuilder MCP server is connected, use the `/build` skill for the workflow and `build-style` for aesthetics.
The server's own instructions (`McpEndpoint.INSTRUCTIONS`) cover coordinates, regions, patterns and undo.

## Server memory
A call must never take the Minecraft server down. What fills the heap is not the plugin's own arrays but what a
call makes the server hold: every chunk it loads (about 1 MB each, for some ten seconds after the last touch) and
WorldEdit's undo history (about 45 MB per million changed blocks, for the last 15 edits of a player). An
`OutOfMemoryError` on the main thread kills the server for good.
- `MemoryGuard` estimates a read or edit before it starts, waits up to 15 seconds for room, then refuses with the
  size that would fit. Large reads go through `BuildService#reading`, one at a time; edits reserve in `submit`.
  `limits.min-free-memory-mb` is the heap it never hands out.
- The map (`WorldMap`) is the exception: it reads region files from disk and never loads a chunk; it snapshots only
  chunks that are already loaded, a few per tick.
- Anything new that reads or changes many blocks goes through those two paths, and an edit task must stop on any
  `Throwable`: one that keeps failing every tick pins the memory the server is short of.
- To test: start the dev server with `JAVA_TOOL_OPTIONS=-Xmx1g ./gradlew :core:runServer` (in `plugin/`) and sweep checks over
  terrain far from spawn; `grep OutOfMemory` in its log must find nothing.

## Token cost
Tool descriptions, schemas and results are read by Claude in every build session, so keep them short:
`McpEndpointTest#toolListStaysWithinBudget` caps the size of `tools/list`, and `/aibuilder stats` in the server
console shows what a session's MCP calls cost. Say things that apply to every tool once, in `INSTRUCTIONS`.

The tool list costs more than a small build's calls, and what Claude writes costs more than what it reads. So:
- Every edit is an op of the one `build` tool, documented in a line of its description (`Tools.BUILD`). A new way to
  place blocks is a new op, not a new tool; the old single-op tools stay registered but unlisted for REST scripts.
- When Claude keeps typing the same blocks or fixing the same mistake, generate it on the server (`roof`, `tree`,
  `light`, door and bed halves) rather than documenting how to do it by hand.
- A result's size must not grow with the region: counts and a few coordinates. `McpEndpoint.MAX_RESULT_CHARS` refuses
  anything larger.
