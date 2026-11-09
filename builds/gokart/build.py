"""Go-kart venue rebuild. Run: python3 builds/gokart/build.py <phase|all>
Phases: circuit, clubhouse, infield, paddock, landscape. Each phase = one undo step."""
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "sdk" / "python"))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from aibuilder import Builder, box  # noqa: E402
from layout import BARRIER, D, GY, W, X0, Z0, plan  # noqa: E402

G = GY + 1          # first block above ground
rnd = random.Random(7)
OUT = Path(__file__).resolve().parent


def pick(spec):
    """'80%a,20%b' -> random choice; plain name -> itself."""
    if "%" not in spec:
        return spec
    parts = [p.split("%") for p in spec.split(",")]
    r, acc = rnd.uniform(0, sum(float(w) for w, _ in parts)), 0.0
    for w, n in parts:
        acc += float(w)
        if r <= acc:
            return n
    return parts[-1][1]


NONSOLID = ("slab", "stairs", "carpet", "lantern", "pane", "bars", "fence", "wall", "torch", "rod",
            "pot", "air", "grass", "leaves", "door", "button", "plate", "sign", "banner", "flower",
            "poppy", "dandelion", "tulip", "allium", "bluet", "daisy", "cornflower", "fern", "bush")
CONNECT = ("pane", "iron_bars", "fence")


class Vox:
    def __init__(self):
        self.b = {}

    def set(self, X, y, Z, st):
        self.b[(X, y, Z)] = pick(st)

    def box(self, x0, y0, z0, x1, y1, z1, st):
        for X in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                for Z in range(min(z0, z1), max(z0, z1) + 1):
                    self.set(X, y, Z, st)

    def ring(self, x0, y, z0, x1, z1, st):
        for X in range(x0, x1 + 1):
            self.set(X, y, z0, st); self.set(X, y, z1, st)
        for Z in range(z0, z1 + 1):
            self.set(x0, y, Z, st); self.set(x1, y, Z, st)

    def connect(self):
        def base(s): return s.split("[")[0]
        def fam(s):
            n = base(s)
            if n.endswith("fence"): return "fence"
            if "pane" in n or n == "iron_bars": return "pane"
            return None
        for (X, y, Z), st in list(self.b.items()):
            f = fam(st)
            if not f or "[" in st:
                continue
            dirs = {}
            for name, (dx, dz) in {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}.items():
                n = self.b.get((X + dx, y, Z + dz))
                ok = n is not None and (fam(n) == f or (not any(k in base(n) for k in NONSOLID)))
                dirs[name] = "true" if ok else "false"
            self.b[(X, y, Z)] = f"{st}[" + ",".join(f"{k}={v}" for k, v in dirs.items()) + "]"

    def items(self):
        return [[X0 + X, y, Z0 + Z, s] for (X, y, Z), s in self.b.items()]


def letters(text):
    font = {
        "G": ["###", "#..", "#.#", "#.#", "###"], "O": ["###", "#.#", "#.#", "#.#", "###"],
        "K": ["#.#", "#.#", "##.", "#.#", "#.#"], "A": ["###", "#.#", "###", "#.#", "#.#"],
        "R": ["##.", "#.#", "##.", "#.#", "#.#"], "T": ["###", ".#.", ".#.", ".#.", ".#."],
    }
    rows = ["" for _ in range(5)]
    for i, ch in enumerate(text):
        for r in range(5):
            rows[r] += ("." if i else "") + font[ch][r]
    return rows  # rows[0] = top


def kart(v, X, Z, color, facing="north"):
    """3x3 kart. facing = driving direction (north or west)."""
    slab = f"{color}[type=bottom]"
    wheel = "black_carpet"
    if facing == "north":
        rows = [[wheel, slab, wheel], [slab, "dark_oak_stairs[facing=south]", slab],
                [wheel, "polished_blackstone_slab[type=bottom]", wheel]]
        for dz, row in enumerate(rows):
            for dx, st in enumerate(row):
                v.set(X + dx, G, Z + dz, st)
    else:
        rows = [[wheel, slab, wheel], [slab, "dark_oak_stairs[facing=east]", slab],
                [wheel, "polished_blackstone_slab[type=bottom]", wheel]]
        for dx, row in enumerate(rows):
            for dz, st in enumerate(row):
                v.set(X + dx, G, Z + dz, st)


