"""Voxel buffer: collect blocks in the script, send them in one go.

    v = Vox(origin=(100, 64, -20))            # everything below is relative to this corner
    v.box(0, 0, 0, 8, 4, 6, "70%stone_bricks,30%andesite")
    v.box(1, 0, 1, 7, 4, 5, "air")
    v.ring(0, 5, 0, 8, 6, "oak_fence")
    v.roof_gable(0, 0, 8, 6, 6, "spruce_stairs", gable="spruce_planks")
    v.connect()                               # fences, panes and stair corners join their neighbours
    b.blocks(v.items())

Later writes replace earlier ones, so carve and decorate in any order without sending a block twice.
"""

from __future__ import annotations

import math
import random

__all__ = ["Vox", "pick", "transform_state"]

# Blocks a fence or pane does not attach to.
NONSOLID = ("slab", "stairs", "carpet", "lantern", "pane", "bars", "fence", "wall", "torch", "rod",
            "pot", "air", "grass", "leaves", "door", "button", "plate", "sign", "banner", "flower",
            "poppy", "dandelion", "tulip", "allium", "bluet", "daisy", "cornflower", "fern", "bush")
_SIDES = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
_CW = ["north", "east", "south", "west"]  # clockwise seen from above
_STAIR_FLIP = {"inner_left": "inner_right", "inner_right": "inner_left",
               "outer_left": "outer_right", "outer_right": "outer_left"}


def pick(spec: str, rng: random.Random) -> str:
    """'80%a,20%b' -> one of them by weight; a plain block name -> itself."""
    if "%" not in spec:
        return spec
    parts = [p.split("%") for p in spec.split(",")]
    r, acc = rng.uniform(0, sum(float(w) for w, _ in parts)), 0.0
    for w, name in parts:
        acc += float(w)
        if r <= acc:
            return name
    return parts[-1][1]


def _family(state: str) -> str | None:
    name = state.split("[")[0]
    if name.endswith("fence"):
        return "fence"
    if "pane" in name or name == "iron_bars":
        return "pane"
    return None


def _turn(d: str, turns: int) -> str:
    return _CW[(_CW.index(d) + turns) % 4] if d in _CW else d


def _props(state: str) -> tuple[str, list[list[str]]]:
    if "[" not in state:
        return state, []
    name, rest = state.split("[", 1)
    return name, [p.split("=", 1) for p in rest.rstrip("]").split(",") if "=" in p]


def _rail(shape: str, m: dict) -> str:
    """Rail shape with its direction words mapped by m, in Minecraft's spelling (north/south first)."""
    w = [m.get(d, d) for d in shape.split("_")]
    if w[0] == "ascending":
        return "_".join(w)
    return "_".join(sorted(w, key=["north", "south", "east", "west"].index))


def _move_sides(props, m: dict) -> None:
    """Side properties (north=..., east=...) follow the side map m: values move between the keys when all
    target keys are present (keeps the order), otherwise the keys are renamed in place."""
    sides = {k: v for k, v in props if k in m}
    moved = {m[k]: v for k, v in sides.items()}
    for p in props:
        if p[0] in sides:
            if set(moved) == set(sides):
                p[1] = moved[p[0]]
            else:
                p[0] = m[p[0]]


# The Java side has the same rules in plugin/common/.../StateFlip.java: the two must stay in sync.
def transform_state(state: str, turns: int = 0, flip: str | None = None) -> str:
    """A block state turned by quarter turns clockwise seen from above, after an optional mirror.

    flip="x" mirrors east<->west, flip="z" north<->south. Handles facing, axis, side properties
    (fences, panes, walls), rotation (signs, banners), stair shape, door hinge, chest type, rail shape.
    """
    name, props = _props(state)
    if not props:
        return state
    if flip:
        a, b = ("east", "west") if flip == "x" else ("north", "south")
        swap = {a: b, b: a}
        for p in props:
            k, v = p
            if k == "facing":
                p[1] = swap.get(v, v)
            elif k == "shape" and "stairs" in name:
                p[1] = _STAIR_FLIP.get(v, v)
            elif k == "shape" and "rail" in name:
                p[1] = _rail(v, swap)
            elif k in ("hinge", "type"):
                p[1] = {"left": "right", "right": "left"}.get(v, v)
            elif k == "rotation":
                p[1] = str(((16 if flip == "x" else 24) - int(v)) % 16)
        _move_sides(props, swap)
    turns %= 4
    if turns:
        for p in props:
            k, v = p
            if k == "facing":
                p[1] = _turn(v, turns)
            elif k == "shape" and "rail" in name:
                p[1] = _rail(v, {s: _turn(s, turns) for s in _CW})
            elif k == "axis" and turns % 2:
                p[1] = {"x": "z", "z": "x"}.get(v, v)
            elif k == "rotation":
                p[1] = str((int(v) + 4 * turns) % 16)
        _move_sides(props, {s: _turn(s, turns) for s in _CW})
    return name + "[" + ",".join(f"{k}={v}" for k, v in props) + "]"


