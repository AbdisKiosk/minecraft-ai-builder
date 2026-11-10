"""Clubhouse interior fit-out. Run: python3 builds/gokart/interior.py  (one undo step)
Ground floor: reception, lounge, showroom kart, stair. Upper floor: race bar + lounge facing the track."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build import G, GY, OUT, X0, Z0, Builder, box, kart, vox  # noqa: E402

UP = G + 6                         # upper floor: walking level y71, floor slab y70
IX0, IX1, IZ0, IZ1 = 37, 45, 4, 14  # interior footprint inside the glass


def sofa(v, y, X, z0, z1, facing):
    """Sofa along Z; facing = side the backrest is on (east or west). Arms are open trapdoors."""
    for Z in range(z0, z1 + 1):
        v.set(X, y, Z, f"polished_blackstone_stairs[facing={facing}]")
    v.set(X, y, z0 - 1, "dark_oak_trapdoor[facing=north,open=true,half=bottom]")
    v.set(X, y, z1 + 1, "dark_oak_trapdoor[facing=south,open=true,half=bottom]")


def build(v):
    # clear both floors inside the glass
    v.box(IX0, G, IZ0, IX1, G + 4, IZ1, "air")
    v.box(IX0, UP, IZ0, IX1, UP + 3, IZ1, "air")

    # ---------------- ground floor ----------------
    # dark polished floor, tile border, chequered runner from the south door to reception
    v.box(IX0 - 1, GY, IZ0 - 1, IX1 + 1, GY, IZ1 + 1, "deepslate_tiles")
    v.box(IX0, GY, IZ0, IX1, GY, IZ1, "polished_deepslate")
    for X in range(40, 43):
        for Z in range(7, 16):
            v.set(X, GY, Z, "white_concrete" if (X + Z) % 2 else "black_concrete")

    # reception: L-shaped counter (red front, quartz top), staff chair, storage behind
    for X in range(42, 46):
        v.set(X, G, 6, "red_concrete"); v.set(X, G + 1, 6, "smooth_quartz_slab[type=bottom]")
    v.set(42, G, 5, "red_concrete"); v.set(42, G + 1, 5, "smooth_quartz_slab[type=bottom]")
    v.set(44, G, 5, "dark_oak_stairs[facing=north]")
    v.set(43, G + 2, 6, "black_stained_glass_pane"); v.set(45, G + 2, 6, "potted_bamboo")
    for X in range(43, 46):
        v.set(X, G, 4, "barrel[facing=south]")
    v.set(43, G + 1, 4, "decorated_pot"); v.set(44, G + 1, 4, "gold_block"); v.set(45, G + 1, 4, "potted_fern")
    v.set(44, G + 2, 4, "lightning_rod")                                   # the "trophy"

    # waiting lounge facing the track window
    sofa(v, G, 39, 5, 6, "east")
    v.box(38, G, 5, 38, G, 6, "smooth_quartz_slab[type=bottom]")             # coffee table
    v.set(37, G, 4, "potted_azalea_bush"); v.set(37, G, 7, "potted_azalea_bush")
    v.box(38, GY, 4, 39, GY, 7, "gray_concrete")                             # rug inlay

    # showroom: kart on a lit red pad with a white surround
    v.box(37, GY, 11, 39, GY, 14, "white_concrete")
    v.box(37, GY, 11, 39, GY, 13, "red_concrete")
    kart(v, 37, 11, "red_nether_brick_slab")
    v.set(37, G, 14, "potted_bamboo"); v.set(39, G, 14, "potted_bamboo")

    # stair to the upper floor along the east glass, rising north (Z13 y65 -> Z9 y69 -> floor at Z8)
    for i, Z in enumerate(range(13, 8, -1)):
        y = G + i
        for X in (44, 45):
            if i:
                v.box(X, G, Z, X, y - 1, Z, "smooth_quartz")                 # solid quartz wedge under the flight
            v.set(X, y, Z, "smooth_quartz_stairs[facing=north]")
        v.set(43, y + 1, Z, "glass_pane")                                    # stair rail
    v.box(44, G + 5, 9, 45, G + 5, 12, "air")                                # opening in the floor slab
    v.set(45, G, 14, "potted_fern"); v.set(43, G, 14, "potted_azalea_bush")

    # ---------------- upper floor ----------------
    v.box(IX0, G + 5, IZ0, IX1, G + 5, IZ1, "spruce_planks")                 # warm wood floor
    v.box(44, G + 5, 9, 45, G + 5, 12, "air")
    for X, Z in ((38, 5), (41, 5), (38, 9), (41, 9), (38, 13), (41, 13), (44, 5), (44, 13)):
        v.set(X, G + 5, Z, "sea_lantern")                                    # keep the ceiling lights below
    for Z in range(9, 13):
        v.set(43, UP, Z, "glass_pane")                                       # railing round the stairwell
    v.set(44, UP, 13, "glass_pane"); v.set(45, UP, 13, "glass_pane")

    # door to the balcony
    v.box(36, UP, 8, 36, UP + 1, 10, "air")

    # window bar along the track glass with stools
    for Z in list(range(4, 8)) + list(range(11, 15)):
        v.set(37, UP, Z, "smooth_quartz_slab[type=top]")
        if Z % 2 == 0:
            v.set(38, UP, Z, "dark_oak_fence"); v.set(38, UP + 1, Z, "red_carpet")

    # race bar in the north-east corner
    for X in range(41, 46):
        v.set(X, UP, 6, "red_concrete"); v.set(X, UP + 1, 6, "smooth_quartz_slab[type=bottom]")
    for X in range(41, 44):
        v.set(X, UP, 7, "dark_oak_fence"); v.set(X, UP + 1, 7, "black_carpet")
    for X in range(41, 46):
        v.set(X, UP, 4, "barrel[facing=south]")
    v.set(41, UP + 1, 4, "brewing_stand"); v.set(43, UP + 1, 4, "brewing_stand")
    v.set(42, UP + 1, 4, "potted_fern"); v.set(45, UP + 1, 4, "cauldron")
    v.set(44, UP + 1, 6, "lantern")

    # lounge: two black sofas facing each other around a table on a red rug
    v.box(39, UP, 10, 42, UP, 14, "red_carpet")
    sofa(v, UP, 39, 11, 13, "west")
    sofa(v, UP, 42, 11, 13, "east")
    v.box(40, UP, 11, 41, UP, 13, "smooth_quartz_slab[type=bottom]")
    v.set(40, UP + 1, 12, "potted_flowering_azalea_bush")

    # live-timing screen on the east wall over the stairwell
    v.box(46, UP + 1, 9, 46, UP + 2, 12, "black_concrete")
    for Z, col in ((9, "lime_concrete"), (10, "yellow_concrete"), (11, "light_blue_concrete"), (12, "red_concrete")):
        v.set(46, UP + 2, Z, col)

    # plants in the corners
    v.set(45, UP, 14, "potted_bamboo"); v.set(37, UP, 9, "potted_azalea_bush")


if __name__ == "__main__":
    b = Builder.connect()
    v = vox()
    build(v)
    v.connect()
    b.blocks(v.items())
    print(b.flush())
    b.render(box([X0 + 36, GY, Z0 + 3], [X0 + 46, G + 4, Z0 + 15]), view="iso", save_to=OUT / "interior_ground.png")
    b.render(box([X0 + 36, G + 5, Z0 + 3], [X0 + 46, G + 9, Z0 + 15]), view="iso", save_to=OUT / "interior_upper.png")
