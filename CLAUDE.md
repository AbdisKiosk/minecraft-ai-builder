# minecraft-ai-builder

A Paper plugin (`plugin/`) exposes WorldEdit to Claude Code over MCP (`POST /mcp`) and to scripts over REST
(`POST /api/<tool>`). Both use the same tools (`plugin/.../tools/Tools.java`). Optionally the plugin connects out to
the web panel (`panel/`), which serves the same endpoints for many servers under `/s/<server>/`; see `docs/panel.md`.

## Modules
- `common/`: pure Java. Op model, blueprint format (and `StateFlip` for mirrored copies), region summary/encoding, region lint (`RegionLint`, `BlockTraits`), generators that save Claude from listing blocks (`Roofs`, `Lighting`, `Halves`, `Terrain`), PNG renderer, `WorldEditBridge` contract, edit history diffs (`RegionSnapshot`, `BlockDiff`) and the relay frame names (`RelayProtocol`), which plugin and panel share. Unit tested.
- `bridge-we7/`: WorldEdit 7.2+ / FAWE (MC 1.13 → 26.x). Compiled against WE 7.2.20 + spigot-api 1.13.2.
- `bridge-we6/`: WorldEdit 6.1 (MC 1.8 → 1.12). Modern block states are mapped to legacy id:data via `LegacyBlockTable`, loaded from
  `legacy-blocks.txt`, which `tools/gen_legacy_blocks.py` generates from WorldEdit 7's `legacy.json`. Re-run it only to change aliases, hints or defaults.
- `plugin/`: HTTP server, MCP endpoint, tools, `BuildService` (threading, limits, jobs, progress events), `History` (per-job block diffs, render, revert), `relay/RelayClient` (outbound WebSocket to the panel, JDK only). Compiled against spigot-api 1.8.8.
- `panel/`: standalone web panel (Javalin). Relay hub, `/s/<id>/` proxy, login, SSE live updates, static UI in `src/main/resources/web/`. Never shaded into the plugin; plugin tests use it only on the test classpath.
- `sdk/python/aibuilder/`: stdlib-only REST client for build scripts in `builds/`.

Everything targets Java 17 bytecode (`--release 17`). The plugin core must never import WorldEdit classes;
bridges are loaded reflectively in `AIBuilderPlugin#loadBridge`.

## Commands
- `./gradlew build`: tests + shaded jars at `plugin/build/libs/AIBuilder-<version>.jar` and `panel/build/libs/AIBuilderPanel-<version>.jar`
- `./gradlew :panel:run --args="add-server test"` / `:panel:run`: panel CLI and server (state in `panel/run/panel.json`)
- `./gradlew :plugin:runServer` (1.21.11 + WE 7.3.19), `:plugin:runLatest` (26.3 + WE 7.4.6), `:plugin:runLegacy` (1.8.8, add WE 6.1.9 yourself)
- `python3 -m unittest discover -s sdk/python/tests`: SDK unit tests.
- `python3 tools/mcp_cli.py cost|list|call <tool> '<json>'|report`: the MCP endpoint from a shell, as a client sees it. It finds the local dev server by itself; with `AIBUILDER_LOG=<file>` it logs what every call costs, which is how a change to the tools is measured.
- `python3 builds/selftest.py --yes`: places directional blocks, reads them back and checks validation against the running server. Use a throwaway server only.
- Connect: run `/aibuilder` in the server console and export the printed `AIBUILDER_URL` and `AIBUILDER_TOKEN` before starting Claude Code. Through the panel, `AIBUILDER_URL` is `https://<panel>/s/<server>` with a client token from `add-client`. Players use `/aibuilder mark|unmark|marks|note`.

## Building in Minecraft
When the aibuilder MCP server is connected, use the `/build` skill for the workflow and `build-style` for aesthetics.
The server's own instructions (`McpEndpoint.INSTRUCTIONS`) cover coordinates, regions, patterns and undo.

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
