package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionSnapshotTest {

    @Test
    void baselineRoundTripsExactCoverageAirAndStates() throws Exception {
        FakeWorld world = new FakeWorld();
        world.set.put(new Vec3(0, 0, 0), "cave_air");
        world.set.put(new Vec3(1, 0, 0), "oak_stairs[facing=east]");
        RegionSnapshot original = new RegionSnapshot(List.of(A, B));
        original.readAll(world, "w", 10);
        byte[] bytes = original.encode();
        RegionSnapshot restored = RegionSnapshot.decode(bytes, original.volume());
        assertTrue(restored.done());
        assertEquals(original.boxes(), restored.boxes());
        assertArrayEquals(original.array(0), restored.array(0));
        assertArrayEquals(original.array(1), restored.array(1));
        assertEquals("air", restored.get(new Vec3(0, 0, 0)));
        assertNull(restored.get(new Vec3(5, 0, 0)));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> RegionSnapshot.decode(bytes, original.volume() - 1));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> RegionSnapshot.decode(java.util.Arrays.copyOf(bytes, bytes.length / 2), 100));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> new RegionSnapshot(List.of(A)).encode());
    }

    @Test
    void corruptBaselineRunsAndHugeDimensionsAreRefused() throws Exception {
        for (int run : new int[]{0, -1, 2}) {
            byte[] bytes = baselineBytes(0, run, false);
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> RegionSnapshot.decode(bytes, 100));
        }
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> RegionSnapshot.decode(baselineBytes(1, 1, false), 100));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> RegionSnapshot.decode(baselineBytes(0, 1, true), 100));
    }

    private static byte[] baselineBytes(int paletteIndex, int run, boolean huge) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(bytes))) {
            out.writeInt(1); out.writeInt(1);
            for (int i = 0; i < 3; i++) out.writeInt(huge ? Integer.MIN_VALUE : 0);
            for (int i = 0; i < 3; i++) out.writeInt(huge ? Integer.MAX_VALUE : 0);
            out.writeInt(1); out.writeUTF("air"); out.writeInt(paletteIndex); out.writeInt(run);
        }
        return bytes.toByteArray();
    }

    /** A world where every block is named after its coordinates, and reads are recorded. */
    static final class FakeWorld implements WorldEditBridge {
        final Map<Vec3, String> set = new HashMap<>();
        final List<Box> reads = new ArrayList<>();

        String at(int x, int y, int z) {
            return set.getOrDefault(new Vec3(x, y, z), "b" + x + "_" + y + "_" + z);
        }

        @Override public String name() { return "fake"; }
        @Override public boolean async() { return false; }
        @Override public BlockCatalog catalog() { throw new UnsupportedOperationException(); }
        @Override public Optional<SelectionInfo> selection(UUID player) { return Optional.empty(); }
        @Override public EditHandle begin(UUID actor, String world) { throw new UnsupportedOperationException(); }
        @Override public int undo(UUID actor, int steps) { return 0; }
        @Override public Schematic readSchematic(Path file, long maxVolume) { throw new UnsupportedOperationException(); }

        @Override
        public String[] read(String world, Box box) {
            reads.add(box);
            String[] out = new String[(int) box.volume()];
            for (int y = box.min().y(); y <= box.max().y(); y++)
                for (int z = box.min().z(); z <= box.max().z(); z++)
                    for (int x = box.min().x(); x <= box.max().x(); x++)
                        out[RegionCodec.index(box, x, y, z)] = at(x, y, z);
            return out;
        }
    }

    private static final Box A = Box.of(new Vec3(0, 0, 0), new Vec3(3, 2, 4));
    private static final Box B = Box.of(new Vec3(10, 5, 10), new Vec3(10, 5, 11));

    @Test
    void steppedReadMatchesOneRead() {
        FakeWorld world = new FakeWorld();
        for (long budget : new long[]{1, 3, 4, 7, 20, 21, 1000}) {
            RegionSnapshot s = new RegionSnapshot(List.of(A, B));
            int steps = 0;
            while (!s.step(world, "w", budget)) steps++;
            assertTrue(s.done());
            assertArrayEquals(world.read("w", A), s.array(0), "budget " + budget);
            assertArrayEquals(world.read("w", B), s.array(1), "budget " + budget);
            if (budget == 1) assertTrue(steps > 10);
        }
    }

    @Test
    void diffSeesWhatChangedBetweenReads() {
        FakeWorld world = new FakeWorld();
        RegionSnapshot before = new RegionSnapshot(List.of(A, B));
        before.readAll(world, "w", 16);
        world.set.put(new Vec3(1, 1, 1), "air");
        world.set.put(new Vec3(10, 5, 11), "gold_block");
        RegionSnapshot after = before.fresh();
        after.readAll(world, "w", 16);
        BlockDiff diff = RegionSnapshot.diff(before, after);
        assertEquals(2, diff.size());
        assertEquals("b1_1_1", diff.changes().get(0).before());
        assertEquals("gold_block", after.get(new Vec3(10, 5, 11)));
        assertNull(after.get(new Vec3(50, 50, 50)));
    }

    @Test
    void mergeJoinsOverlappingAndTouchingBoxes() {
        List<Box> merged = RegionSnapshot.merge(List.of(
                Box.of(new Vec3(0, 0, 0), new Vec3(4, 4, 4)),
                Box.of(new Vec3(5, 0, 0), new Vec3(6, 1, 1)),     // touches the first
                Box.of(new Vec3(100, 0, 0), new Vec3(101, 1, 1)))); // apart
        assertEquals(2, merged.size());
        assertEquals(Box.of(new Vec3(0, 0, 0), new Vec3(6, 4, 4)), merged.get(0));
        assertFalse(merged.get(1).contains(merged.get(0)));
    }

    @Test
    void clipYCutsToWorldHeight() {
        assertEquals(Box.of(new Vec3(0, -64, 0), new Vec3(1, 10, 1)),
                RegionSnapshot.clipY(Box.of(new Vec3(0, -100, 0), new Vec3(1, 10, 1)), -64, 319));
        assertNull(RegionSnapshot.clipY(Box.of(new Vec3(0, 400, 0), new Vec3(1, 410, 1)), -64, 319));
    }
}
