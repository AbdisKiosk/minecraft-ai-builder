# minecraft-ai-builder

A Paper plugin (`plugin/`) exposes WorldEdit to Claude Code over MCP (`POST /mcp`) and to scripts over REST
(`POST /api/<tool>`). Both use the same tools (`plugin/.../tools/Tools.java`). Optionally the plugin connects out to
a web app, which serves the same endpoints for many servers under `/s/<server>/`: the hosted, multi-user `web/`
(see `docs/web.md`), which replaces the self-hosted Java panel `panel/` (see `docs/panel.md`).

## Modules
- `common/`: pure Java. Op model, blueprint format (and `StateFlip` for mirrored copies), region summary/encoding, region lint (`RegionLint`, `BlockTraits`), generators that save Claude from listing blocks (`Roofs`, `Lighting`, `Halves`, `Terrain`), PNG renderer, `WorldEditBridge` contract, edit history diffs (`RegionSnapshot`, `BlockDiff`), drafts (`Draft`, `DryRunStore`, `PreviewHandle`), region transfer (`StateArrays`) and the relay frame names (`RelayProtocol`), which plugin and panel share. Unit tested.
- `bridge-we7/`: WorldEdit 7.2+ / FAWE (MC 1.13 → 26.x). Compiled against WE 7.2.20 + spigot-api 1.13.2.
  `DryWorld` (in both bridges) is a proxy `World` that records writes for drafts; dry-run sessions use a private event bus.
- `bridge-we6/`: WorldEdit 6.1 (MC 1.8 → 1.12). Modern block states are mapped to legacy id:data via `LegacyBlockTable`, loaded from
  `legacy-blocks.txt`, which `tools/gen_legacy_blocks.py` generates from WorldEdit 7's `legacy.json`. Re-run it only to change aliases, hints or defaults.
- `plugin/`: HTTP server, MCP endpoint, tools, `BuildService` (threading, limits, jobs, progress events), `History` (per-job block diffs, render, revert), `Drafts` (review mode, approve/reject, promote export/import), `relay/RelayClient` (outbound WebSocket to the panel, JDK only). Compiled against spigot-api 1.8.8.
- `panel/`: standalone web panel (Javalin). Relay hub, `/s/<id>/` proxy, login, SSE live updates. The UI is a Next.js app in `web/` (React, TypeScript), exported to static files that the Gradle build puts in the jar; pages are hash routes because `/s/` belongs to the proxy. Never shaded into the plugin; plugin tests use it only on the test classpath.
- `web/`: the hosted app (Next.js, TypeScript). One Node process (`server.ts`) serves the landing page, the dashboard, the API routes, the `/s/<id>/` proxy and the plugins' `/relay` WebSocket. Auth0 accounts, servers, memberships, invites and access tokens in MySQL through raw SQL (no ORM; `migrations/*.sql` run on startup), archived history and Minecraft client jars in Cloudflare R2. Server code is in `lib/server/`; `hub.ts` must stay in step with `RelayProtocol`.
- `sdk/python/aibuilder/`: stdlib-only REST client for build scripts in `builds/`.

Everything targets Java 17 bytecode (`--release 17`). The plugin core must never import WorldEdit classes;
bridges are loaded reflectively in `AIBuilderPlugin#loadBridge`.

## Commands
- `./gradlew build`: tests + shaded jars at `plugin/build/libs/AIBuilder-<version>.jar` and `panel/build/libs/AIBuilderPanel-<version>.jar`
- `cd panel/web && PANEL_URL=http://127.0.0.1:8080 npm run dev`: the panel UI with hot reload, its data proxied from a running panel. `./gradlew build -PskipWeb` builds without Node (a panel with no UI).
- `./gradlew :panel:run --args="add-server test"` / `:panel:run`: panel CLI and server (state in `panel/run/panel.json`)
- `cd web && npm run dev`: the hosted app on :3000 with hot reload (needs `web/.env.local`, see `.env.example`). `npm test`, `npm run typecheck`, `npm run build`; `server.ts` itself does not hot reload.
- `./gradlew :plugin:runServer` (1.21.11 + WE 7.3.19), `:plugin:runLatest` (26.3 + WE 7.4.6), `:plugin:runLegacy` (1.8.8, add WE 6.1.9 yourself)
- `python3 -m unittest discover -s sdk/python/tests`: SDK unit tests.
- `python3 tools/mcp_cli.py cost|list|call <tool> '<json>'|report`: the MCP endpoint from a shell, as a client sees it. It finds the local dev server by itself; with `AIBUILDER_LOG=<file>` it logs what every call costs, which is how a change to the tools is measured.
- `python3 builds/selftest.py --yes`: places directional blocks, reads them back and checks validation against the running server. Use a throwaway server only.
- Connect: run `/aibuilder` in the server console and export the printed `AIBUILDER_URL` and `AIBUILDER_TOKEN` before starting Claude Code. Through the panel, `AIBUILDER_URL` is `https://<panel>/s/<server>` with a client token from `add-client`. Players use `/aibuilder mark|unmark|marks|note`.

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
- Anything new that reads or changes many blocks goes through those two paths, and an edit task must stop on any
  `Throwable`: one that keeps failing every tick pins the memory the server is short of.
- To test: start the dev server with `JAVA_TOOL_OPTIONS=-Xmx1g ./gradlew :plugin:runServer` and sweep checks over
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