def _stair(state: str | None):
    """(facing, half) of a stair with an explicit facing, else None."""
    if not state:
        return None
    name, props = _props(state)
    p = dict(props)
    return (p["facing"], p.get("half", "bottom")) if name.endswith("stairs") and p.get("facing") in _CW else None


class Vox:
    """Blocks keyed by (x, y, z), relative to `origin`. `b` is the dict itself, for anything not covered here.

    Weighted patterns are resolved per block when written, with `rng` (or a fixed `seed`), so a script
    gives the same build every run.
    """

    def __init__(self, origin=(0, 0, 0), seed: int = 0, rng: random.Random | None = None):
        self.b: dict[tuple[int, int, int], str] = {}
        self.origin = tuple(origin)
        self.rng = rng or random.Random(seed)

    def set(self, x, y, z, state: str) -> None:
        self.b[(x, y, z)] = pick(state, self.rng)

    def box(self, x0, y0, z0, x1, y1, z1, state: str) -> None:
        """Solid box between two corners (any order)."""
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                for z in range(min(z0, z1), max(z0, z1) + 1):
                    self.set(x, y, z, state)

    def ring(self, x0, y, z0, x1, z1, state: str) -> None:
        """Rectangle outline on one level."""
        for x in range(x0, x1 + 1):
            self.set(x, y, z0, state)
            self.set(x, y, z1, state)
        for z in range(z0, z1 + 1):
            self.set(x0, y, z, state)
            self.set(x1, y, z, state)

    def connect(self) -> None:
        """Gives fences, panes and iron bars their north/south/east/west states from the blocks next to them,
        and stairs with a facing their corner `shape` from the stairs next to them (vanilla rule).

        WorldEdit places exactly the state it is given, so without this they stand as single posts.
        Fences and panes that already carry states, and stairs with a shape, are left as written.
        Call it last, before items().
        """
        orig = dict(self.b)
        for (x, y, z), state in orig.items():
            me = _stair(state)
            if me and "shape=" not in state:
                shape = self._stair_shape(orig, x, y, z, *me)
                if shape:
                    self.b[(x, y, z)] = state[:-1] + f",shape={shape}]"
        for (x, y, z), state in list(self.b.items()):
            family = _family(state)
            if not family or "[" in state:
                continue
            sides = {}
            for side, (dx, dz) in _SIDES.items():
                other = self.b.get((x + dx, y, z + dz))
                joins = other is not None and (
                    _family(other) == family or not any(k in other.split("[")[0] for k in NONSOLID))
                sides[side] = "true" if joins else "false"
            self.b[(x, y, z)] = f"{state}[" + ",".join(f"{k}={v}" for k, v in sides.items()) + "]"

    @staticmethod
    def _stair_shape(b, x, y, z, f, half):
        def at(d, sign):
            dx, dz = _SIDES[d]
            return _stair(b.get((x + sign * dx, y, z + sign * dz)))

        for sign, kind in ((1, "outer"), (-1, "inner")):  # stair in front, then behind
            other = at(f, sign)
            if other and other[1] == half and _CW.index(other[0]) % 2 != _CW.index(f) % 2:
                g = other[0]
                if at(g, -sign) != (f, half):  # vanilla canTakeShape
                    return f"{kind}_{'left' if g == _turn(f, -1) else 'right'}"
        return None

    def mirror(self, axis: str, at: float, copy: bool = True) -> None:
        """Reflect every block across the plane x=at (axis "x") or z=at (axis "z"), flipping states.

        copy=True keeps the originals: build one half, mirror it, and the reflection overwrites whatever
        is on the other side. `at` may be a half-integer (4.5) for an even width.
        """
        out = dict(self.b) if copy else {}
        for (x, y, z), s in self.b.items():
            p = (round(2 * at - x), y, z) if axis == "x" else (x, y, round(2 * at - z))
            out[p] = transform_state(s, flip=axis)
        self.b = out

    def rotate(self, turns: int, about=(0, 0)) -> None:
        """Rotate every block and its state by quarter turns clockwise seen from above, around the
        vertical line through about=(x, z)."""
        ax, az = about
        out = {}
        for (x, y, z), s in self.b.items():
            for _ in range(turns % 4):
                x, z = ax - (z - az), az + (x - ax)
            out[(round(x), y, round(z))] = transform_state(s, turns)
        self.b = out

    def _stairs(self, x, y, z, stairs: str, facing: str) -> None:
        self.b[(x, y, z)] = f"{pick(stairs, self.rng)}[facing={facing}]"

    def roof_gable(self, x0, z0, x1, z1, y, stairs: str, ridge: str | None = None, along: str = "x",
                   overhang: int = 1, gable: str | None = None) -> None:
        """Gable roof over the walls (x0,z0)-(x1,z1) with its ridge along x or z; the eaves course sits at y.

        Slopes rise one per step toward the ridge and stick out `overhang` on all sides. An odd span ends in a
        ridge row of `ridge` (default: the slab of `stairs`). `gable` fills the end walls below the roof.
        """
        x0, x1, z0, z1 = min(x0, x1), max(x0, x1), min(z0, z1), max(z0, z1)
        ridge = ridge or stairs.replace("_stairs", "_slab")
        if along == "x":
            u0, u1, w0, w1, lo, hi, pos = x0, x1, z0, z1, "south", "north", lambda u, w: (u, w)
        else:
            u0, u1, w0, w1, lo, hi, pos = z0, z1, x0, x1, "east", "west", lambda u, w: (w, u)
        a, b = w0 - overhang, w1 + overhang
        for u in range(u0 - overhang, u1 + overhang + 1):
            for i in range((b - a) // 2 + 1):
                (xa, za), (xb, zb) = pos(u, a + i), pos(u, b - i)
                if a + i == b - i:
                    self.set(xa, y + i, za, ridge)
                else:
                    self._stairs(xa, y + i, za, stairs, lo)
                    self._stairs(xb, y + i, zb, stairs, hi)
        for u in (u0, u1) if gable else ():
            for w in range(w0, w1 + 1):
                gx, gz = pos(u, w)
                for h in range(y, y + min(w - a, b - w)):
                    self.set(gx, h, gz, gable)

    def roof_hip(self, x0, z0, x1, z1, y, stairs: str, ridge: str | None = None, overhang: int = 1) -> None:
        """Hip roof over the walls (x0,z0)-(x1,z1): four slopes rising inward one per ring from height y,
        `overhang` beyond the walls, closed by a ridge (default: the slab of `stairs`).
        Call connect() afterwards so the corner stairs become outer corners."""
        a, b, c, d = min(x0, x1) - overhang, max(x0, x1) + overhang, min(z0, z1) - overhang, max(z0, z1) + overhang
        ridge = ridge or stairs.replace("_stairs", "_slab")
        while a <= b and c <= d:
            if a == b or c == d:
                self.box(a, y, c, b, y, d, ridge)
                return
            for x in range(a, b + 1):
                self._stairs(x, y, c, stairs, "south")
                self._stairs(x, y, d, stairs, "north")
            for z in range(c + 1, d):
                self._stairs(a, y, z, stairs, "east")
                self._stairs(b, y, z, stairs, "west")
            a, b, c, d, y = a + 1, b - 1, c + 1, d - 1, y + 1

    def arch(self, x0, x1, y, z, rise, state: str, along: str = "x") -> None:
        """Half-ellipse arc over x0..x1 at the given z (along="z": over z0..z1, and `z` is the fixed x),
        springing at height y at both ends and peaking at y + rise. Steps are filled: no vertical gaps."""
        a, b = min(x0, x1), max(x0, x1)
        c, hw = (a + b) / 2, (b - a) / 2
        h = [y + (round(rise * math.sqrt(max(0.0, 1 - ((u - c) / hw) ** 2))) if hw else 0) for u in range(a, b + 1)]
        for i, u in enumerate(range(a, b + 1)):
            low = min([h[i]] + [h[j] + 1 for j in (i - 1, i + 1) if 0 <= j < len(h)])
            for yy in range(low, h[i] + 1):
                self.set(*((u, yy, z) if along == "x" else (z, yy, u)), state)

    def items(self) -> list[list]:
        """[x, y, z, state] in world coordinates, ready for Builder.blocks()."""
        ox, oy, oz = self.origin
        return [[ox + x, oy + y, oz + z, s] for (x, y, z), s in self.b.items()]
