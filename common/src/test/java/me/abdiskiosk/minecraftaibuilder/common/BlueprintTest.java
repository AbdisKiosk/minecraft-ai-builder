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
}
