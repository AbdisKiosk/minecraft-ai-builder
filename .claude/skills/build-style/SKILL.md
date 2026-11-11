---
name: build-style
description: Minecraft building aesthetics - palettes, depth, roofs, detailing. Use when planning or critiquing how a Minecraft build looks.
---

# Building style guide

## Palette
- 3–5 blocks: one main, one or two accents, one trim, one roof. Example (medieval): stone_bricks / cobblestone / spruce_log / spruce_planks / dark_oak_stairs.
- Texture large surfaces with weighted patterns instead of single blocks: `70%stone_bricks,20%cracked_stone_bricks,10%mossy_stone_bricks`.
- Gradients go bottom-to-top from dark/rough to light/clean: cobblestone → stone_bricks → smooth_stone.

## Older servers
- If `get_context.server.legacy_blocks` is true (1.8-1.12), the block set is smaller: no lanterns, chains, concrete, deepslate, copper, mud, cherry/bamboo woods. Use `glowstone`/`sea_lantern`/`redstone_lamp` for light, `*_stained_clay`/`*_wool` for colour, `iron_bars` for chains. Unknown blocks are rejected with suggestions; check with `block_info` instead of guessing.

## Shape and depth
- No flat walls longer than ~5 blocks. Break them with log pillars every 4–6 blocks, set windows 1 block in, and add a 1-block overhang under the roof.
- Use odd widths (5, 7, 9) so doors, windows and roof peaks can be centred.
- Make the foundation one block wider than the walls, in a darker block.
- Use stairs and slabs for roof slopes and window sills, `[half=top]` stairs under eaves, and fences/walls for railings.

## Roofs
- Gable: each side's stairs have `facing` pointing toward the ridge (the direction the slope rises), rising one block per step, with a full block or slab ridge. The roof should overhang the walls by one block.
- Steeper roofs (two blocks up per step, using stair + full block) read as gothic or alpine. Shallow slab roofs read as modern.
- Close the gable ends with the trim block or planks, not the main wall block.
- In scripts use `Vox.roof_gable` / `Vox.roof_hip` (then `connect()` for the corners) instead of placing roof stairs by hand.

## Details (last phase)
- Light every ~8 blocks: lanterns, wall torches, or glowstone hidden under carpet.
- Add flower pots, barrels, trapdoor shutters, leaves on walls, path blocks to the door, and a few scattered coarse_dirt/gravel patches.
- Keep the interior one block smaller than the shell on each side. Floors should be 4–5 blocks apart.

## Critique checklist (use on renders)
- Does the silhouette read clearly from the iso view (varied height, no box)?
- Is any wall or roof face flat and untextured?
- Is the palette consistent, with no stray blocks?
- Is anything floating, cut off, or clipping into terrain?
- Are the proportions right (height vs width, roof vs walls ~1:1 for houses)?
