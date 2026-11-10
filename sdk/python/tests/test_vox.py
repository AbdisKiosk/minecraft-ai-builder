"""Unit tests for aibuilder.vox. Run: python3 -m unittest discover -s sdk/python/tests"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from aibuilder import Vox, transform_state  # noqa: E402


def facing(state):
    return dict(p.split("=") for p in state.split("[")[1].rstrip("]").split(","))["facing"]


def prop(state, key):
    return dict(p.split("=") for p in state.split("[")[1].rstrip("]").split(",")).get(key)


class GableRoof(unittest.TestCase):
    def test_odd_span_along_x(self):
        v = Vox()
        v.roof_gable(0, 0, 6, 4, 10, "spruce_stairs")  # z span 0..4, with overhang -1..5 = 7 wide
        self.assertEqual({x for x, _, _ in v.b}, set(range(-1, 8)))
        self.assertEqual({z for _, _, z in v.b}, set(range(-1, 6)))
        for x in range(-1, 8):
            for i in range(3):
                self.assertEqual(v.b[(x, 10 + i, -1 + i)], "spruce_stairs[facing=south]")
                self.assertEqual(v.b[(x, 10 + i, 5 - i)], "spruce_stairs[facing=north]")
            self.assertEqual(v.b[(x, 13, 2)], "spruce_slab")
        self.assertEqual(len(v.b), 9 * 7)

    def test_even_span_along_z(self):
        v = Vox()
        v.roof_gable(0, 0, 3, 5, 0, "oak_stairs", ridge="oak_planks", along="z", overhang=0)
        self.assertEqual(len(v.b), 4 * 6)  # two stairs meet at the top, no ridge row
        self.assertNotIn("oak_planks", v.b.values())
        for z in range(6):
            self.assertEqual(v.b[(0, 0, z)], "oak_stairs[facing=east]")
            self.assertEqual(v.b[(1, 1, z)], "oak_stairs[facing=east]")
            self.assertEqual(v.b[(2, 1, z)], "oak_stairs[facing=west]")
            self.assertEqual(v.b[(3, 0, z)], "oak_stairs[facing=west]")

    def test_odd_span_along_z_ridge_and_weighted(self):
        v = Vox(seed=3)
        v.roof_gable(0, 0, 4, 2, 0, "50%oak_stairs,50%birch_stairs", along="z")
        self.assertIn(v.b[(2, 3, 0)], ("oak_slab", "birch_slab"))
        self.assertTrue(v.b[(-1, 0, -1)].endswith("_stairs[facing=east]"))

    def test_gable_infill_only_on_wall_lines(self):
        v = Vox()
        v.roof_gable(0, 0, 6, 4, 10, "spruce_stairs", gable="oak_planks")
        planks = {p for p, s in v.b.items() if s == "oak_planks"}
        self.assertEqual({x for x, _, _ in planks}, {0, 6})
        self.assertEqual({z for _, _, z in planks}, set(range(0, 5)))
        # under the roof at z=0 (roof at 11), z=1 (12), z=2 (ridge at 13)
        self.assertEqual(sorted((y, z) for x, y, z in planks if x == 0),
                         [(10, 0), (10, 1), (10, 2), (10, 3), (10, 4), (11, 1), (11, 2), (11, 3), (12, 2)])


class HipRoof(unittest.TestCase):
    def test_slopes_face_inward_and_corners_connect(self):
        v = Vox()
        v.roof_hip(0, 0, 4, 4, 0, "oak_stairs")  # 7x7 with overhang: rings at y 0..2, slab at y 3
        for i in range(3):
            a, b = -1 + i, 5 - i
            for t in range(a + 1, b):
                self.assertEqual(facing(v.b[(t, i, a)]), "south")
                self.assertEqual(facing(v.b[(t, i, b)]), "north")
                self.assertEqual(facing(v.b[(a, i, t)]), "east")
                self.assertEqual(facing(v.b[(b, i, t)]), "west")
        self.assertEqual(v.b[(2, 3, 2)], "oak_slab")
        v.connect()
        # NW corner (-1,0,-1) faces south. In front (south) is (-1,0,0) facing east = g, perpendicular.
        # canTakeShape: p - g = (-2,0,-1) is empty, so outer; g == ccw(south) == east -> outer_left.
        self.assertEqual(v.b[(-1, 0, -1)], "oak_stairs[facing=south,shape=outer_left]")
        self.assertEqual(v.b[(5, 0, -1)], "oak_stairs[facing=south,shape=outer_right]")
        self.assertEqual(v.b[(-1, 0, 5)], "oak_stairs[facing=north,shape=outer_right]")
        self.assertEqual(v.b[(5, 0, 5)], "oak_stairs[facing=north,shape=outer_left]")
        self.assertEqual(v.b[(2, 0, -1)], "oak_stairs[facing=south]")


class StairShapes(unittest.TestCase):
    def shape(self, blocks):
        v = Vox()
        for p, s in blocks.items():
            v.set(*p, s)
        v.connect()
        return prop(v.b[(0, 0, 0)], "shape")

    def test_outer(self):
        self.assertEqual(self.shape({(0, 0, 0): "s[facing=north]", (0, 0, -1): "s[facing=west]"}), None)  # not a stair
        me = "oak_stairs[facing=north]"
        self.assertEqual(self.shape({(0, 0, 0): me, (0, 0, -1): "oak_stairs[facing=west]"}), "outer_left")
        self.assertEqual(self.shape({(0, 0, 0): me, (0, 0, -1): "oak_stairs[facing=east]"}), "outer_right")
        self.assertEqual(self.shape({(0, 0, 0): me, (0, 0, -1): "oak_stairs[facing=east,half=top]"}), None)

    def test_inner(self):
        me = "oak_stairs[facing=north,half=top]"
        self.assertEqual(self.shape({(0, 0, 0): me, (0, 0, 1): "stone_stairs[facing=west,half=top]"}), "inner_left")
        self.assertEqual(self.shape({(0, 0, 0): me, (0, 0, 1): "oak_stairs[facing=east,half=top]"}), "inner_right")

    def test_can_take_shape(self):
        me = "oak_stairs[facing=north]"
        # outer blocked: p - g (g=west) = (1,0,0) is a stair with the same facing and half
        self.assertEqual(self.shape({(0, 0, 0): me, (0, 0, -1): "oak_stairs[facing=west]",
                                     (1, 0, 0): "oak_stairs[facing=north]"}), None)
        # ... then falls through to the inner check
        self.assertEqual(self.shape({(0, 0, 0): me, (0, 0, -1): "oak_stairs[facing=west]",
                                     (1, 0, 0): "oak_stairs[facing=north]",
                                     (0, 0, 1): "oak_stairs[facing=west]"}), "inner_left")
        # inner blocked: p + g (g=west) = (-1,0,0) is a stair with the same facing and half
        self.assertEqual(self.shape({(0, 0, 0): me, (0, 0, 1): "oak_stairs[facing=west]",
                                     (-1, 0, 0): "oak_stairs[facing=north]"}), None)

    def test_explicit_shape_kept(self):
        me = "oak_stairs[facing=north,shape=straight]"
        self.assertEqual(self.shape({(0, 0, 0): me, (0, 0, -1): "oak_stairs[facing=west]"}), "straight")


class TransformState(unittest.TestCase):
    STATES = ["oak_stairs[facing=north,half=top,shape=inner_left]", "oak_log[axis=x]", "stone",
              "oak_fence[north=true,east=false,south=false,west=true,waterlogged=false]",
              "oak_sign[rotation=3]", "oak_door[facing=east,half=lower,hinge=left,open=false]",
              "chest[facing=south,type=right]", "rail[shape=ascending_east]", "cobblestone_wall[north=low,east=tall]"]

    def test_four_turns_identity(self):
        for s in self.STATES:
            self.assertEqual(transform_state(s, 4), s)
            t = s
            for _ in range(4):
                t = transform_state(t, 1)
            self.assertEqual(t, s)

    def test_rail_turns(self):
        shapes = ["north_south", "east_west", "ascending_north", "ascending_east", "ascending_south",
                  "ascending_west", "south_east", "south_west", "north_west", "north_east"]
        for sh in shapes:
            t = f"rail[shape={sh}]"
            for _ in range(4):
                t = transform_state(t, 1)
                self.assertIn(t[11:-1], shapes)
            self.assertEqual(t, f"rail[shape={sh}]")
        self.assertEqual(transform_state("rail[shape=south_east]", 1), "rail[shape=south_west]")
        self.assertEqual(transform_state("rail[shape=north_south]", 1), "rail[shape=east_west]")
        self.assertEqual(transform_state("rail[shape=north_south]", 2), "rail[shape=north_south]")
        self.assertEqual(transform_state("powered_rail[shape=ascending_east,powered=true]", 1),
                         "powered_rail[shape=ascending_south,powered=true]")

    def test_flip_twice_identity(self):
        for s in self.STATES:
            for axis in ("x", "z"):
                self.assertEqual(transform_state(transform_state(s, flip=axis), flip=axis), s)

    def test_turns(self):
        self.assertEqual(transform_state("oak_stairs[facing=north,half=top]", 1), "oak_stairs[facing=east,half=top]")
        self.assertEqual(transform_state("oak_log[axis=x]", 1), "oak_log[axis=z]")
        self.assertEqual(transform_state("oak_log[axis=x]", 2), "oak_log[axis=x]")
        self.assertEqual(transform_state("oak_fence[north=true,east=false,south=false,west=false]", 1),
                         "oak_fence[north=false,east=true,south=false,west=false]")
        self.assertEqual(transform_state("oak_fence[north=true,east=false,south=false,west=false]", -1),
                         "oak_fence[north=false,east=false,south=false,west=true]")
        for r in range(16):
            self.assertEqual(transform_state(f"oak_sign[rotation={r}]", 1), f"oak_sign[rotation={(r + 4) % 16}]")

    def test_flips(self):
        self.assertEqual(transform_state("oak_stairs[facing=east,shape=outer_left]", flip="x"),
                         "oak_stairs[facing=west,shape=outer_right]")
        self.assertEqual(transform_state("oak_stairs[facing=east,shape=inner_right]", flip="z"),
                         "oak_stairs[facing=east,shape=inner_left]")
        self.assertEqual(transform_state("oak_door[facing=north,hinge=left]", flip="x"), "oak_door[facing=north,hinge=right]")
        self.assertEqual(transform_state("chest[facing=north,type=left]", flip="z"), "chest[facing=south,type=right]")
        self.assertEqual(transform_state("glass_pane[east=true,west=false]", flip="x"), "glass_pane[east=false,west=true]")
        self.assertEqual(transform_state("oak_fence[east=true,north=true]", flip="x"), "oak_fence[west=true,north=true]")
        self.assertEqual(transform_state("cobblestone_wall[north=low,east=tall]", 1),
                         "cobblestone_wall[east=low,south=tall]")
        self.assertEqual(transform_state("oak_sign[rotation=4]", flip="x"), "oak_sign[rotation=12]")
        self.assertEqual(transform_state("oak_sign[rotation=0]", flip="z"), "oak_sign[rotation=8]")
        self.assertEqual(transform_state("oak_sign[rotation=4]", flip="z"), "oak_sign[rotation=4]")
        self.assertEqual(transform_state("rail[shape=ascending_east]", flip="x"), "rail[shape=ascending_west]")
        self.assertEqual(transform_state("rail[shape=north_east]", flip="x"), "rail[shape=north_west]")
        self.assertEqual(transform_state("rail[shape=south_west]", flip="z"), "rail[shape=north_west]")
        self.assertEqual(transform_state("rail[shape=east_west]", flip="x"), "rail[shape=east_west]")
        self.assertEqual(transform_state("oak_slab[type=top]", flip="x"), "oak_slab[type=top]")
        self.assertEqual(transform_state("stone", 1, "x"), "stone")


class MirrorRotate(unittest.TestCase):
    def test_mirror_copy(self):
        v = Vox()
        v.set(1, 0, 0, "oak_stairs[facing=east]")
        v.set(5, 0, 0, "stone")  # reflected to x=3
        v.set(1, 0, 2, "stone")
        v.set(7, 0, 2, "dirt")  # overwritten
        v.mirror("x", 4)
        self.assertEqual(v.b[(1, 0, 0)], "oak_stairs[facing=east]")
        self.assertEqual(v.b[(7, 0, 0)], "oak_stairs[facing=west]")
        self.assertEqual(v.b[(7, 0, 2)], "stone")
        self.assertEqual(v.b[(3, 0, 0)], "stone")

    def test_mirror_move_and_half(self):
        v = Vox()
        v.set(0, 1, 0, "oak_stairs[facing=north]")
        v.mirror("z", 4.5, copy=False)
        self.assertEqual(v.b, {(0, 1, 9): "oak_stairs[facing=south]"})

    def test_rotate(self):
        v = Vox(seed=1)
        v.box(0, 0, 0, 3, 2, 1, "50%stone,50%andesite")
        v.set(2, 3, 0, "oak_stairs[facing=north,half=top]")
        v.set(0, 3, 1, "oak_fence[north=true,east=false,south=false,west=false]")
        before = dict(v.b)
        v.rotate(1, about=(1, 1))
        self.assertEqual(v.b[(2, 3, 2)], "oak_stairs[facing=east,half=top]")  # (2,0) is NE of (1,1): goes SE
        for _ in range(3):
            v.rotate(1, about=(1, 1))
        self.assertEqual(v.b, before)
        v.rotate(4, about=(0.5, 0.5))
        self.assertEqual(v.b, before)


class Arch(unittest.TestCase):
    def test_symmetric_and_continuous(self):
        for span, rise in ((8, 4), (11, 3), (6, 6), (20, 2)):
            v = Vox()
            v.arch(0, span, 5, 2, rise, "stone_bricks")
            cells = {(x, y) for x, y, z in v.b}
            self.assertEqual({z for _, _, z in v.b}, {2})
            self.assertEqual(cells, {(span - x, y) for x, y in cells})
            self.assertEqual(min(y for x, y in cells if x == 0), 5)
            self.assertEqual(max(y for x, y in cells), 5 + rise)
            cols = [sorted(y for x, y in cells if x == u) for u in range(span + 1)]
            for c in cols:
                self.assertEqual(c, list(range(c[0], c[-1] + 1)))
            for c, d in zip(cols, cols[1:]):
                self.assertLessEqual(max(c[0], d[0]), min(c[-1], d[-1]) + 1)

    def test_along_z(self):
        v = Vox()
        v.arch(0, 4, 0, 7, 2, "stone", along="z")
        self.assertEqual({x for x, _, _ in v.b}, {7})
        self.assertIn((7, 2, 2), v.b)


class Existing(unittest.TestCase):
    def test_box_ring(self):
        v = Vox()
        v.box(2, 0, 0, 0, 1, 1, "stone")
        self.assertEqual(len(v.b), 12)
        v = Vox()
        v.ring(0, 0, 0, 3, 2, "stone")
        self.assertEqual(len(v.b), 10)
        self.assertNotIn((1, 0, 1), v.b)

    def test_weighted_deterministic(self):
        a, b = Vox(seed=7), Vox(seed=7)
        a.box(0, 0, 0, 5, 5, 5, "70%stone_bricks,30%andesite")
        b.box(0, 0, 0, 5, 5, 5, "70%stone_bricks,30%andesite")
        self.assertEqual(a.b, b.b)
        self.assertEqual(set(a.b.values()), {"stone_bricks", "andesite"})

    def test_fence_connect(self):
        v = Vox(origin=(10, 0, 0))
        v.set(0, 0, 0, "oak_fence")
        v.set(1, 0, 0, "oak_fence")
        v.set(0, 0, 1, "stone")
        v.set(0, 0, -1, "oak_slab")
        v.set(1, 0, 1, "oak_fence[north=false,east=false,south=false,west=false]")
        v.connect()
        self.assertEqual(v.b[(0, 0, 0)], "oak_fence[north=false,south=true,east=true,west=false]")
        self.assertEqual(v.b[(1, 0, 0)], "oak_fence[north=false,south=true,east=false,west=true]")
        self.assertEqual(v.b[(1, 0, 1)], "oak_fence[north=false,east=false,south=false,west=false]")
        self.assertIn([10, 0, 0, v.b[(0, 0, 0)]], v.items())


if __name__ == "__main__":
    unittest.main()
