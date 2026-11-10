"""Voxel buffer: collect blocks in the script, send them in one go.

    v = Vox(origin=(100, 64, -20))            # everything below is relative to this corner
    v.box(0, 0, 0, 8, 4, 6, "70%stone_bricks,30%andesite")
    v.box(1, 0, 1, 7, 4, 5, "air")
    v.ring(0, 5, 0, 8, 6, "oak_fence")
    v.connect()                               # fences and panes join their neighbours
    b.blocks(v.items())

Later writes replace earlier ones, so carve and decorate in any order without sending a block twice.
"""

from __future__ import annotations

import random

__all__ = ["Vox", "pick"]

# Blocks a fence or pane does not attach to.
NONSOLID = ("slab", "stairs", "carpet", "lantern", "pane", "bars", "fence", "wall", "torch", "rod",
            "pot", "air", "grass", "leaves", "door", "button", "plate", "sign", "banner", "flower",
            "poppy", "dandelion", "tulip", "allium", "bluet", "daisy", "cornflower", "fern", "bush")
_SIDES = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}


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
        """Gives fences, panes and iron bars their north/south/east/west states from the blocks next to them.

        WorldEdit places exactly the state it is given, so without this they stand as single posts.
        Blocks that already carry states are left as written. Call it last, before items().
        """
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

    def items(self) -> list[list]:
        """[x, y, z, state] in world coordinates, ready for Builder.blocks()."""
        ox, oy, oz = self.origin
        return [[ox + x, oy + y, oz + z, s] for (x, y, z), s in self.b.items()]
