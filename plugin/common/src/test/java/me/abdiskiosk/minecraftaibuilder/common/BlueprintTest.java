package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BlueprintTest {

    @Test
    void expandsRunLengthsAndSkips() {
        Blueprint bp = Blueprint.of(Map.of('S', "stone", '_', "air"), List.of(List.of("3S2.", "S_S")));
        assertEquals(5, bp.sizeX());
        assertEquals(1, bp.sizeY());
        assertEquals(2, bp.sizeZ());
        assertEquals('S', bp.at(2, 0, 0));
        assertEquals(Blueprint.SKIP, bp.at(3, 0, 0));
        assertEquals('_', bp.at(1, 0, 1));
        assertEquals(Blueprint.SKIP, bp.at(4, 0, 1));
        assertEquals(6, bp.cellCount());
    }

    @Test
    void multiDigitCounts() {
        Blueprint bp = Blueprint.of(Map.of('A', "stone"), List.of(List.of("12A")));
        assertEquals(12, bp.sizeX());
    }

    @Test
    void rejectsUnknownKeysAndDigitKeys() {
        assertThrows(EditException.class, () -> Blueprint.of(Map.of('S', "stone"), List.of(List.of("SX"))));
        assertThrows(EditException.class, () -> Blueprint.of(Map.of('1', "stone"), List.of(List.of("1"))));
        assertThrows(EditException.class, () -> Blueprint.of(Map.of('S', "stone"), List.of(List.of("S3"))));
    }

    @Test
    void visitsCellsWithPatterns() {
        Blueprint bp = Blueprint.of(Map.of('W', "50%oak_planks,50%spruce_planks"), List.of(List.of("W.W"), List.of(".W")));
        List<String> seen = new ArrayList<>();
        bp.forEachCell((x, y, z, p) -> seen.add(x + "," + y + "," + z + "=" + p));
        assertEquals(List.of("0,0,0=50%oak_planks,50%spruce_planks", "2,0,0=50%oak_planks,50%spruce_planks",
                "1,1,0=50%oak_planks,50%spruce_planks"), seen);
    }

    @Test
    void repeatsRowsAndLayers() {
        Blueprint bp = Blueprint.of(Map.of('S', "stone", '_', "air"),
                List.of(List.of("3S", "2^", "S_S"), List.of("^"), List.of("3_"), List.of("2^")));
        assertEquals(5, bp.sizeY());
        assertEquals(4, bp.sizeZ());
        assertEquals('S', bp.at(1, 0, 2));
        assertEquals('_', bp.at(1, 0, 3));
        assertEquals('_', bp.at(1, 1, 3));
        assertEquals('S', bp.at(2, 1, 1));
        assertEquals('_', bp.at(0, 4, 0));
        assertEquals(Blueprint.SKIP, bp.at(0, 4, 1));
        assertEquals(2 * 12 + 3 * 3, bp.cellCount());
    }

    @Test
    void rejectsBadRepeats() {
        Map<Character, String> palette = Map.of('S', "stone");
        assertTrue(assertThrows(EditException.class, () -> Blueprint.of(palette, List.of(List.of("^")))).getMessage().contains("first layer"));
        assertTrue(assertThrows(EditException.class, () -> Blueprint.of(palette, List.of(List.of("S"), List.of("^", "S")))).getMessage().contains("no other rows"));
        assertTrue(assertThrows(EditException.class, () -> Blueprint.of(palette, List.of(List.of("S", "S^")))).getMessage().contains("bad repeat"));
        assertThrows(EditException.class, () -> Blueprint.of(palette, List.of(List.of("S", "0^"))));
        assertThrows(EditException.class, () -> Blueprint.of(Map.of('^', "stone"), List.of(List.of("^"))));
    }

    private static List<String> cells(Blueprint bp) {
        List<String> seen = new ArrayList<>();
        bp.forEachCell((x, y, z, p) -> seen.add(x + "," + y + "," + z + "=" + p));
        return seen;
    }

    @Test
    void ofStatesKeepsRegionOrder() {
        // 2 x 2 x 2 in y, z, x order
        String[] states = {"stone", "air", "dirt", "oak_stairs[facing=east]", "cave_air", "glass", "stone", "air"};
        Blueprint skip = Blueprint.ofStates(2, 2, 2, states, true);
        assertEquals(List.of("0,0,0=stone", "0,0,1=dirt", "1,0,1=oak_stairs[facing=east]", "1,1,0=glass", "0,1,1=stone"), cells(skip));
        assertEquals(5, skip.cellCount());
        assertEquals(Blueprint.SKIP, skip.at(1, 0, 0));
        assertEquals(2, skip.sizeX());
        assertEquals(2, skip.sizeY());
        assertEquals(2, skip.sizeZ());

        Blueprint withAir = Blueprint.ofStates(2, 2, 2, states, false);
        assertEquals(8, withAir.cellCount());
        assertEquals("air", withAir.palette().get(withAir.at(0, 1, 0))); // cave_air places plain air
    }

    @Test
    void ofStatesHasNoPaletteLimit() {
        String[] states = new String[200];
        for (int i = 0; i < states.length; i++) states[i] = "block_" + i;
        Blueprint bp = Blueprint.ofStates(200, 1, 1, states, true);
        assertEquals(200, bp.palette().size());
        assertEquals("block_199", bp.palette().get(bp.at(199, 0, 0)));
    }

    @Test
    void flipsMirrorCellsAndStates() {
        Blueprint bp = Blueprint.of(Map.of('S', "oak_stairs[facing=east]", 'N', "70%oak_stairs[facing=north],30%stone"),
                List.of(List.of("SN.", "N"), List.of("^")));
        Blueprint x = bp.flipped('x');
        // the short row "N" is padded to the full width, so after the flip it sits at the far end
        assertEquals(List.of("1,0,0=70%oak_stairs[facing=north],30%stone", "2,0,0=oak_stairs[facing=west]",
                "2,0,1=70%oak_stairs[facing=north],30%stone", "1,1,0=70%oak_stairs[facing=north],30%stone",
                "2,1,0=oak_stairs[facing=west]", "2,1,1=70%oak_stairs[facing=north],30%stone"), cells(x));
        assertEquals(bp.cellCount(), x.cellCount());

        Blueprint z = bp.flipped('z');
        assertEquals(List.of("0,0,0=70%oak_stairs[facing=south],30%stone", "0,0,1=oak_stairs[facing=east]",
                "1,0,1=70%oak_stairs[facing=south],30%stone"), cells(z).subList(0, 3));
        assertEquals(cells(bp), cells(z.flipped('z')));
        assertThrows(EditException.class, () -> bp.flipped('y'));
    }

    @Test
    void withoutLeavesThoseCellsAlone() {
        Blueprint bp = Blueprint.ofStates(3, 1, 2, new String[]{"stone", "copper_bulb", "air", "copper_bulb", "stone", "dirt"}, false);
        Blueprint kept = bp.without(s -> s.startsWith("copper"));
        assertEquals(4, kept.cellCount());
        assertEquals(Blueprint.SKIP, kept.at(1, 0, 0));
        assertEquals(Blueprint.SKIP, kept.at(0, 0, 1));
        assertFalse(kept.palette().containsValue("copper_bulb"));
        assertEquals(6, bp.cellCount(), "the original is unchanged");
        assertSame(bp, bp.without(s -> false));
    }
}