def tree(v, X, Z, kind="oak", h=5, r=2.4):
    log, leaf = f"{kind}_log", f"{kind}_leaves[persistent=true]"
    if kind == "spruce":
        v.box(X, G, Z, X, G + h, Z, log)
        for i, rad in enumerate([2.3, 1.8, 1.4, 2.0, 1.4, 1.0, 0.8, 0.5]):
            y = G + 2 + i
            for dx in range(-3, 4):
                for dz in range(-3, 4):
                    if dx * dx + dz * dz <= rad * rad and (dx or dz or y > G + h):
                        v.set(X + dx, y, Z + dz, leaf)
        v.set(X, G + 10, Z, leaf)
        return
    v.box(X, G, Z, X, G + h - 1, Z, log)
    cy = G + h
    for dx in range(-3, 4):
        for dy in range(-2, 3):
            for dz in range(-3, 4):
                d = (dx * dx + dz * dz) / (r * r) + (dy * dy) / (1.8 * 1.8)
                if d <= 1 and not (d > 0.75 and rnd.random() < 0.45):
                    if (X + dx, cy + dy, Z + dz) not in v.b or dy > -1:
                        v.set(X + dx, cy + dy, Z + dz, leaf)
    v.box(X, G, Z, X, cy, Z, log)


# --------------------------------------------------------------------------------------------
def phase_circuit(b):
    b.fill(box([X0, G, Z0], [X0 + W - 1, 102, Z0 + 36]), "air")
    v = Vox()
    cells = plan()
    surf = {
        "asphalt": "82%black_concrete,10%polished_blackstone,8%black_concrete_powder",
        "pit": "88%gray_concrete,12%cyan_terracotta",
        "curb_r": "red_concrete", "curb_w": "white_concrete", "edge": "white_concrete",
        "gravel": "85%gravel,15%light_gray_concrete_powder", "grass": "grass_block",
    }
    for Z in range(D + 2):
        for X in range(W):
            v.set(X, GY, Z, surf[cells.get((X, Z), "grass")])

    # start/finish line (checkered) and staggered grid slots on the main straight
    for X in range(26, 30):
        for Z in (15, 16):
            v.set(X, GY, Z, "white_concrete" if (X + Z) % 2 else "black_concrete")
    for Z, xs in ((19, (26, 27)), (22, (28, 29)), (25, (26, 27))):
        for X in xs:
            v.set(X, GY, Z, "white_concrete")
    # pit lane speed-limit line
    for X in (30, 31):
        v.set(X, GY, 20, "white_concrete")

    # barrier: smooth-stone wall + sponsor boards + catch fence of iron bars
    bx0, bx1, bz0, bz1 = BARRIER
    sponsors = ["light_blue_concrete", "yellow_concrete", "lime_concrete", "orange_concrete", "blue_concrete"]
    ring = [(X, bz0) for X in range(bx0, bx1 + 1)] + [(X, bz1) for X in range(bx0, bx1 + 1)] + \
           [(bx0, Z) for Z in range(bz0, bz1 + 1)] + [(bx1, Z) for Z in range(bz0, bz1 + 1)]
    for X, Z in ring:
        if cells[(X, Z)] in ("asphalt", "pit"):
            continue
        along = X if Z in (bz0, bz1) else Z
        seg = (along - 4) // 6
        board = 4 <= along <= 28 and (along - 4) % 6 != 5
        v.set(X, GY, Z, "gray_concrete")
        v.set(X, G, Z, sponsors[(seg + X // 7 + Z // 9) % len(sponsors)] if board else "smooth_stone")
        v.set(X, G + 1, Z, "iron_bars")

    # tyre walls on gravel cells hugging the barrier
    for (X, Z), k in cells.items():
        if k != "gravel":
            continue
        if X in (bx0 + 1, bx1 - 1) or Z in (bz0 + 1, bz1 - 1):
            v.set(X, G, Z, "white_concrete" if (X + Z) % 4 == 0 else "black_concrete")
            v.set(X, G + 1, Z, "black_carpet")
    v.connect()
    b.blocks(v.items())


def phase_clubhouse(b):
    v = Vox()
    x0, x1, z0, z1 = 35, 47, 2, 16          # shell
    cols_x, cols_z = (35, 38, 41, 44, 47), (2, 5, 9, 13, 16)
    # plinth / walkway
    v.box(33, GY, 1, 48, GY, 18, "polished_andesite")
    v.box(x0 + 1, GY, z0 + 1, x1 - 1, GY, z1 - 1, "smooth_stone")
    # ground floor y65-69, floor band y70, upper floor y71-74, roof band y75
    for y in range(G, G + 10):
        for X in range(x0, x1 + 1):
            for Z in range(z0, z1 + 1):
                edge_x, edge_z = X in (x0, x1), Z in (z0, z1)
                if not (edge_x or edge_z):
                    continue
                col = (edge_x and Z in cols_z) or (edge_z and X in cols_x)
                if y in (G + 5, G + 10):
                    continue
                if col:
                    v.set(X, y, Z, "smooth_quartz")
                    continue
                upper = y > G + 5
                face = "W" if X == x0 else "E" if X == x1 else "N" if Z == z0 else "S"
                if face in ("W", "S"):
                    st = "light_gray_stained_glass"
                    if not upper and y == G:
                        st = "gray_concrete"
                    if upper and y == G + 9:
                        st = "red_concrete"
                else:
                    band = (G + 1 <= y <= G + 3) or (G + 6 <= y <= G + 8)
                    st = "light_gray_stained_glass" if band else "white_concrete"
                    if y == G:
                        st = "gray_concrete"
                    if upper and y == G + 9:
                        st = "red_concrete"
                # glass sits one block in, so columns read as fins
                if st == "light_gray_stained_glass":
                    ix = X + (1 if X == x0 else -1 if X == x1 else 0)
                    iz = Z + (1 if Z == z0 else -1 if Z == z1 else 0)
                    v.set(ix, y, iz, st)
                    v.set(X, y, Z, "air")
                else:
                    v.set(X, y, Z, st)
    # entrances: south (paddock) and west (track side)
    for Z_, X_ in ((z1 - 1, 41), (z1, 41)):
        v.box(X_ - 1, G, Z_, X_ + 1, G + 2, Z_, "air")
    v.box(36, G, 8, 36, G + 2, 10, "air")
    # floor slab with projecting band, balcony to the west
    v.box(x0 - 1, G + 5, z0 - 1, x1 + 1, G + 5, z1 + 1, "light_gray_concrete")
    v.box(33, G + 5, z0 - 1, x0 - 1, G + 5, z1 + 1, "light_gray_concrete")
    for Z in range(z0 - 1, z1 + 2):
        v.set(33, G + 6, Z, "glass_pane")
    for X in (33, 34):
        v.set(X, G + 6, z0 - 1, "glass_pane"); v.set(X, G + 6, z1 + 1, "glass_pane")
    v.box(33, G + 4, z0 - 1, 33, G + 4, z1 + 1, "smooth_quartz_slab[type=top]")     # balcony soffit edge
    # roof band (projects 1), deck, parapet, canopy over balcony
    v.box(x0 - 1, G + 10, z0 - 1, x1 + 1, G + 10, z1 + 1, "light_gray_concrete")
    v.box(33, G + 10, z0 - 1, x0 - 2, G + 10, z1 + 1, "smooth_quartz_slab[type=bottom]")
    v.ring(x0 - 1, G + 11, z0 - 1, x1 + 1, z1 + 1, "smooth_stone_slab[type=bottom]")
    # recessed ceiling lights
    for X in (38, 41, 44):
        for Z in (5, 9, 13):
            v.set(X, G + 5, Z, "sea_lantern"); v.set(X, G + 10, Z, "sea_lantern")
    # interior: reception counter, display kart, lounge tables upstairs
    v.box(42, G, 4, 45, G, 4, "white_concrete"); v.box(42, G + 1, 4, 45, G + 1, 4, "smooth_quartz_slab[type=bottom]")
    kart(v, 38, 11, "red_nether_brick_slab")
    for Z in (4, 8, 12):
        for X in (38, 42):
            v.set(X, G + 6, Z, "spruce_fence"); v.set(X, G + 7, Z, "white_carpet")
            v.set(X - 1, G + 6, Z, "spruce_stairs[facing=west]"); v.set(X + 1, G + 6, Z, "spruce_stairs[facing=east]")
    # rooftop plant units
    for zz in (3, 13):
        v.box(45, G + 11, zz, 46, G + 11, zz + 1, "smooth_stone")
        v.box(45, G + 12, zz, 46, G + 12, zz + 1, "iron_trapdoor[half=bottom]")

    # double-sided roof sign: KART + GO on a 3-thick black panel in plane X=40..42, Z 1..17
    sy = G + 12
    v.box(40, sy, 1, 42, sy + 14, 17, "black_concrete")
    for X in (40, 41, 42):                                                      # red frame
        v.ring(X, sy, 1, X, 17, "red_concrete")
        v.box(X, sy, 1, X, sy + 14, 1, "red_concrete"); v.box(X, sy, 17, X, sy + 14, 17, "red_concrete")
        v.box(X, sy + 14, 1, X, sy + 14, 17, "red_concrete")
    for text, top, color in (("KART", sy + 6, "white_concrete"), ("GO", sy + 12, "red_concrete")):
        rows = letters(text)
        wdt = len(rows[0])
        start = 9 - wdt // 2
        for r, row in enumerate(rows):
            for i, c in enumerate(row):
                if c == "#":
                    v.set(42, top - r, 18 - (start + i), color)     # east face reads right-to-left in Z
                    v.set(40, top - r, start + i, color)            # west face
    for Z in (3, 9, 15):
        v.box(41, G + 11, Z, 41, G + 11, Z, "gray_concrete")
    v.connect()
    b.blocks(v.items())


def phase_infield(b):
    v = Vox()
    # race-control tower 5x5 with a 7x7 glass cab
    tx0, tz0 = 12, 13
    v.box(tx0, G, tz0, tx0 + 4, G + 7, tz0 + 4, "white_concrete")
    v.box(tx0 + 1, G, tz0 + 1, tx0 + 3, G + 7, tz0 + 3, "air")
    for X in (tx0, tx0 + 4):
        for Z in (tz0, tz0 + 4):
            v.box(X, G, Z, X, G + 7, Z, "smooth_quartz")
    v.box(tx0 + 2, G, tz0 + 4, tx0 + 2, G + 1, tz0 + 4, "air")                 # door (south)
    for y in (G + 1, G + 2, G + 5, G + 6):
        v.set(tx0 + 2, y, tz0, "light_gray_stained_glass_pane")
        v.set(tx0 + 4, y, tz0 + 2, "light_gray_stained_glass_pane")
        v.set(tx0, y, tz0 + 2, "light_gray_stained_glass_pane")
    v.box(tx0, G + 3, tz0, tx0 + 4, G + 3, tz0 + 4, "red_concrete")           # brand stripe
    v.box(tx0 - 1, G + 8, tz0 - 1, tx0 + 5, G + 8, tz0 + 5, "light_gray_concrete")
    for y in range(G + 9, G + 12):
        v.ring(tx0 - 1, y, tz0 - 1, tx0 + 5, tz0 + 5, "light_blue_stained_glass")
    for X, Z in ((tx0 - 1, tz0 - 1), (tx0 + 5, tz0 - 1), (tx0 - 1, tz0 + 5), (tx0 + 5, tz0 + 5)):
        v.box(X, G + 9, Z, X, G + 11, Z, "white_concrete")
    v.box(tx0 - 2, G + 12, tz0 - 2, tx0 + 6, G + 12, tz0 + 6, "smooth_quartz_slab[type=bottom]")
    v.box(tx0 - 1, G + 12, tz0 - 1, tx0 + 5, G + 12, tz0 + 5, "white_concrete")
    v.box(tx0 + 2, G + 13, tz0 + 2, tx0 + 2, G + 16, tz0 + 2, "iron_bars")      # antenna mast
    v.set(tx0 + 2, G + 17, tz0 + 2, "lightning_rod")
    v.box(tx0 + 3, G + 15, tz0 + 2, tx0 + 4, G + 16, tz0 + 2, "black_wool")      # chequered flag
    v.set(tx0 + 3, G + 16, tz0 + 2, "white_wool"); v.set(tx0 + 4, G + 15, tz0 + 2, "white_wool")
    v.set(tx0 + 2, G + 8, tz0 + 2, "sea_lantern")

    # grandstand facing the main straight
    gz0, gz1 = 12, 19
    for i, X in enumerate((23, 22, 21, 20)):
        top = G + i
        v.box(X, G, gz0, X, top, gz1, "light_gray_concrete")
        for Z in range(gz0 + 1, gz1):
            v.set(X, top + 1, Z, "red_nether_brick_stairs[facing=west]" if Z not in (15, 16) else "air")
        v.set(X, top + 1, 15, "smooth_stone_slab[type=bottom]"); v.set(X, top + 1, 16, "smooth_stone_slab[type=bottom]")
    v.box(19, G, gz0, 19, G + 6, gz1, "white_concrete")
    for Z in (gz0, gz1):                                     # stepped end walls
        for i, X in enumerate((23, 22, 21, 20)):
            v.box(X, G, Z, X, G + i + 2, Z, "white_concrete")
    v.box(19, G + 7, gz0 - 1, 23, G + 7, gz1 + 1, "white_concrete")            # cantilever roof
    v.box(24, G + 7, gz0 - 1, 24, G + 7, gz1 + 1, "smooth_quartz_slab[type=bottom]")
    v.box(19, G + 6, gz0 - 1, 19, G + 6, gz1 + 1, "red_concrete")
    v.box(20, G + 6, gz0, 23, G + 6, gz0, "white_concrete"); v.box(20, G + 6, gz1, 23, G + 6, gz1, "white_concrete")
    for Z in (13, 18):
        v.set(22, G + 6, Z, "lantern[hanging=true]")

    # start/finish gantry over the main straight
    for X in (24, 31):
        v.box(X, G, 15, X, G + 6, 16, "light_gray_concrete")
        v.set(X, G, 15, "gray_concrete"); v.set(X, G, 16, "gray_concrete")
    for X in range(24, 32):
        for y in (G + 5, G + 6):
            v.set(X, y, 15, "white_concrete" if (X + y) % 2 else "black_concrete")
            v.set(X, y, 16, "white_concrete" if (X + y) % 2 == 0 else "black_concrete")
    for X in (26, 27, 28, 29):
        v.set(X, G + 4, 16, "redstone_lamp[lit=true]" if X % 2 else "red_concrete")
    v.connect()
    b.blocks(v.items())


def phase_paddock(b):
    v = Vox()
    # paddock apron
    for X in range(33, 49):
        for Z in range(19, 34):
            v.set(X, GY, Z, "90%gray_concrete,10%light_gray_concrete")
    v.box(33, GY, 17, 48, GY, 18, "polished_andesite")
    for Z in range(20, 33, 4):                                                  # bay lines
        v.box(34, GY, Z, 37, GY, Z, "white_concrete")
    v.box(38, GY, 20, 38, GY, 32, "white_concrete")
    # kart garage canopy along the east edge
    cx0, cx1 = 43, 48
    v.box(cx0, GY, 20, cx1, GY, 32, "smooth_stone")
    v.box(cx1, G, 20, cx1, G + 3, 32, "light_gray_concrete")
    for Z in (20, 24, 28, 32):
        v.box(cx0, G, Z, cx0, G + 3, Z, "smooth_quartz")
        v.box(cx0 + 1, G, Z, cx1 - 1, G + 2, Z, "white_concrete")
    v.box(cx0 - 1, G + 4, 19, cx1, G + 4, 33, "light_gray_concrete")
    v.box(cx0 - 1, G + 4, 19, cx0 - 1, G + 4, 33, "red_concrete")
    v.box(cx0 - 2, G + 4, 19, cx0 - 2, G + 4, 33, "smooth_quartz_slab[type=bottom]")
    for Z in (22, 26, 30):
        v.set(45, G + 4, Z, "sea_lantern")
    colors = ["red_nether_brick_slab", "smooth_quartz_slab", "warped_slab", "prismarine_slab", "purpur_slab", "crimson_slab"]
    for i, Z in enumerate((21, 25, 29)):
        kart(v, 44, Z, colors[i], facing="west")
    kart(v, 34, 21, colors[3], facing="west"); kart(v, 34, 29, colors[4], facing="west")
    # tool chests / tyres in the garages
    for Z in (23, 27, 31):
        v.set(47, G, Z, "barrel[facing=up]"); v.set(47, G + 1, Z, "black_carpet")
    for Z in (21, 25, 29):
        v.set(47, G, Z, "black_concrete"); v.set(47, G + 1, Z, "black_carpet")
    v.connect()
    b.blocks(v.items())


def phase_landscape(b):
    v = Vox()
    # perimeter: andesite curb + clipped hedge, gate to the south
    for X in range(W):
        for Z in (0, 34):
            if Z == 34 and 39 <= X <= 41:
                continue
            v.set(X, G, Z, "polished_andesite"); v.set(X, G + 1, Z, "spruce_leaves[persistent=true]")
    for Z in range(D):
        for X in (0, 49):
            v.set(X, G, Z, "polished_andesite"); v.set(X, G + 1, Z, "spruce_leaves[persistent=true]")
    for X in (38, 42):                                                          # gate pillars
        v.box(X, G, 34, X, G + 3, 34, "smooth_quartz"); v.set(X, G + 4, 34, "lantern")
    v.box(39, GY, 33, 41, GY, 36, "polished_andesite")
    # plaza between clubhouse and paddock: planters
    for X in (34, 47):
        v.box(X, G, 17, X, G, 18, "spruce_planks")
    for X, Z in ((33, 17), (48, 17)):
        v.set(X, G, Z, "potted_azalea_bush")
    # trees: infield, beside the clubhouse, and along the south boundary
    for X, Z, k in ((15, 21, "birch"), (10, 11, "oak"), (10, 18, "oak")):
        tree(v, X, Z, k, h=4, r=2.0)
    tree(v, 48, 1, "spruce")
    for X in (4, 12, 20, 28):
        tree(v, X, 36, "oak" if X % 8 == 4 else "birch", h=4, r=2.2)
    for X, Z in ((46, 36), (34, 36)):
        tree(v, X, Z, "spruce")
    # floodlight masts at the circuit corners
    for X, Z in ((3, 3), (31, 3), (3, 31), (31, 31)):
        v.box(X, G, Z, X, G + 8, Z, "iron_bars")
        v.box(X, G + 9, Z, X, G + 9, Z, "sea_lantern")
        v.set(X, G + 10, Z, "smooth_stone_slab[type=bottom]")
    # lamp posts along the walkway and paddock
    for X, Z in ((33, 2), (33, 10), (33, 18), (40, 33), (36, 19)):
        v.box(X, G, Z, X, G + 2, Z, "iron_bars"); v.set(X, G + 3, Z, "lantern")
    # karts on the grid and one on the back straight
    kart(v, 26, 19, "red_nether_brick_slab"); kart(v, 27, 22, "warped_slab")
    kart(v, 14, 5, "smooth_quartz_slab", facing="west")
    # flowers along the grandstand front
    for Z in range(12, 20):
        v.set(24, G, Z, rnd.choice(["red_tulip", "white_tulip", "oxeye_daisy", "cornflower", "short_grass"]))
    v.connect()
    b.blocks(v.items())


def phase_details(b):
    v = Vox()
    # solar arrays on the garage canopy and the clubhouse roof
    for Z in range(20, 33):
        if Z % 4 != 0:
            v.box(44, G + 5, Z, 47, G + 5, Z, "daylight_detector")
    for X0_, X1_ in ((35, 38), (44, 47)):
        for Z in range(3, 16):
            if Z % 3 != 0 and not (X0_ == 44 and Z in (3, 4, 13, 14)):
                v.box(X0_, G + 11, Z, X1_ if X0_ == 44 else 38, G + 11, Z, "daylight_detector")
    for X, Z in ((45, 3), (46, 3), (45, 4), (46, 4), (45, 13), (46, 13), (45, 14), (46, 14)):
        v.b.pop((X, G + 11, Z), None)
    # cantilevered entrance canopy on the paddock side
    v.box(39, G + 3, 17, 43, G + 3, 19, "red_concrete")
    v.box(38, G + 3, 17, 38, G + 3, 19, "smooth_quartz_slab[type=top]")
    v.box(44, G + 3, 17, 44, G + 3, 19, "smooth_quartz_slab[type=top]")
    v.box(39, G + 3, 20, 43, G + 3, 20, "smooth_quartz_slab[type=top]")
    v.set(41, G + 2, 19, "lantern[hanging=true]")
    # flag poles flanking the paddock gate
    for X, wool in ((36, "red_wool"), (44, "white_wool")):
        v.box(X, G, 33, X, G + 6, 33, "iron_bars")
        v.box(X - (1 if X < 40 else -1), G + 5, 33, X - (2 if X < 40 else -2), G + 6, 33, wool)
    # benches facing the track along the walkway
    for Z in (5, 13):
        v.set(34, G, Z, "spruce_stairs[facing=east]")
        v.set(34, G, Z + 1, "spruce_stairs[facing=east]")
    v.connect()
    b.blocks(v.items())


PHASES = {"circuit": phase_circuit, "clubhouse": phase_clubhouse, "infield": phase_infield,
          "paddock": phase_paddock, "landscape": phase_landscape, "details": phase_details}

if __name__ == "__main__":
    b = Builder.connect()
    for name in (PHASES if sys.argv[1:] == ["all"] else sys.argv[1:]):
        PHASES[name](b)
        print(name, b.flush())
    area = box([X0, GY - 1, Z0], [X0 + W - 1, GY + 28, Z0 + 38])
    b.render(area, save_to=OUT / "render.png")
    print("rendered", OUT / "render.png")
