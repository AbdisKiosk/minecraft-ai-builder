#!/usr/bin/env python3
"""Generates bridge-we6's block table from WorldEdit 7's legacy.json.

legacy.json maps every Minecraft 1.12 block id:data to its 1.13 block state, e.g.
    "53:7": "minecraft:oak_stairs[half=top,shape=straight,facing=north]"
That is exactly the "reverse flattening" a 1.8 server needs to understand modern block states, so
we reuse it instead of hand-coding block families.

Usage (stdlib only):
    python3 -I tools/gen_legacy_blocks.py [--jar PATH_TO_worldedit-core-7.2.20.jar]

Without --jar the jar is looked up in the Gradle cache. Output:
    plugin/bridge-we6/src/main/resources/me/abdiskiosk/minecraftaibuilder/bridge/we6/legacy-blocks.txt

Output line kinds (see LegacyBlockTable):
    <id>:<data> <type> [prop=value,...]     bidirectional
    +<id>:<data> <type> [prop=value,...]    placing only (none are generated today; the loader supports it)
    -<id>:<data> <type> [prop=value,...]    reading only
    =<type> prop=value,...                  modern default state (what a bare name means)
    @alias <modern name> <name in table>    renames after 1.13
    @hint <glob> alt1,alt2                  suggestions for blocks 1.8 does not have
    item <id>:<damage> <name>               items, for containers
"""

import fnmatch
import glob
import json
import os
import sys
import zipfile

ENTRY = "com/sk89q/worldedit/world/registry/legacy.json"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "plugin", "bridge-we6", "src", "main", "resources",
                   "me", "abdiskiosk", "minecraftaibuilder", "bridge", "we6", "legacy-blocks.txt")

# What a property means when the client leaves it out. These are the 1.13+ defaults, so a bare
# "oak_stairs" is the same block on a 1.8 server and on a modern one.
DEFAULTS = {
    "facing": "north", "half": "bottom", "type": "bottom", "axis": "y", "open": "false", "powered": "false",
    "lit": "false", "hinge": "left", "rotation": "0", "face": "wall", "shape": "straight", "part": "foot",
    "occupied": "false", "waterlogged": "false", "delay": "1", "attached": "false", "in_wall": "false",
    "extended": "false", "conditional": "false", "triggered": "false", "layers": "1", "age": "0", "level": "0",
    "snowy": "false", "persistent": "false", "distance": "7", "power": "0", "moisture": "0", "bites": "0",
    "eye": "false", "enabled": "true", "locked": "false", "disarmed": "false", "has_bottle_0": "false",
    "has_bottle_1": "false", "has_bottle_2": "false", "inverted": "false", "variant": "oak",
    "north": "false", "south": "false", "east": "false", "west": "false", "up": "false", "down": "false",
    "bottom": "false", "drag": "true", "stage": "0", "honey_level": "0", "has_record": "false",
    "note": "0", "instrument": "harp", "mode": "compare", "hanging": "false", "check_decay": "false",
}
TYPE_DEFAULTS = {  # per-block exceptions to DEFAULTS
    "hopper": {"facing": "down"},
    "redstone_torch": {"lit": "true"},
    "redstone_wall_torch": {"lit": "true"},
    "redstone_wire": {},
    "water": {}, "lava": {},
}
# Properties that decide *which part* of a block a state is; matched before everything else so that
# e.g. oak_door[half=upper] never falls back to a lower half.
STRUCTURAL = ["half", "part", "type"]
# 1.13 blocks that did not exist as such in 1.8: suggestions for the error message (never auto-applied).
HINTS = [
    ("lantern", "glowstone,sea_lantern,redstone_lamp"),
    ("soul_lantern", "glowstone,sea_lantern"),
    ("*_concrete", "white_stained_clay,white_wool"),
    ("*_concrete_powder", "sand,white_wool"),
    ("deepslate*", "stone,cobblestone,stonebrick"),
    ("cobbled_deepslate*", "cobblestone"),
    ("polished_deepslate*", "smooth_stone,stone"),
    ("blackstone*", "obsidian,coal_block"),
    ("polished_blackstone*", "obsidian,coal_block"),
    ("cherry_*", "birch_planks,birch_stairs,birch_slab"),
    ("mangrove_*", "dark_oak_planks,dark_oak_stairs"),
    ("bamboo_*", "birch_planks,birch_stairs"),
    ("crimson_*", "nether_brick,nether_brick_stairs"),
    ("warped_*", "prismarine,prismarine_bricks"),
    ("mud_bricks*", "bricks,brick_stairs"),
    ("mud", "dirt,soul_sand"),
    ("*_terracotta", "white_stained_clay"),
    ("copper*", "iron_block,gold_block"),
    ("*_copper", "iron_block,gold_block"),
    ("chain", "iron_bars,fence"),
    ("tuff*", "stone,andesite,cobblestone"),
    ("calcite", "quartz_block,white_wool"),
    ("moss_block", "grass,leaves"),
    ("moss_carpet", "carpet"),
    ("dirt_path", "grass_path,dirt,sand"),
    ("smooth_stone_slab", "stone_slab"),
    ("barrel", "chest"),
    ("smoker", "furnace"),
    ("blast_furnace", "furnace"),
    ("composter", "cauldron"),
    ("scaffolding", "ladder,fence"),
    ("campfire", "furnace,netherrack"),
    ("*_wall", "cobblestone_wall"),
    ("stripped_*", "oak_log,spruce_log"),
    ("*_candle", "torch"),
    ("amethyst*", "glass,quartz_block"),
    ("glow_lichen", "vine"),
    ("short_grass", "grass,tallgrass"),
    ("end_rod", "fence,iron_bars"),
]
# Renames since the 1.13 names legacy.json uses. Left = what clients may type today.
ALIASES = [
    ("short_grass", "grass"),          # 1.20.3
    ("dirt_path", "grass_path"),       # 1.17
    ("oak_sign", "sign"),              # 1.14 gave every wood its own sign
    ("oak_wall_sign", "wall_sign"),
]
FLOWING = (8, 10)  # flowing water/lava; the stationary twins (9, 11) are what we place


