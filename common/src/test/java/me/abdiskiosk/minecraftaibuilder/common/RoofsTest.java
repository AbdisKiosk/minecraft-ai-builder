package me.abdiskiosk.minecraftaibuilder.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class RoofsTest {

    private static Box walls(int x0, int z0, int x1, int z1, int y) {
        return Box.of(new Vec3(x0, y, z0), new Vec3(x1, y, z1));
    }

    /** Every cell the ops place, as "x,y,z" to state or pattern. */
    private static Map<String, String> cells(List<Op> ops) {
        Map<String, String> m = new TreeMap<>();
        for (Op op : ops) {
            if (op instanceof Op.Blocks bl) {
                for (Op.BlockAt b : bl.blocks()) put(m, b.pos().x(), b.pos().y(), b.pos().z(), b.state());
            } else if (op instanceof Op.Fill f) {
                for (int x = f.box().min().x(); x <= f.box().max().x(); x++)
                    for (int y = f.box().min().y(); y <= f.box().max().y(); y++)
                        for (int z = f.box().min().z(); z <= f.box().max().z(); z++) put(m, x, y, z, f.pattern());
            }
        }
        return m;
    }

    private static void put(Map<String, String> m, int x, int y, int z, String s) {
        assertEquals(null, m.put(x + "," + y + "," + z, s), "cell placed twice: " + x + "," + y + "," + z);
    }

    private static long count(Map<String, String> m, String contains) {
        return m.values().stream().filter(s -> s.contains(contains)).count();
    }

    @Test
    void matchesPythonImplementation() {
        // Derived with Vox.roof_gable(0,0,2,2,0,"spruce_stairs",along="x",overhang=0,gable="G",ridge="R") at origin 10,64,20.
        Map<String, String> expected = new TreeMap<>();
        for (int x = 0; x <= 2; x++) {
            expected.put(x + ",0,0", "spruce_stairs[facing=south]");
            expected.put(x + ",0,2", "spruce_stairs[facing=north]");
            expected.put(x + ",1,1", "R");
        }
        expected.put("0,0,1", "G");
        expected.put("2,0,1", "G");
        assertEquals(expected, cells(Roofs.gable(walls(10, 20, 12, 22, 64), "spruce_stairs", true, 0, "R", "G"))
                .entrySet().stream().collect(TreeMap::new, (m, e) -> {
                    String[] p = e.getKey().split(",");
                    m.put((Integer.parseInt(p[0]) - 10) + "," + (Integer.parseInt(p[1]) - 64) + "," + (Integer.parseInt(p[2]) - 20), e.getValue());
                }, Map::putAll));
    }

    @Test
    void hipMatchesPythonImplementation() {
        // Vox.roof_hip(0,0,3,2,0,"spruce_stairs",overhang=0)
        Map<String, String> expected = new TreeMap<>();
        for (int x = 0; x <= 3; x++) {
            expected.put(x + ",0,0", "spruce_stairs[facing=south]");
            expected.put(x + ",0,2", "spruce_stairs[facing=north]");
        }
        expected.put("0,0,1", "spruce_stairs[facing=east]");
        expected.put("3,0,1", "spruce_stairs[facing=west]");
        expected.put("1,1,1", "spruce_slab");
        expected.put("2,1,1", "spruce_slab");
        assertEquals(expected, cells(Roofs.hip(walls(0, 0, 3, 2, 0), "spruce_stairs", 0, null)));
    }

    @Test
    void gableAlongXOddSpanHasRidgeRow() {
        List<Op> ops = Roofs.gable(walls(0, 0, 5, 4, 70), "spruce_stairs", true, 1, null, null);
        Map<String, String> m = cells(ops);
        // span z -1..5 = 7 wide: stair rows at y 70..72, ridge at y 73, z=2, x -1..6
        assertEquals("spruce_slab", m.get("0,73,2"));
        assertEquals(8, count(m, "spruce_slab"));
        assertEquals(8 * 6, count(m, "stairs"));
        assertEquals("spruce_stairs[facing=south]", m.get("3,70,-1"));
        assertEquals("spruce_stairs[facing=north]", m.get("3,70,5"));
        assertEquals("spruce_stairs[facing=south]", m.get("3,72,1"));
        assertEquals(2, ops.size());
        assertEquals(Box.of(new Vec3(-1, 73, 2), new Vec3(6, 73, 2)), ((Op.Fill) ops.get(1)).box());
    }

    @Test
    void gableEvenSpanHasNoRidge() {
        List<Op> ops = Roofs.gable(walls(0, 0, 3, 3, 0), "oak_stairs", true, 0, null, null);
        assertEquals(1, ops.size());
        Map<String, String> m = cells(ops);
        assertEquals(4 * 4, m.size());
        assertEquals("oak_stairs[facing=south]", m.get("0,1,1"));
        assertEquals("oak_stairs[facing=north]", m.get("0,1,2"));
    }

    @Test
    void gableAlongZ() {
        Map<String, String> m = cells(Roofs.gable(walls(0, 0, 4, 6, 5), "oak_stairs", false, 0, "oak_planks", null));
        assertEquals("oak_stairs[facing=east]", m.get("0,5,3"));
        assertEquals("oak_stairs[facing=west]", m.get("4,5,3"));
        assertEquals("oak_stairs[facing=east]", m.get("1,6,0"));
        assertEquals("oak_planks", m.get("2,7,0"));
        assertEquals("oak_planks", m.get("2,7,6"));
        assertEquals(7, count(m, "oak_planks"));
    }

    @Test
    void gableInfillOnlyUnderSlopeOnEndWalls() {
        List<Op> ops = Roofs.gable(walls(0, 0, 5, 4, 0), "spruce_stairs", true, 0, null, "stone");
        Map<String, String> m = cells(ops);
        // Walls z 0..4: heights 0,1,0..? min(w, 4-w) = 0,1,2,1,0 -> columns of height 0,1,2,1,0 at x=0 and x=5
        assertEquals(8, count(m, "stone"));
        for (int x : new int[]{0, 5}) {
            assertEquals("stone", m.get(x + ",0,1"));
            assertEquals("stone", m.get(x + ",0,2"));
            assertEquals("stone", m.get(x + ",0,3"));
            assertEquals("stone", m.get(x + ",1,2"));
            assertTrue(!"stone".equals(m.get(x + ",0,0")));
            assertTrue(!"stone".equals(m.get(x + ",1,1")));
        }
        for (int x = 1; x <= 4; x++) assertTrue(!"stone".equals(m.get(x + ",0,2")));
        // one box per end wall and level: 2 ends x 2 levels
        assertEquals(4, ops.stream().filter(o -> o instanceof Op.Fill f && f.pattern().equals("stone")).count());
    }

    @Test
    void gableInfillWithOverhangStaysOnWalls() {
        Map<String, String> m = cells(Roofs.gable(walls(0, 0, 4, 4, 0), "spruce_stairs", true, 1, null, "G"));
        // roof span z -1..5, so infill heights at w=0..4 are 1,2,3,2,1 on x=0 and x=4 only
        assertEquals(18, count(m, "G"));
        assertEquals("G", m.get("0,2,2"));
        assertEquals(null, m.get("-1,0,2"));
        assertEquals(null, m.get("2,0,2"));
    }

    @Test
    void hipOverRectangle() {
        List<Op> ops = Roofs.hip(walls(0, 0, 8, 4, 10), "spruce_stairs", 1, null);
        Map<String, String> m = cells(ops);
        // rings over x -1..9, z -1..5: 7 wide rings -> rings at y 10,11,12, ridge at y 13 over x 2..6, z 2
        assertEquals(1, ops.stream().filter(o -> o instanceof Op.Fill).count());
        assertEquals(Box.of(new Vec3(2, 13, 2), new Vec3(6, 13, 2)), ((Op.Fill) ops.get(1)).box());
        assertEquals(5, count(m, "slab"));
        assertEquals("spruce_stairs[facing=south]", m.get("-1,10,-1"));
        assertEquals("spruce_stairs[facing=north]", m.get("9,10,5"));
        assertEquals("spruce_stairs[facing=east]", m.get("-1,10,0"));
        assertEquals("spruce_stairs[facing=west]", m.get("9,10,4"));
        assertEquals("spruce_stairs[facing=south]", m.get("1,12,1"));
        assertEquals(2 * 11 + 2 * 5 + 2 * 9 + 2 * 3 + 2 * 7 + 2 * 1, count(m, "stairs"));
    }

    @Test
    void hipOverSquareWithOddSideHasSingleRidgeBlock() {
        List<Op> ops = Roofs.hip(walls(0, 0, 4, 4, 0), "oak_stairs", 0, "oak_planks");
        Map<String, String> m = cells(ops);
        assertEquals(Box.of(new Vec3(2, 2, 2), new Vec3(2, 2, 2)), ((Op.Fill) ops.get(1)).box());
        assertEquals("oak_planks", m.get("2,2,2"));
        assertEquals(1, count(m, "oak_planks"));
        assertEquals(16 + 8, count(m, "stairs"));
    }

    @Test
    void rejectsBadArguments() {
        Box w = walls(0, 0, 4, 4, 0);
        assertThrows(EditException.class, () -> Roofs.gable(w, "oak_planks", true, 0, null, null));
        assertThrows(EditException.class, () -> Roofs.hip(w, "oak_stairs", -1, null));
        assertThrows(EditException.class, () -> Roofs.hip(w, "oak_stairs", 9, null));
    }
}
