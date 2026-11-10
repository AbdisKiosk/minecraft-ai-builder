---
name: build
description: Workflow for building in Minecraft through the aibuilder MCP server - which tool to use, phases, checking the result, and keeping token use low. Use when asked to build, extend or fix something in the Minecraft world.
---

# Building in Minecraft

The MCP server's instructions cover coordinates, regions, patterns and undo. This is the workflow on top of them.

## 1. Look first
- `get_context`: player position and facing, selection, `server.legacy_blocks` (true = 1.8-1.12 block set).
- With a selection, pass `"region": "selection"`. Otherwise choose an origin near the player and say where it is.
- `read_region` (summary; add `heightmap` when the terrain matters) on the site. `mode=full` only for regions up to about 10x10x10.
- Unfamiliar block or state: `block_info`. Bad blocks are rejected before anything is placed, so a failed call leaves nothing behind.

## 2. Place blocks the cheapest way
Every argument you write and every result you read costs tokens, so pick by size:

| Job | Use |
|---|---|
| A few shapes | One `batch` with `origin` (coordinates become small offsets) and `vars` for any pattern used twice |
| Details, up to ~100 blocks | `set_blocks` grouped by state, with `origin` |
| The same module several times | `place_blueprint` once per position, or one script |
| Copy, mirror or repeat something already built | `clone` op in a batch (`flip`, `rotation`, `repeat` + `step`) |
| Over ~500 single blocks, loops, symmetry, curves | A script (below) |

`save_blueprint` keeps a region on the server; `place_blueprint` or a `paste` op with `name` places it again. Clone and saved blueprints copy block states only, not sign text or container contents.

```json
{"origin": [104, 64, -20], "vars": {"W": "70%stone_bricks,20%cracked_stone_bricks,10%mossy_stone_bricks"},
 "ops": [{"op": "fill", "region": [0, 0, 0, 8, 0, 6], "pattern": "cobblestone"},
         {"op": "fill", "region": [0, 1, 0, 8, 4, 6], "pattern": "$W", "mode": "walls"},
         {"op": "blocks", "blocks": {"spruce_log": [[0, 1, 0], [8, 1, 0], [0, 1, 6], [8, 1, 6]]}}]}
```

Never hand-type long `set_blocks` lists or blueprints.

## 3. Scripts
Write `builds/<name>.py` and run it from the repository root (`PYTHONPATH` already points at the SDK, so no `sys.path` lines):

```python
"""Stone hut. Run: python3 builds/hut.py"""
from aibuilder import Builder, Vox

b = Builder.connect()
v = Vox(origin=(104, 64, -20))                 # local coordinates from here on
v.box(0, 0, 0, 8, 4, 6, "70%stone_bricks,30%andesite")
v.box(1, 1, 1, 7, 4, 5, "air")
v.ring(0, 5, 0, 8, 6, "oak_fence")
v.connect()                                    # fences and panes join their neighbours
b.blocks(v.items())
print(b.flush())                               # one undo step
```

- `Vox` has `set`, `box`, `ring`, weighted patterns and `connect`; do not rewrite those helpers. `v.b` is the underlying dict.
- For shapes rather than single blocks, `b.at(origin)` then `b.fill`, `b.cylinder`, `b.sphere`, `b.line`, ... with small offsets.
- One function per phase and a phase name as argument, so a fix reruns one phase, not the whole build.
- Print only the flush result. Save renders with `b.render(region, save_to=..., size="small")` and look at the file only when needed.

## 4. Phases and checks
- Foundation, frame, walls, roof, details. One batch or script phase per step, so each is one `undo`.
- After a phase, run `check_region` on it first: the cheapest check, a few coordinates of floating or unsupported blocks, lone door/bed halves, reversed stairs, unconnected fences/panes and dark floors. Fix the real ones.
- Then look with `render_region` at `size: "small"`, `view: "iso"` (a few hundred tokens). Use `"both"` when the floor plan matters, `"large"` only for the final look or when asked. The image is cropped to the blocks, so a generous region costs nothing extra.
- To judge a facade use the elevation of that face (`view: "north"`, `"south"`, `"east"`, `"west"`); to see the back of a build, `view: "iso"` with the opposite `corner` (e.g. `"nw"`).
- For a critique of how it looks, give the region and the brief to the `build-reviewer` subagent: the images stay in its context and you get back at most six concrete fixes.
- To confirm a small fix, `check_region` or `read_region` summary counts are cheaper than another render.

## 5. When something is off
- `undo` reverts the last call or batch. A result with `"status": "running"` is still working: poll `job_status`.
- With several players online, pass `player`; without it calls act as the console and `~` coordinates and selections fail.
