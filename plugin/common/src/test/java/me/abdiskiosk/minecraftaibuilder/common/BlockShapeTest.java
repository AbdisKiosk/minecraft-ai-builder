package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlockShapeTest {

    private static void assertBoxes(String state, double[]... expected) {
        List<double[]> boxes = BlockShape.boxes(state);
        assertEquals(expected.length, boxes.size(), state);
        for (int i = 0; i < expected.length; i++) assertArrayEquals(expected[i], boxes.get(i), 1e-9, state + " box " + i);
    }

    @Test
    void slabs() {
        assertBoxes("oak_slab", new double[]{0, 0, 0, 1, 0.5, 1});
        assertBoxes("oak_slab[type=top]", new double[]{0, 0.5, 0, 1, 1, 1});
        assertBoxes("oak_slab[type=double]", new double[]{0, 0, 0, 1, 1, 1});
    }

    @Test
    void stairsFollowFacingAndHalf() {
        // Default facing north: the tall part is on the north (-z) side.
        assertBoxes("oak_stairs", new double[]{0, 0, 0, 1, 0.5, 1}, new double[]{0, 0.5, 0, 1, 1, 0.5});
        assertBoxes("oak_stairs[facing=east]", new double[]{0, 0, 0, 1, 0.5, 1}, new double[]{0.5, 0.5, 0, 1, 1, 1});
        assertBoxes("stone_brick_stairs[facing=south,half=top]", new double[]{0, 0.5, 0, 1, 1, 1}, new double[]{0, 0, 0.5, 1, 0.5, 1});
        assertBoxes("oak_stairs[facing=west,shape=outer_left]", new double[]{0, 0, 0, 1, 0.5, 1}, new double[]{0, 0.5, 0, 0.5, 1, 1});
    }

    @Test
    void postsAndFlatBlocks() {
        double[] post = BlockShape.boxes("oak_fence[east=true]").get(0);
        assertEquals(0, post[1]);
        assertEquals(1, post[4]);
        assertEquals(0.25, post[3] - post[0], 0.01);
        assertEquals(0.25, post[5] - post[2], 0.01);
        assertArrayEquals(post, BlockShape.boxes("cobblestone_wall").get(0));
        assertEquals(0.125, BlockShape.boxes("red_carpet").get(0)[4], 1e-9);
    }

    @Test
    void fullCubes() {
        assertTrue(BlockShape.isFullCube("stone_bricks"));
        assertTrue(BlockShape.isFullCube("oak_log[axis=x]"));
        assertTrue(BlockShape.isFullCube("oak_slab[type=double]"));
        assertTrue(BlockShape.isFullCube("sea_lantern"));
        assertFalse(BlockShape.isFullCube("oak_slab"));
        assertFalse(BlockShape.isFullCube("oak_stairs[facing=east]"));
        assertFalse(BlockShape.isFullCube("glass_pane"));
        assertFalse(BlockShape.isFullCube("wall_torch[facing=north]"));
        assertFalse(BlockShape.isFullCube("spruce_trapdoor[half=top]"));
        assertFalse(BlockShape.isFullCube("oak_door[facing=east,half=lower]"));
    }
}
