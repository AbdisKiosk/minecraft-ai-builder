package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockTraitsTest {

    @Test
    void slabsAreFullOnlyWhenDouble() {
        assertFalse(BlockTraits.fullCube("stone_slab"));
        assertFalse(BlockTraits.solid("stone_slab"));
        assertFalse(BlockTraits.solid("stone_slab[type=top]"));
        assertTrue(BlockTraits.solid("stone_slab[type=double]"));
        assertTrue(BlockTraits.opaque("stone_slab[type=double]"));
    }

    @Test
    void glassAndLeavesHoldBlocksButLetLightThrough() {
        assertTrue(BlockTraits.solid("glass"));
        assertFalse(BlockTraits.opaque("glass"));
        assertFalse(BlockTraits.opaque("oak_leaves[persistent=true]"));
        assertTrue(BlockTraits.opaque("tinted_glass"));
        assertFalse(BlockTraits.solid("glass_pane"));
        assertTrue(BlockTraits.opaque("stone_bricks"));
        assertFalse(BlockTraits.solid("air"));
        assertTrue(BlockTraits.fullCube("sea_lantern"));
        assertFalse(BlockTraits.fullCube("lantern"));
        assertTrue(BlockColors.isTransparent("glass") && !BlockColors.isTransparent("stone"));
    }

    @Test
    void lanternsNeedAFloorUnlessHanging() {
        assertTrue(BlockTraits.needsFloor("lantern"));
        assertTrue(BlockTraits.needsFloor("soul_lantern[hanging=false]"));
        assertFalse(BlockTraits.needsFloor("lantern[hanging=true]"));
        assertFalse(BlockTraits.needsFloor("sea_lantern"));
    }

    @Test
    void wallBlocksHangInsteadOfStanding() {
        assertTrue(BlockTraits.needsFloor("torch"));
        assertFalse(BlockTraits.needsFloor("wall_torch[facing=east]"));
        assertTrue(BlockTraits.wallAttached("wall_torch[facing=east]"));
        assertTrue(BlockTraits.wallAttached("soul_wall_torch"));
        assertTrue(BlockTraits.wallAttached("oak_wall_sign[facing=south]"));
        assertFalse(BlockTraits.wallAttached("oak_wall_hanging_sign"));
        assertTrue(BlockTraits.needsFloor("oak_sign[rotation=4]"));
        assertFalse(BlockTraits.needsFloor("oak_hanging_sign"));
        assertTrue(BlockTraits.needsFloor("oak_door"));
        assertFalse(BlockTraits.needsFloor("oak_trapdoor"));
        assertEquals(new Vec3(1, 0, 0), BlockTraits.facing("wall_torch[facing=east]"));
        assertEquals(new Vec3(0, 0, -1), BlockTraits.facing("ladder"));
    }

    @Test
    void lightLevels() {
        assertEquals(14, BlockTraits.light("torch"));
        assertEquals(14, BlockTraits.light("wall_torch[facing=north]"));
        assertEquals(10, BlockTraits.light("soul_wall_torch"));
        assertEquals(7, BlockTraits.light("redstone_torch"));
        assertEquals(0, BlockTraits.light("redstone_torch[lit=false]"));
        assertEquals(15, BlockTraits.light("redstone_lamp[lit=true]"));
        assertEquals(0, BlockTraits.light("redstone_lamp"));
        assertEquals(15, BlockTraits.light("lantern[hanging=true]"));
        assertEquals(15, BlockTraits.light("pearlescent_froglight[axis=y]"));
        assertEquals(13, BlockTraits.light("furnace[facing=east,lit=true]"));
        assertEquals(0, BlockTraits.light("stone"));
    }

    @Test
    void gravity() {
        assertTrue(BlockTraits.gravity("red_sand"));
        assertTrue(BlockTraits.gravity("lime_concrete_powder"));
        assertTrue(BlockTraits.gravity("chipped_anvil"));
        assertFalse(BlockTraits.gravity("sandstone"));
        assertFalse(BlockTraits.gravity("lime_concrete"));
    }

    @Test
    void ground() {
        assertTrue(BlockTraits.ground("grass_block[snowy=false]"));
        assertTrue(BlockTraits.ground("stone_bricks"));
        assertTrue(BlockTraits.ground("sand"));
        assertTrue(BlockTraits.ground("snow_block"));
        for (String no : new String[]{"air", "water[level=0]", "lava", "oak_leaves[persistent=false]", "oak_log[axis=y]",
                "stripped_birch_wood", "crimson_stem", "poppy", "short_grass", "vine[east=true]", "snow[layers=2]",
                "oak_fence", "torch", "stone_slab"}) {
            assertFalse(BlockTraits.ground(no), no);
        }
    }
}
