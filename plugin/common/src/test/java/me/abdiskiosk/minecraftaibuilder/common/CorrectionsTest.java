package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrectionsTest {

    /** An entry that placed {@code after} in a row of cells along x at y=64, where there was air. */
    private static BlockDiff placed(String... after) {
        BlockDiff.Builder b = new BlockDiff.Builder();
        for (int x = 0; x < after.length; x++) b.change(new Vec3(x, 64, 0), "air", after[x]);
        return b.build();
    }

    private static Vec3 at(int x) {
        return new Vec3(x, 64, 0);
    }

    /** The world: the entry's after-states, with some cells changed since. */
    private static Map<Vec3, String> world(BlockDiff entry) {
        Map<Vec3, String> w = new HashMap<>();
        for (BlockDiff.Change c : entry.changes()) w.put(c.pos(), c.after());
        return w;
    }

    private static String[] repeat(String state, int n) {
        String[] out = new String[n];
        java.util.Arrays.fill(out, state);
        return out;
    }

    @Test
    void substitutionsAreGroupedByTypeWithSamplePositions() {
        BlockDiff entry = placed(repeat("oak_planks", 10));
        Map<Vec3, String> w = world(entry);
        for (int x = 0; x < 7; x++) w.put(at(x), "spruce_planks");
        Corrections.Result r = Corrections.find(entry, List.of(), w::get);
        assertTrue(r.corrected());
        assertEquals(7, r.cells());
        assertEquals(10, r.placed());
        assertEquals(0.7, r.share(), 1e-9);
        assertEquals(1, r.substitutions().size());
        Corrections.Substitution s = r.substitutions().get(0);
        assertEquals("oak_planks", s.from());
        assertEquals("spruce_planks", s.to());
        assertEquals(7, s.count());
        assertEquals(List.of(at(0), at(1), at(2), at(3), at(4)), s.at());
        assertEquals(7, r.diff().size());
        assertEquals("oak_planks", r.diff().changes().get(0).before());
        assertEquals("spruce_planks", r.diff().changes().get(0).after());
    }

    @Test
    void removalsAreCountedByType() {
        BlockDiff entry = placed("torch", "torch", "torch", "torch", "stone", "stone");
        Map<Vec3, String> w = world(entry);
        for (int x = 0; x < 4; x++) w.put(at(x), "cave_air");
        Corrections.Result r = Corrections.find(entry, List.of(), w::get);
        assertEquals(4, r.cells());
        assertEquals(List.of(new Corrections.TypeCount("torch", 4)), r.removed());
        assertTrue(r.substitutions().isEmpty());
        assertEquals("air", r.diff().changes().get(0).after());
    }

    @Test
    void stateOnlyChangesAreCountedByType() {
        BlockDiff entry = placed(repeat("oak_stairs[facing=east]", 5));
        Map<Vec3, String> w = world(entry);
        for (int x = 0; x < 5; x++) w.put(at(x), "oak_stairs[facing=west]");
        Corrections.Result r = Corrections.find(entry, List.of(), w::get);
        assertEquals(List.of(new Corrections.TypeCount("oak_stairs", 5)), r.states());
        assertTrue(r.substitutions().isEmpty());
        assertTrue(r.removed().isEmpty());
    }

    @Test
    void cellsALaterEntryChangedAreNotCorrections() {
        BlockDiff entry = placed(repeat("oak_planks", 6));
        BlockDiff later = new BlockDiff.Builder()
                .change(at(0), "oak_planks", "glass").change(at(1), "oak_planks", "glass")
                .change(at(2), "oak_planks", "glass").change(at(3), "oak_planks", "glass").build();
        BlockDiff revert = new BlockDiff.Builder().change(at(3), "glass", "air").build();
        Map<Vec3, String> w = world(entry);
        for (int x = 0; x < 3; x++) w.put(at(x), "glass");
        w.put(at(3), "air");
        assertFalse(Corrections.find(entry, List.of(later, revert), w::get).corrected());

        // A person changed what the later entry placed: still a correction, measured from the later state.
        for (int x = 0; x < 3; x++) w.put(at(x), "white_stained_glass");
        w.put(at(4), "birch_planks");
        Corrections.Result r = Corrections.find(entry, List.of(later, revert), w::get);
        assertEquals(4, r.cells());
        assertEquals("glass", r.substitutions().get(0).from());
        assertEquals(3, r.substitutions().get(0).count());
    }

    @Test
    void cellsOfALaterEditWithoutADiffAreSkipped() {
        BlockDiff entry = placed(repeat("stone", 6));
        Map<Vec3, String> w = world(entry);
        for (int x = 0; x < 6; x++) w.put(at(x), "dirt");
        Corrections.Result r = Corrections.find(entry, List.of(), List.of(Box.of(at(0), at(3))), w::get,
                new Corrections.Threshold(1, 0));
        assertEquals(2, r.cells());
    }

    @Test
    void changesTheWorldMakesItselfAreDropped() {
        String[][] cells = {
                {"oak_leaves[distance=1]", "air"},                                  // leaf decay
                {"oak_leaves[distance=1]", "oak_leaves[distance=7]"},
                {"dirt", "grass_block[snowy=false]"},                              // grass spreading
                {"grass_block", "dirt"},                                           // grass dying under a block
                {"wheat[age=0]", "wheat[age=7]"},                                  // crop growth
                {"oak_sapling", "oak_log[axis=y]"},                                // sapling grew
                {"water[level=2]", "air"},                                         // flow receded
                {"oak_fence[east=true]", "oak_fence[east=false]"},                 // neighbour gone
                {"ice", "water"},                                                  // melted
                {"copper_block", "exposed_copper"},                                // oxidised
                {"air", "snow[layers=1]"},                                         // snowfall on a cleared cell
                {"oak_door[open=false]", "oak_door[open=true]"},                   // someone walked through
                {"white_concrete_powder", "white_concrete"},
                {"brain_coral_block", "dead_brain_coral_block"},
                {"melon_stem[age=7]", "attached_melon_stem[facing=north]"},
                {"air", "fire[age=3]"},
                {"kelp", "kelp_plant"},
        };
        BlockDiff.Builder b = new BlockDiff.Builder();
        Map<Vec3, String> w = new HashMap<>();
        for (int x = 0; x < cells.length; x++) {
            b.change(at(x), cells[x][0].equals("air") ? "stone" : "air", cells[x][0]);
            w.put(at(x), cells[x][1]);
        }
        BlockDiff entry = b.build();
        assertEquals(cells.length, entry.size());
        assertFalse(Corrections.find(entry, List.of(), List.of(), w::get, new Corrections.Threshold(1, 0)).corrected());
    }

    @Test
    void naturalRulesDoNotHideRealChanges() {
        assertFalse(Corrections.natural("oak_stairs[facing=east]", "oak_stairs[facing=west]"));
        assertFalse(Corrections.natural("water", "air"));          // a source someone removed
        assertFalse(Corrections.natural("waxed_copper_block", "waxed_exposed_copper"));
        assertFalse(Corrections.natural("pumpkin", "melon"));
        assertFalse(Corrections.natural("oak_leaves", "spruce_leaves"));
        assertFalse(Corrections.natural("stone", "air"));
        assertTrue(Corrections.natural("fire", "air"));
        assertTrue(Corrections.natural("lava", "obsidian"));
    }

    @Test
    void resultsBelowTheThresholdAreNotReported() {
        BlockDiff entry = placed(repeat("stone", 200));
        Map<Vec3, String> w = world(entry);
        for (int x = 0; x < 3; x++) w.put(at(x), "andesite");
        Corrections.Result r = Corrections.find(entry, List.of(), w::get);
        assertFalse(r.corrected());
        assertEquals(0, r.cells());
        assertEquals(200, r.placed());
        assertTrue(r.diff().isEmpty());

        w.put(at(3), "andesite"); // four cells reach the threshold even though they are 2% of the entry
        assertEquals(4, Corrections.find(entry, List.of(), w::get).cells());

        BlockDiff small = placed(repeat("stone", 10));
        Map<Vec3, String> w2 = world(small);
        w2.put(at(0), "andesite"); // one cell is 10% of a small entry
        assertEquals(1, Corrections.find(small, List.of(), w2::get).cells());
    }

    @Test
    void everyKindOfAirIsTheSame() {
        BlockDiff entry = new BlockDiff.Builder()
                .change(at(0), "stone", "air").change(at(1), "stone", "air").change(at(2), "stone", "air").build();
        Map<Vec3, String> w = new HashMap<>();
        w.put(at(0), "cave_air");
        w.put(at(1), "void_air");
        // at(2) reads as null: unloaded or outside the read counts as air
        Corrections.Result r = Corrections.find(entry, List.of(), List.of(), w::get, new Corrections.Threshold(1, 0));
        assertFalse(r.corrected());
    }

    @Test
    void airReplacedByABlockIsASubstitutionFromAir() {
        BlockDiff entry = new BlockDiff.Builder()
                .change(at(0), "stone", "air").change(at(1), "stone", "air").change(at(2), "stone", "air").build();
        Map<Vec3, String> w = new HashMap<>();
        for (int x = 0; x < 3; x++) w.put(at(x), "cobblestone");
        Corrections.Result r = Corrections.find(entry, List.of(), w::get);
        assertEquals(3, r.cells());
        assertEquals("air", r.substitutions().get(0).from());
        assertEquals("cobblestone", r.substitutions().get(0).to());
    }
}
