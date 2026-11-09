"""End-to-end check of block states, validation and error handling against a RUNNING server.

It builds in the air at --origin (default 0,150,0), reads everything back, then clears the area again.
Run it on a throwaway dev server, never on a world you care about:

    AIBUILDER_URL=http://127.0.0.1:8765 AIBUILDER_TOKEN=... python3 builds/selftest.py --yes [--origin X Y Z]

Works on 1.8 (WorldEdit 6) and 1.13+ (WorldEdit 7); cases that only apply to one are skipped on the other.
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "sdk" / "python"))
from aibuilder import AIBuilderError, Builder, box  # noqa: E402

# What a property means when it is left out (the 1.13+ defaults); read-back may omit default values.
DEFAULTS = {"facing": "north", "half": "bottom", "type": "bottom", "axis": "y", "open": "false", "hinge": "left",
            "powered": "false", "part": "foot", "waterlogged": "false", "shape": "straight", "delay": "1"}

results = []


def parse(state):
    state = state.removeprefix("minecraft:")
    if "[" not in state:
        return state, {}
    name, rest = state.split("[", 1)
    return name, dict(kv.split("=", 1) for kv in rest.rstrip("]").split(","))


# 1.8 reads blocks back under their 1.13 names; 1.14 gave every wood its own sign.
SAME = {"wall_sign": "oak_wall_sign", "sign": "oak_sign"}


def default(block_type, prop):
    if prop == "half" and block_type.endswith("_door"):
        return "lower"  # doors say lower/upper, everything else bottom/top
    return DEFAULTS.get(prop)


def matches(expected, actual):
    """Same block, and every property the test asked for has that value (missing = default)."""
    e_type, e_props = parse(expected)
    a_type, a_props = parse(actual)
    a_type = SAME.get(a_type, a_type)
    return e_type == a_type and all(a_props.get(k, default(a_type, k)) == v for k, v in e_props.items())


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"   -> {detail}"))


def decode(read):
    """read_region mode=full -> {(x, y, z): state}."""
    ox, oy, oz = read["origin"]
    out = {}
    for y, layer in enumerate(read["layers"]):
        for z, row in enumerate(layer):
            x, count = 0, ""
            for ch in row:
                if ch.isdigit():
                    count += ch
                    continue
                for _ in range(int(count or 1)):
                    out[(ox + x, oy + y, oz + z)] = read["palette"][ch]
                    x += 1
                count = ""
    return out


def read_cells(b, cells):
    xs, ys, zs = zip(*cells)
    return decode(b.read(box([min(xs), min(ys), min(zs)], [max(xs), max(ys), max(zs)]), mode="full"))


def expect_error(name, fn, needle):
    try:
        fn()
    except AIBuilderError as e:
        check(name, needle in str(e), f"message was: {e}")
        return e
    check(name, False, "no error was raised")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--yes", action="store_true", help="confirm this is a throwaway server")
    ap.add_argument("--origin", type=int, nargs=3, default=[0, 150, 0])
    args = ap.parse_args()

    b = Builder.connect()
    ctx = b.context()
    server = ctx.get("server", {})
    legacy = bool(server.get("legacy_blocks"))
    print(f"server: Minecraft {server.get('minecraft')}, {ctx.get('bridge')}, legacy_blocks={legacy}, caller={ctx['caller']['name']}")
    if not args.yes:
        sys.exit("refusing to build without --yes (this places and removes blocks around the origin)")
    ox, oy, oz = args.origin
    area = box([ox - 2, oy - 2, oz - 2], [ox + 60, oy + 25, oz + 60])
    b.fill(area, "air")
    b.flush()

    # ---- directional blocks: place, read back, compare ----
    cases, supports = [], []

    def cell(i, dy=0):
        """A 4x4 slot with a stone floor and four stone neighbours, so wall-mounted blocks have something to hang on."""
        x, z = ox + (i % 12) * 4, oz + (i // 12) * 4
        for dx, dz in [(0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)]:
            supports.append((x + dx, oy - 1 + dy, z + dz, "stone"))
        for dx, dz in [(1, 0), (-1, 0), (0, 1), (0, -1)]:
            supports.append((x + dx, oy + dy, z + dz, "stone"))
        return x, oy + dy, z

    slot = 0
    states = []
    states += [f"oak_stairs[facing={f},half={h}]" for f in ("north", "east", "south", "west") for h in ("bottom", "top")]
    states += ["oak_stairs[facing=east]", "oak_stairs[half=top]", "stone_brick_stairs[facing=west,half=top]"]
    states += ["oak_slab[type=top]", "oak_slab[type=bottom]", "stone_slab[type=double]"]
    states += ["oak_log[axis=x]", "oak_log[axis=y]", "oak_log[axis=z]", "spruce_log[axis=x]"]
    states += [f"ladder[facing={f}]" for f in ("north", "south", "east", "west")]
    states += [f"oak_wall_sign[facing={f}]" for f in ("north", "south", "east", "west")]
    states += [f"wall_torch[facing={f}]" for f in ("north", "south", "east", "west")]
    states += [f"chest[facing={f}]" for f in ("north", "south", "east", "west")]
    states += ["furnace[facing=east]", "anvil[facing=east]", "dispenser[facing=up]", "hopper[facing=south]", "piston[facing=up]"]
    states += ["repeater[facing=east]", "oak_fence_gate[facing=south,open=true]", "carved_pumpkin[facing=south]", "torch"]
    states += ["oak_sign[rotation=4]"]
    for st in states:
        x, y, z = cell(slot)
        b.block(x, y, z, st)
        cases.append((st, (x, y, z)))
        slot += 1
    # two-part blocks
    x, y, z = cell(slot); slot += 1
    b.block(x, y, z, "oak_door[facing=north,half=lower,hinge=left,open=false]")
    b.block(x, y + 1, z, "oak_door[facing=north,half=upper,hinge=left,open=false]")
    cases += [("oak_door[facing=north,half=lower]", (x, y, z)), ("oak_door[half=upper,hinge=left]", (x, y + 1, z))]
    x, y, z = cell(slot); slot += 1
    supports[:] = [sp for sp in supports if not (sp[0] == x and sp[2] == z + 1 and sp[1] == y)]  # keep the head's square free
    b.block(x, y, z, "red_bed[part=foot,facing=south]")
    b.block(x, y, z + 1, "red_bed[part=head,facing=south]")
    supports.append((x, y - 1, z + 1, "stone"))
    cases += [("red_bed[part=foot,facing=south]", (x, y, z)), ("red_bed[part=head,facing=south]", (x, y, z + 1))]

    for sx, sy, sz, st in supports:
        b.block(sx, sy, sz, st)
    try:
        b.flush()
        placed = True
    except AIBuilderError as e:
        check("place directional blocks", False, str(e))
        placed = False
    if placed:
        got = read_cells(b, [c for _, c in cases] + [(sx, sy, sz) for sx, sy, sz, _ in supports[:1]])
        for st, pos in cases:
            actual = got.get(pos, "?")
            check(f"state {st}", matches(st, actual), f"read back {actual}")

    # ---- masks match what was asked for, not every variant ----
    mx, mz = ox, oz + 56
    for i, st in enumerate(["oak_log", "spruce_log", "stone", "granite", "andesite", "dirt", "coarse_dirt"]):
        b.block(mx + i, oy, mz, st)
    b.flush()
    b.replace(box([mx, oy, mz], [mx + 6, oy, mz]), "oak_log", "glass")
    b.replace(box([mx, oy, mz], [mx + 6, oy, mz]), "stone", "glowstone")
    b.replace(box([mx, oy, mz], [mx + 6, oy, mz]), "dirt", "sand")
    try:
        b.flush()
        got = read_cells(b, [(mx + i, oy, mz) for i in range(7)])
        row = [parse(got[(mx + i, oy, mz)])[0] for i in range(7)]
        check("mask oak_log leaves spruce_log alone", row[0] == "glass" and row[1] == "spruce_log", str(row))
        check("mask stone leaves granite and andesite alone", row[2] == "glowstone" and row[3] == "granite" and row[4] == "andesite", str(row))
        check("mask dirt leaves coarse_dirt alone", row[5] == "sand" and row[6] == "coarse_dirt", str(row))
    except AIBuilderError as e:
        check("replace masks", False, str(e))

    # ---- nothing is built when any block is unknown ----
    nx, nz = ox + 20, oz + 56
    before = read_cells(b, [(nx, oy, nz), (nx + 3, oy, nz)])
    b.fill(box([nx, oy, nz], [nx + 1, oy, nz]), "stone")
    b.fill(box([nx + 2, oy, nz], [nx + 3, oy, nz]), "definitely_not_a_block")
    err = expect_error("unknown block is rejected", b.flush, "unknown block 'definitely_not_a_block'")
    check("rejected batch is kept for a retry", len(b._ops) == 2, f"queue has {len(b._ops)} ops")
    b.clear()
    after = read_cells(b, [(nx, oy, nz), (nx + 3, oy, nz)])
    check("rejected batch placed nothing", before == after, f"{before} -> {after}")
    if legacy:
        e = expect_error("lantern is rejected on 1.8 with a suggestion", lambda: (b.fill(box([nx, oy, nz], [nx, oy, nz]), "lantern"), b.flush()), "glowstone")
        b.clear()
        check("block_info says lantern is not valid", b.block_info("lantern")["valid"] is False)

    # ---- argument and height validation ----
    expect_error("missing radius", lambda: b.call("sphere", center=[nx, oy, nz], pattern="stone"), "missing \"radius\"")
    expect_error("misspelled argument", lambda: b.call("sphere", center=[nx, oy, nz], raduis=3, pattern="stone"), "unknown argument \"raduis\"")
    expect_error("above build height", lambda: b.call("fill", region=box([nx, 999, nz], [nx, 999, nz]), pattern="stone"), "only allows y")
    expect_error("batch error names the op", lambda: b.call("batch", ops=[
        {"op": "fill", "region": box([nx, oy, nz], [nx, oy, nz]), "pattern": "stone"}, {"op": "sphere", "center": [nx, oy, nz], "pattern": "stone"}]), "ops[1] (sphere)")
    info = b.block_info("oak_stairs")
    check("block_info lists stair properties", info.get("valid") and {"facing", "half"} <= set(info.get("properties", {})), str(info))

    # ---- properties the server ignores are reported, not silently dropped ----
    if legacy:
        res = b.call("fill", region=box([nx, oy, nz], [nx, oy, nz]), pattern="oak_stairs[direction=north]")
        check("unknown property warns on 1.8", any("direction" in w for w in res.get("warnings", [])), str(res))

    # ---- rotated paste: every cell of one palette key must end up the same ----
    px, pz = ox + 30, oz + 56
    bp = {"palette": {"S": "oak_stairs[facing=east]", "L": "oak_log[axis=x]"}, "layers": [["SSSS", "LLLL"]]}
    b.paste(bp, [px, oy, pz], rotation=90)
    try:
        b.flush()
        got = read_cells(b, [(px + dx, oy, pz + dz) for dx in range(2) for dz in range(4)])
        stairs = {parse(s)[1].get("facing") for s in got.values() if s.startswith("oak_stairs")}
        logs = {parse(s)[1].get("axis") for s in got.values() if s.startswith("oak_log")}
        check("rotated paste: stairs all face the same way", len(stairs) == 1 and stairs != {"east"}, f"facings {stairs}")
        check("rotated paste: logs all share one axis", logs == {"z"}, f"axes {logs}")
    except AIBuilderError as e:
        check("rotated paste", False, str(e))

    # ---- big shapes are split across ticks without changing the result ----
    cx, cz = ox + 14, oz + 38
    b.cylinder([cx, oy, cz], 10, 20, "stone", hollow=True)
    b.outline(box([ox + 30, oy, oz + 30], [ox + 45, oy + 15, oz + 45]), "stone")
    try:
        res = b.flush()
        layers = [b.read(box([cx - 10, oy + i, cz - 10], [cx + 10, oy + i, cz + 10]))["non_air"] for i in range(20)]
        check("tall cylinder has the same ring on every layer", len(set(layers)) == 1 and layers[0] > 0, str(layers))
        shell = b.read(box([ox + 30, oy, oz + 30], [ox + 45, oy + 15, oz + 45]))["non_air"]
        check("outline is exactly the six faces", shell == 16 ** 3 - 14 ** 3, f"{shell} blocks")
    except AIBuilderError as e:
        check("split shapes", False, str(e))

    # ---- undo ----
    if ctx["caller"]["name"] == "console" and legacy:
        expect_error("undo as console explains itself on WorldEdit 6", b.undo, "player")
    else:
        b.block(ox + 50, oy, oz + 50, "diamond_block")
        b.flush()
        check("undo reverts the last edit", b.undo() == 1 and read_cells(b, [(ox + 50, oy, oz + 50)])[(ox + 50, oy, oz + 50)] == "air")

    b.fill(area, "air")
    b.flush()
    failed = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
