# minecraft-ai-builder

A Paper plugin (`plugin/`) exposes WorldEdit to Claude Code over MCP (`POST /mcp`) and to scripts over REST
(`POST /api/<tool>`). Both use the same tools (`plugin/.../tools/Tools.java`).

## Modules
- `common/`: pure Java. Op model, blueprint format, region summary/encoding, PNG renderer, `WorldEditBridge` contract. Unit tested.
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

## Building in Minecraft (when the aibuilder MCP server is connected)
- Start with `get_context`. If the player has a selection, use `"region": "selection"` instead of coordinates.
- Axes: +y up, +x east, +z south. `"~n"` coordinates are relative to the player's feet.
- Modern block names and states (`stone_bricks`, `oak_stairs[facing=east,half=top]`), also on 1.8 servers, where only blocks that exist in 1.8 are accepted.
  `get_context.server` says which (`legacy_blocks`); `block_info` lists a block's properties and the closest names for an unknown one.
  Bad blocks are rejected before anything is placed, so a failed call never leaves a half-built structure.
- With several players online and no `player` argument, calls act as the console: `~` coordinates, selections and (on WorldEdit 6) undo then need `player`.
- Patterns use WorldEdit syntax: `80%stone_bricks,20%andesite`.
- **Token budget:** use shapes (`fill`, `walls`, `cylinder`, ...) or `batch` for a few ops. For anything
  over ~500 individual blocks or with repetition, write `builds/<name>.py` with the Python SDK and run it.
  Never hand-type large `set_blocks` lists. Never use `read_region mode=full` on big regions; use `render_region`.
- Build in phases (foundation → frame → walls → roof → detail), render after each phase, fix, then continue.
- Each tool call or batch is one undo step; `undo` reverts the latest.
- Use the `/build` skill for the full workflow and `build-style` for aesthetics.
