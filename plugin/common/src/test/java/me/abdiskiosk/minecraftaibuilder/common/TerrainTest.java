package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TerrainTest {

    /** 4 x 3 columns, y 0..5: ground of height x + 1 (top at y = x), a poppy on (1,_,0), water above (2,_,1). */
    private static final Box REGION = Box.of(new Vec3(0, 0, 0), new Vec3(3, 5, 2));

    private static String[] slope() {
        String[] states = new String[(int) REGION.volume()];
        Arrays.fill(states, "air");
        for (int x = 0; x <= 3; x++) {
            for (int z = 0; z <= 2; z++) {
                for (int y = 0; y <= x; y++) states[RegionCodec.index(REGION, x, y, z)] = y == x ? "grass_block[snowy=false]" : "dirt";
            }
        }
        states[RegionCodec.index(REGION, 1, 2, 0)] = "poppy";
        states[RegionCodec.index(REGION, 2, 3, 1)] = "water[level=0]";
        states[RegionCodec.index(REGION, 2, 4, 1)] = "water[level=0]";
        return states;
    }

    /** Cells covered by the fills, as "x,y,z"; fails when two fills overlap. */
    private static Set<String> cells(List<Op> ops) {
        Set<String> out = new HashSet<>();
        for (Op op : ops) {
            Box b = ((Op.Fill) op).box();
            assertEquals("dirt", ((Op.Fill) op).pattern());
            for (int x = b.min().x(); x <= b.max().x(); x++) {
                for (int y = b.min().y(); y <= b.max().y(); y++) {
                    for (int z = b.min().z(); z <= b.max().z(); z++) {
                        assertTrue(out.add(x + "," + y + "," + z), "fills overlap at " + x + "," + y + "," + z);
                    }
                }
            }
        }
        return out;
    }

    /** Every z of the region for each column x, from y lo(x) to hi(x). */
    private static Set<String> columns(java.util.function.IntUnaryOperator lo, java.util.function.IntUnaryOperator hi) {
        Set<String> out = new HashSet<>();
        for (int x = 0; x <= 3; x++) {
            for (int z = 0; z <= 2; z++) {
                for (int y = lo.applyAsInt(x); y <= hi.applyAsInt(x); y++) out.add(x + "," + y + "," + z);
            }
        }
        return out;
    }

    @Test
    void foundationFillsFromTheGroundToTheTopThroughPlantsAndWater() {
        List<Op> ops = Terrain.foundation(REGION, slope(), "dirt");
        assertEquals(columns(x -> x + 1, x -> 5), cells(ops));
        assertEquals(4, ops.size()); // one per x, the three z rows merged
    }

    @Test
    void overlayReplacesTheTopGroundBlocks() {
        assertEquals(columns(x -> x, x -> x), cells(Terrain.overlay(REGION, slope(), "dirt", 1, 0)));
        assertEquals(columns(x -> Math.max(0, x - 1), x -> x), cells(Terrain.overlay(REGION, slope(), "dirt", 2, 0)));
    }

    @Test
    void overlayRaisesOnTopOfTheGroundBelowTheRegionTop() {
        assertEquals(columns(x -> x + 1, x -> Math.min(5, x + 3)), cells(Terrain.overlay(REGION, slope(), "dirt", 1, 3)));
    }

    @Test
    void flatGroundIsOneFill() {
        Box region = Box.of(new Vec3(10, 60, 10), new Vec3(29, 70, 29));
        String[] states = new String[(int) region.volume()];
        Arrays.fill(states, "air");
        for (int x = 10; x <= 29; x++) {
            for (int z = 10; z <= 29; z++) states[RegionCodec.index(region, x, 62, z)] = "stone";
        }
        List<Op> ops = Terrain.foundation(region, states, "dirt");
        assertEquals(List.of(new Op.Fill(Box.of(new Vec3(10, 63, 10), new Vec3(29, 70, 29)), "dirt")), ops);
        assertEquals(1, Terrain.overlay(region, states, "dirt", 1, 0).size());
    }

    @Test
    void columnsWithoutGround() {
        Box region = Box.of(new Vec3(0, 0, 0), new Vec3(1, 2, 0));
        // By y then x: x=0 has stone at y 0; x=1 only leaves and a log.
        String[] states = {"stone", "air", "air", "oak_leaves", "air", "oak_log"};
        assertEquals(Set.of("1,0,0", "1,1,0", "1,2,0", "0,1,0", "0,2,0"), cells(Terrain.foundation(region, states, "dirt")));
        assertEquals(Set.of("0,0,0"), cells(Terrain.overlay(region, states, "dirt", 1, 0)));
    }

    @Test
    void groundAtTheTopNeedsNothing() {
        Box region = Box.of(new Vec3(0, 0, 0), new Vec3(1, 1, 1));
        String[] states = new String[8];
        Arrays.fill(states, "stone");
        assertTrue(Terrain.foundation(region, states, "dirt").isEmpty());
        assertTrue(Terrain.overlay(region, states, "dirt", 1, 2).isEmpty());
        assertEquals(1, Terrain.overlay(region, states, "dirt", 1, 0).size());
    }
}
