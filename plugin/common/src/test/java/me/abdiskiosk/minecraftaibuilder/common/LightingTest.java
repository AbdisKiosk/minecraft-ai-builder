package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LightingTest {

    private static Box box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return Box.of(new Vec3(x1, y1, z1), new Vec3(x2, y2, z2));
    }

    private static String[] filled(Box box, String state) {
        String[] states = new String[(int) box.volume()];
        Arrays.fill(states, state);
        return states;
    }

    private static void fill(Box box, String[] states, int x1, int y1, int z1, int x2, int y2, int z2, String state) {
        for (int y = y1; y <= y2; y++) {
            for (int z = z1; z <= z2; z++) {
                for (int x = x1; x <= x2; x++) states[RegionCodec.index(box, x, y, z)] = state;
            }
        }
    }

    /** A sealed stone box with an empty interior one block in from every face. */
    private static String[] room(Box box) {
        String[] states = filled(box, "stone");
        fill(box, states, box.min().x() + 1, box.min().y() + 1, box.min().z() + 1,
                box.max().x() - 1, box.max().y() - 1, box.max().z() - 1, "air");
        return states;
    }

    /** Checks each position is air on solid ground, writes a torch there and returns the dark issues left. */
    private static List<RegionLint.Issue> placeTorches(Box box, String[] states, List<Vec3> lights) {
        assertEquals(lights.size(), new HashSet<>(lights).size(), lights.toString());
        for (Vec3 p : lights) {
            int i = RegionCodec.index(box, p.x(), p.y(), p.z());
            assertTrue(RegionCodec.isAir(states[i]), p.toString());
            assertTrue(BlockTraits.solid(states[RegionCodec.index(box, p.x(), p.y() - 1, p.z())]), p.toString());
            states[i] = "torch";
        }
        return RegionLint.check(box, states, Set.of("dark"), false);
    }

    @Test
    void sealedRoomNeedsAHandfulOfLights() {
        Box box = box(100, 60, -20, 120, 64, 0);
        String[] states = room(box);
        assertEquals(1, RegionLint.check(box, states, Set.of("dark"), false).size());
        List<Vec3> lights = Lighting.place(box, states, 14);
        assertTrue(lights.size() >= 1 && lights.size() <= 6, lights.toString());
        assertEquals(lights, Lighting.place(box, states, 14));
        assertTrue(placeTorches(box, states, lights).isEmpty());
    }

    @Test
    void openSkyAndLitRoomsNeedNothing() {
        Box box = box(0, 0, 0, 8, 4, 8);
        String[] open = room(box);
        fill(box, open, 1, 4, 1, 7, 4, 7, "air");
        assertTrue(Lighting.place(box, open, 14).isEmpty());

        String[] lit = room(box);
        lit[RegionCodec.index(box, 4, 1, 4)] = "torch";
        assertTrue(RegionLint.check(box, lit, Set.of("dark"), false).isEmpty());
        assertTrue(Lighting.place(box, lit, 14).isEmpty());
    }

    @Test
    void separateRoomsEachGetALight() {
        Box box = box(0, 0, 0, 12, 4, 4);
        String[] states = filled(box, "stone");
        fill(box, states, 1, 1, 1, 5, 3, 3, "air");
        fill(box, states, 7, 1, 1, 11, 3, 3, "air");
        List<Vec3> lights = Lighting.place(box, states, 14);
        assertTrue(lights.stream().anyMatch(p -> p.x() <= 5), lights.toString());
        assertTrue(lights.stream().anyMatch(p -> p.x() >= 7), lights.toString());
        assertTrue(placeTorches(box, states, lights).isEmpty());
    }

    @Test
    void weakLightStillFinishes() {
        Box box = box(0, 0, 0, 6, 4, 6);
        String[] states = room(box);
        int darkCells = RegionLint.check(box, states, Set.of("dark"), false).get(0).count();
        List<Vec3> lights = Lighting.place(box, states, 1);
        assertEquals(darkCells, lights.size());
        assertTrue(placeTorches(box, states, lights).isEmpty());
    }

    @Test
    void lightsGoOnEveryFloorOfAStack() {
        Box box = box(0, 0, 0, 16, 8, 16);
        String[] states = room(box);
        fill(box, states, 1, 4, 1, 15, 4, 15, "oak_planks");
        List<Vec3> lights = Lighting.place(box, states, 14);
        assertTrue(lights.stream().anyMatch(p -> p.y() == 1) && lights.stream().anyMatch(p -> p.y() == 5), lights.toString());
        assertTrue(placeTorches(box, states, lights).isEmpty());
    }

    @Test
    void tooManyLightsIsRefused() {
        Box box = box(0, 0, 0, 120, 3, 120);
        EditException e = assertThrows(EditException.class, () -> Lighting.place(box, room(box), 1));
        assertTrue(e.getMessage().contains("too many lights"), e.getMessage());
    }

    @Test
    void largeRegionIsFast() {
        Box box = box(0, 0, 0, 499, 7, 499);
        String[] states = room(box);
        fill(box, states, 1, 4, 1, 498, 4, 498, "stone");
        long start = System.nanoTime();
        List<Vec3> lights = Lighting.place(box, states, 14);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 5000, ms + " ms for " + lights.size() + " lights");
        assertTrue(placeTorches(box, states, lights).isEmpty());
    }

    /** The region to light often stops at the ceiling; the roof that makes it dark is read along with it. */
    @Test
    void floorsUnderARoofAboveTheLitLayersAreLit() {
        Box box = Box.of(new Vec3(0, 0, 0), new Vec3(6, 9, 6));
        String[] states = new String[(int) box.volume()];
        Arrays.fill(states, "air");
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                states[RegionCodec.index(box, x, 0, z)] = "stone";
                states[RegionCodec.index(box, x, 6, z)] = "stone"; // a roof with a dark attic floor on top of it
                states[RegionCodec.index(box, x, 9, z)] = "stone";
            }
        }
        List<Vec3> lights = Lighting.place(box, states, 14, 3);
        assertFalse(lights.isEmpty());
        assertTrue(lights.stream().allMatch(p -> p.y() == 1), lights.toString());
        assertTrue(Lighting.place(box, states, 14, 0).isEmpty());
    }
}
