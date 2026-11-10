# minecraft-ai-builder

A Paper plugin (`plugin/`) exposes WorldEdit to Claude Code over MCP (`POST /mcp`) and to scripts over REST
(`POST /api/<tool>`). Both use the same tools (`plugin/.../tools/Tools.java`).

## Modules
- `common/`: pure Java. Op model, blueprint format (and `StateFlip` for mirrored copies), region summary/encoding, region lint (`RegionLint`, `BlockTraits`), PNG renderer, `WorldEditBridge` contract. Unit tested.
- `bridge-we7/`: WorldEdit 7.2+ / FAWE (MC 1.13 → 26.x). Compiled against WE 7.2.20 + spigot-api 1.13.2.
- `bridge-we6/`: WorldEdit 6.1 (MC 1.8 → 1.12). Modern block states are mapped to legacy id:data via `LegacyBlockTable`, loaded from
  `legacy-blocks.txt`, which `tools/gen_legacy_blocks.py` generates from WorldEdit 7's `legacy.json`. Re-run it only to change aliases, hints or defaults.
- `plugin/`: HTTP server, MCP endpoint, tools, `BuildService` (threading, limits, jobs). Compiled against spigot-api 1.8.8.
- `sdk/python/aibuilder/`: stdlib-only REST client for build scripts in `builds/`.

Everything targets Java 17 bytecode (`--release 17`). The plugin core must never import WorldEdit classes;
bridges are loaded reflectively in `AIBuilderPlugin#loadBridge`.

## Commands
- `./gradlew build`: tests + shaded jar at `plugin/build/libs/AIBuilder-<version>.jar`
- `./gradlew :plugin:runServer` (1.21.11 + WE 7.3.19), `:plugin:runLatest` (26.3 + WE 7.4.6), `:plugin:runLegacy` (1.8.8, add WE 6.1.9 yourself)
- `python3 builds/selftest.py --yes`: places directional blocks, reads them back and checks validation against the running server. Use a throwaway server only.
- Connect: run `/aibuilder` in the server console and export the printed `AIBUILDER_URL` and `AIBUILDER_TOKEN` before starting Claude Code.

## Building in Minecraft
When the aibuilder MCP server is connected, use the `/build` skill for the workflow and `build-style` for aesthetics.
The server's own instructions (`McpEndpoint.INSTRUCTIONS`) cover coordinates, regions, patterns and undo.

## Token cost
Tool descriptions, schemas and results are read by Claude in every build session, so keep them short:
`McpEndpointTest#toolListStaysWithinBudget` caps the size of `tools/list`, and `/aibuilder stats` in the server
console shows what a session's MCP calls cost. Say things that apply to every tool once, in `INSTRUCTIONS`.