def load(jar):
    with zipfile.ZipFile(jar) as z:
        return json.loads(z.read(ENTRY))


def parse_state(state):
    state = state.removeprefix("minecraft:")
    if "[" not in state:
        return state, {}
    name, rest = state.split("[", 1)
    props = {}
    for kv in rest.rstrip("]").split(","):
        k, v = kv.split("=", 1)
        props[k] = v
    return name, props


def find_jar():
    pattern = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1/com.sk89q.worldedit/worldedit-core/7.2.20/*/worldedit-core-7.2.20.jar")
    found = glob.glob(pattern)
    if not found:
        sys.exit("worldedit-core-7.2.20.jar not found in the Gradle cache; run ./gradlew build in plugin/ once or pass --jar")
    return found[0]


def main():
    jar = sys.argv[sys.argv.index("--jar") + 1] if "--jar" in sys.argv else find_jar()
    data = load(jar)
    lines = []
    types = {}

    for key, state in sorted(data["blocks"].items(), key=lambda kv: tuple(int(p) for p in kv[0].split(":"))):
        block_id = int(key.split(":")[0])
        name, props = parse_state(state)
        flag = "-" if block_id in FLOWING else ""
        prop_text = ",".join(f"{k}={v}" for k, v in sorted(props.items()))
        lines.append(f"{flag}{key} {name}" + (f" {prop_text}" if prop_text else ""))
        types.setdefault(name, []).append(props)

    out = [
        "# Generated by tools/gen_legacy_blocks.py from WorldEdit 7.2.20 legacy.json (GPLv3, EngineHub). Do not edit by hand.",
        "# <id>:<data> <type> [props] | +place only | -read only | =defaults | @alias | @hint | item",
    ]
    out += [f"@alias {new} {old}" for new, old in ALIASES if new != old]
    out += [f"@hint {glob_} {alts}" for glob_, alts in HINTS]
    for name, entries in sorted(types.items()):
        seen = {}
        for e in entries:  # values in legacy data order, so seen[k][0] is the lowest-data value
            for k, v in e.items():
                if v not in seen.setdefault(k, []):
                    seen[k].append(v)
        default = {}
        for k, values in seen.items():
            wanted = TYPE_DEFAULTS.get(name, {}).get(k, DEFAULTS.get(k))
            if wanted in values:
                default[k] = wanted
            elif k == "half" and "lower" in values:  # doors and tall plants say lower/upper instead of bottom/top
                default[k] = "lower"
            elif len(values) > 1 or k in DEFAULTS:
                default[k] = values[0]
        if default:
            out.append(f"={name} " + ",".join(f"{k}={v}" for k, v in sorted(default.items())))
    out += lines
    for key, name in sorted(data["items"].items(), key=lambda kv: tuple(int(p) for p in kv[0].split(":"))):
        out.append(f"item {key} {name.removeprefix('minecraft:')}")

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        f.write("\n".join(out) + "\n")
    print(f"wrote {os.path.normpath(OUT)}: {len(lines)} block entries, {len(types)} block types, {len(data['items'])} items")

    # Report states reachable from several id:data pairs so surprises are visible in review.
    inverse = {}
    for key, state in data["blocks"].items():
        inverse.setdefault(state, []).append(key)
    dups = {s: k for s, k in inverse.items() if len(k) > 1 and not s.startswith(("minecraft:water", "minecraft:lava"))}
    print(f"{len(dups)} non-liquid states have several legacy ids, lowest id:data wins when placing", file=sys.stderr)


if __name__ == "__main__":
    main()
