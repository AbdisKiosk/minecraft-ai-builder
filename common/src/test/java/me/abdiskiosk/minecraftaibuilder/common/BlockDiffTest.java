package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockDiffTest {

    // 2 x 1 x 2 box: indexes 0=(0,0,0) 1=(1,0,0) 2=(0,0,1) 3=(1,0,1)
    private static final Box BOX = Box.of(new Vec3(0, 0, 0), new Vec3(1, 0, 1));

    private static BlockDiff sample() {
        return BlockDiff.of(BOX,
                new String[]{"air", "stone", "dirt", "oak_planks"},
                new String[]{"stone_bricks", "air", "dirt", "spruce_planks"});
    }

    @Test
    void findsAddedRemovedAndChangedCells() {
        BlockDiff diff = sample();
        assertEquals(3, diff.size());
        assertEquals(BlockDiff.Kind.ADDED, diff.changes().get(0).kind());
        assertEquals(BlockDiff.Kind.REMOVED, diff.changes().get(1).kind());
        assertEquals(BlockDiff.Kind.CHANGED, diff.changes().get(2).kind());
        assertEquals(Box.of(new Vec3(0, 0, 0), new Vec3(1, 0, 1)), diff.bounds());
    }

    @Test
    void airVariantsAreNotChanges() {
        BlockDiff diff = BlockDiff.of(Box.of(new Vec3(0, 0, 0), new Vec3(0, 0, 0)), new String[]{"cave_air"}, new String[]{null});
        assertTrue(diff.isEmpty());
        assertNull(diff.bounds());
    }

    @Test
    void statsCountByKindAndType() {
        BlockDiff.Stats stats = BlockDiff.of(BOX,
                new String[]{"air", "air", "stone", "oak_stairs[facing=east]"},
                new String[]{"stone", "stone", "air", "oak_stairs[facing=west]"}).stats();
        assertEquals(2, stats.added());
        assertEquals(1, stats.removed());
        assertEquals(1, stats.changed());
        assertEquals(new BlockDiff.TypeCount("stone", 2, 1), stats.types().get(0));
        assertEquals(new BlockDiff.TypeCount("oak_stairs", 1, 1), stats.types().get(1));
    }

    @Test
    void encodesAndDecodes() {
        BlockDiff diff = new BlockDiff.Builder()
                .add(BOX, new String[]{"air", "stone", "dirt", "oak_planks"}, new String[]{"stone_bricks", "air", "dirt", "spruce_planks"})
                .add(Box.of(new Vec3(-300, -64, 5000), new Vec3(-300, -64, 5000)), new String[]{"air"}, new String[]{"oak_stairs[facing=east,half=top]"})
                .build();
        BlockDiff back = BlockDiff.decode(diff.encode());
        assertEquals(diff.changes(), back.changes());
    }

    @Test
    void rejectsGarbage() {
        assertThrows(EditException.class, () -> BlockDiff.decode(new byte[]{1, 2, 3}));
    }

    @Test
    void overlayRebuildsBeforeAndAfter() {
        BlockDiff diff = sample();
        String[] now = {"stone_bricks", "air", "dirt", "spruce_planks"};
        assertArrayEquals(new String[]{"air", "stone", "dirt", "oak_planks"}, diff.overlay(BOX, now, false));
        assertArrayEquals(now, diff.overlay(BOX, new String[]{"x", "x", "dirt", "x"}, true));
    }

    @Test
    void revertSkipsCellsEditedSinceAndForceTakesThem() {
        BlockDiff diff = sample();
        Map<Vec3, String> world = new HashMap<>();
        world.put(new Vec3(0, 0, 0), "stone_bricks");    // unchanged since: revert
        world.put(new Vec3(1, 0, 0), "gold_block");      // edited again: conflict
        world.put(new Vec3(1, 0, 1), "oak_planks");      // already back: nothing to do
        BlockDiff.Revert revert = diff.revert(p -> world.getOrDefault(p, "air"), false);
        assertEquals(List.of(new Op.BlockAt(new Vec3(0, 0, 0), "air")), revert.blocks());
        assertEquals(1, revert.conflicts().size());
        assertEquals("gold_block", revert.conflicts().get(0).after());

        BlockDiff.Revert forced = diff.revert(p -> world.getOrDefault(p, "air"), true);
        assertEquals(2, forced.blocks().size());
        assertTrue(forced.conflicts().isEmpty());
    }

    @Test
    void highlightRendersDifferentlyFromPlain() {
        BlockDiff diff = sample();
        String[] after = {"stone_bricks", "stone", "dirt", "spruce_planks"}; // removed cell shows the old block
        byte[] plain = IsoRenderer.render(BOX, after, IsoRenderer.View.TOP, IsoRenderer.Corner.SE, IsoRenderer.Size.SMALL).png();
        byte[] marked = IsoRenderer.render(BOX, after, IsoRenderer.View.TOP, IsoRenderer.Corner.SE, IsoRenderer.Size.SMALL,
                diff.highlight(BOX)).png();
        assertTrue(marked.length > 0);
        assertTrue(!java.util.Arrays.equals(plain, marked));
        assertEquals(3, diff.highlight(BOX).size());
    }
}
