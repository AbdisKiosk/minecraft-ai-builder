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

class DraftTest {

    private static final Vec3 A = new Vec3(0, 64, 0), B = new Vec3(1, 64, 0);

    private static Draft.Entry entry(BlockDiff diff) {
        return new Draft.Entry("fill", "test", "Alex", 0, diff, null);
    }

    private static BlockDiff diff(Object... posBeforeAfter) {
        BlockDiff.Builder b = new BlockDiff.Builder();
        for (int i = 0; i < posBeforeAfter.length; i += 3) b.change((Vec3) posBeforeAfter[i], (String) posBeforeAfter[i + 1], (String) posBeforeAfter[i + 2]);
        return b.build();
    }

    @Test
    void entriesMergeIntoOneChangePerPosition() {
        Draft d = new Draft("d1", "world", 0);
        d.add(entry(diff(A, "air", "stone", B, "dirt", "stone")));
        d.add(entry(diff(A, "stone", "gold_block")));
        assertEquals(2, d.size());
        assertEquals("gold_block", d.after(A));
        BlockDiff all = d.diff();
        assertEquals(new BlockDiff.Change(A, "air", "gold_block"), all.changes().get(0));
    }

    @Test
    void anEntryThatPutsTheOriginalBackDropsTheChange() {
        Draft d = new Draft("d1", "world", 0);
        d.add(entry(diff(A, "air", "stone")));
        d.add(entry(diff(A, "stone", "air")));
        assertEquals(0, d.size());
        assertNull(d.after(A));
    }

    @Test
    void undoRestoresTheStateBeforeTheEntry() {
        Draft d = new Draft("d1", "world", 0);
        d.add(entry(diff(A, "air", "stone")));
        d.add(entry(diff(A, "stone", "air", B, "dirt", "glass")));
        d.undoLast();
        assertEquals("stone", d.after(A));
        assertNull(d.after(B));
        d.undoLast();
        assertEquals(0, d.size());
        assertNull(d.undoLast());
    }

    @Test
    void overlayWritesTheDraftOverARead() {
        Draft d = new Draft("d1", "world", 0);
        d.add(entry(diff(A, "air", "stone")));
        Box box = Box.of(new Vec3(0, 64, 0), new Vec3(1, 64, 0));
        String[] states = {"air", "dirt"};
        d.overlay(box, states);
        assertArrayEquals(new String[]{"stone", "dirt"}, states);
    }

    @Test
    void closedDraftsRefuseChanges() {
        Draft d = new Draft("d1", "world", 0);
        d.close(Draft.Status.REJECTED, "admin", 1, null);
        assertThrows(EditException.class, () -> d.add(entry(diff(A, "air", "stone"))));
    }

    @Test
    void applyPlacesAfterStatesAndReportsConflicts() {
        BlockDiff diff = diff(A, "air", "stone", B, "dirt", "glass", new Vec3(2, 64, 0), "air", "oak_planks");
        Map<Vec3, String> world = new HashMap<>();
        world.put(A, "air");                       // unchanged: place
        world.put(B, "gold_block");                // changed since: conflict
        world.put(new Vec3(2, 64, 0), "oak_planks"); // already there: skip
        BlockDiff.Revert r = diff.apply(p -> world.getOrDefault(p, "air"), false);
        assertEquals(List.of(new Op.BlockAt(A, "stone")), r.blocks());
        assertEquals(List.of(new BlockDiff.Change(B, "dirt", "gold_block")), r.conflicts());
        assertEquals(2, diff.apply(p -> world.getOrDefault(p, "air"), true).blocks().size());
    }

    @Test
    void dryRunStoreRecordsWritesAndReadsThroughTheOverlay() {
        Map<Vec3, String> realWorld = Map.of(A, "dirt");
        Map<Vec3, String> draft = Map.of(B, "stone");
        DryRunStore<String> store = new DryRunStore<>(draft::get, s -> s, s -> s, 10);
        assertEquals("stone", store.read(1, 64, 0, () -> realWorld.getOrDefault(B, "air")));
        store.write(0, 64, 0, "glass", () -> realWorld.getOrDefault(A, "air"));
        store.write(0, 64, 0, "glowstone", () -> "never read again");
        assertEquals("glowstone", store.read(0, 64, 0, () -> "world"));
        store.write(1, 64, 0, "stone", () -> "air"); // same as the draft already has: no change
        BlockDiff diff = store.diff();
        assertEquals(List.of(new BlockDiff.Change(A, "dirt", "glowstone")), diff.changes());
    }

    @Test
    void dryRunStoreHasALimit() {
        DryRunStore<String> store = new DryRunStore<>(null, s -> s, s -> s, 1);
        store.write(0, 0, 0, "stone", () -> "air");
        assertThrows(EditException.class, () -> store.write(1, 0, 0, "stone", () -> "air"));
    }

    @Test
    void keysRoundTripNegativeCoordinates() {
        for (Vec3 v : List.of(new Vec3(-30_000_000, -64, 29_999_999), new Vec3(0, 0, 0), new Vec3(-1, 319, -1), new Vec3(12, -2048, -7))) {
            assertEquals(v, DryRunStore.unkey(DryRunStore.key(v.x(), v.y(), v.z())));
        }
    }

    @Test
    void stateArraysRoundTrip() {
        String[] states = {"air", "air", "cave_air", "stone", "oak_stairs[facing=east]", "stone", "stone"};
        String[] back = StateArrays.decode(StateArrays.encode(states), 100);
        assertArrayEquals(new String[]{"air", "air", "air", "stone", "oak_stairs[facing=east]", "stone", "stone"}, back);
        assertThrows(EditException.class, () -> StateArrays.decode(StateArrays.encode(states), 3));
        assertTrue(StateArrays.encode(new String[10000]).length < 100);
    }
}
