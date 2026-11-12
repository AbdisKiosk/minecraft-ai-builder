---
name: build
description: Workflow for building in Minecraft through the aibuilder MCP server - which tool to use, phases, checking the result, and keeping token use low. Use when asked to build, extend or fix something in the Minecraft world.
---

# Building in Minecraft

The MCP server's instructions cover coordinates, regions, patterns and undo, and the `build` tool lists its ops. This is the workflow on top of them.

## 1. Look first
- `get_context`: player position and facing, selection, `server.legacy_blocks` (true = 1.8-1.12 block set), markers and notes.
- Check `assets` (your library: names, sizes, descriptions) before inventing a window, pillar or tree. Reuse with a `paste` op's `name`; server-local `blueprints` take precedence on a name collision. `save_blueprint` with `description` saves to your account when authenticated. If `assets_error` appears, don't assume the library is empty or silently replace the user's pieces.
- It may carry `lessons`: follow them. `feedback` (on a rejected draft or reverted build) and `corrections` (what people changed in your earlier builds) are said once: act on them, and save a rule that holds for later builds with `lesson` (`{"text","entry"}`).
- With a selection, pass `"region": "selection"`. Otherwise choose an origin near the player and say where it is.
- A place that is hard to describe: ask the player to stand there or look at it and run `/aibuilder mark <name>`, then use `"@name"` as a position or `origin`. When done, `tell` the player where the build is.
- `read_region` (add `heightmap` when the terrain matters) on the site. `mode=full` only for regions up to about 10x10x10.
- Unfamiliar block or state: `block_info`. Bad blocks are rejected before anything is placed, so a failed call leaves nothing behind.

## 2. Place blocks the cheapest way
What you write costs the most, so say each thing once: one `build` call per phase, with `origin` (coordinates become small offsets) and `vars` for a pattern used twice.

| Job | Use |
|---|---|
| Walls, floors, rows | `fill` (`mode: walls` for a shell) |
| Posts, windows, details | one `blocks` op grouped by state; `[x1,y1,z1,x2,y2,z2]` is a box of that state |
| Roof | `roof` (gable or hip): it faces the stairs and fills the gable ends |
| Trees | `tree`: grown by the server, so natural and never floating |
| Lighting a finished interior | `light` on its region, last |
| The same module several times | `paste` once per position (`save_blueprint` + `name`), or `clone` with `repeat` + `step`, `flip`, `rotation` |
| Over ~500 single blocks, loops, curves | A script (below) |

A door, bed or tall plant needs one entry: the other half is added. Fences, panes, walls and stair corners connect by themselves. Clone and saved blueprints copy block states only, not sign text or container contents.

Terrain ops read the ground as it is before the call. On a slope, put a `foundation` op (region top = the level just under the floor) before the walls so nothing floats; `overlay` lays paths and ground cover that follow the surface (`depth` replaces, `raise` adds on top); `smooth` blends cut or filled edges into the land.

```json
{"origin": [104, 64, -20], "vars": {"W": "70%stone_bricks,20%cracked_stone_bricks,10%mossy_stone_bricks"},
 "ops": [{"op": "fill", "region": [0, 0, 0, 8, 0, 6], "pattern": "cobblestone"},
         {"op": "fill", "region": [0, 1, 0, 8, 4, 6], "pattern": "$W", "mode": "walls"},
         {"op": "blocks", "blocks": {"spruce_log": [[0, 1, 0, 0, 4, 0], [8, 1, 0, 8, 4, 0], [0, 1, 6, 0, 4, 6], [8, 1, 6, 8, 4, 6]],
                                     "glass_pane": [[2, 2, 0, 3, 3, 0], [5, 2, 6, 6, 3, 6]], "spruce_door[facing=north]": [[4, 1, 0]]}},
         {"op": "roof", "region": [0, 5, 0, 8, 5, 6], "stairs": "spruce_stairs", "gable": "spruce_planks"},
         {"op": "tree", "at": [12, 0, 3], "type": "birch"},
         {"op": "light", "region": [0, 0, 0, 8, 5, 6], "block": "lantern"}]}
```

Never hand-type long block lists or blueprints.

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
b.blocks(v.items())                            # world coordinates
b.roof([104, 69, -20, 112, 69, -14], "spruce_stairs", gable="spruce_planks")
b.light([104, 64, -20, 112, 69, -14])
print(b.flush())                               # one undo step
```

- `Vox` has `set`, `box`, `ring`, weighted patterns, `roof_gable`, `roof_hip`, `arch(x0, x1, y, z, rise, state)`, `mirror(axis, at)`, `rotate(turns, about)`, `connect` (only needed on FastAsyncWorldEdit); do not rewrite those helpers. `v.b` is the underlying dict.
- For shapes rather than single blocks, `b.at(origin)` then `b.fill`, `b.cylinder`, `b.roof`, `b.tree`, `b.light`, ... with small offsets.
- One function per phase and a phase name as argument, so a fix reruns one phase, not the whole build.
- Print only the flush result. Check with `b.check(region)`, which covers up to 2,000,000 blocks in one call; do not write your own checkers or read regions back to verify. Save renders with `b.render(region, save_to=...)` and look at the file only when needed.

## 4. Phases and checks
- Foundation, frame, walls, roof, details. One `build` call or script phase per step, so each is one `undo`.
- After a phase, `check_region` on it first: the cheapest check, a few coordinates of floating or unsupported blocks, lone door/bed halves, reversed stairs, unconnected fences/panes and dark floors (`limit` lists more coordinates). Fix the real ones; `light` fixes dark floors.
- Then one `render_region` (default: small isometric view, a few hundred tokens). `"top"` or `"both"` when the floor plan matters, an elevation (`"north"`, `"south"`, `"east"`, `"west"`) to judge a facade, the opposite `corner` (e.g. `"nw"`) to see the back, `"large"` only for the final look of a big build.
- Last, `decorate` the placed signs and containers, and add item frames (`facing` = away from the wall) and armor stands:
  `{"origin": [104, 64, -20], "items": [{"at": [2, 2, 0], "text": ["The Inn", "Rooms"]}, {"at": [1, 1, 5], "contents": {"bread": 6, "torch": 16}}]}`.
  `undo` does not revert it; undoing the blocks removes a sign or chest with its data, and `remove` clears frames and stands.
- For a critique of how it looks, give the region and the brief to the `build-reviewer` subagent: the images stay in its context and you get back at most six concrete fixes.
- To confirm a small fix, `check_region` or `read_region` counts are cheaper than another render.

## 5. Large builds with subagents
Every subagent starts by paying for its own context, and a changed requirement costs a rebuild and a re-check, so:
- Settle the spec (sizes, palette, what goes inside) before anyone builds.
- Give each agent a region of its own and tell it to stay inside; neighbours' regions are read-only.
- Ask for one short final report (region, what was built, what `check_region` still shows), no progress messages.
- Use a smaller model for checking and read-only work.

## 6. When something is off
- `undo` reverts the last `build` call. A result with `"status": "running"` is still working: poll `job_status`.
- With several players online, pass `player`; without it calls act as the console and `~` coordinates and selections fail.
