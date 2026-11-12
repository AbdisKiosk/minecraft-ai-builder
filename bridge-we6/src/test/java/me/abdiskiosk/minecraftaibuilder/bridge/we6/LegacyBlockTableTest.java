package me.abdiskiosk.minecraftaibuilder.bridge.we6;

import me.abdiskiosk.minecraftaibuilder.common.BlockState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Runs against the real generated table, limited to the block ids that exist in Minecraft 1.8.8 (0-197). */
class LegacyBlockTableTest {

    private final LegacyBlockTable table = LegacyNames.load(id -> id <= 197);

    private int[] legacy(String state) {
        BlockState s = BlockState.parse(state);
        return table.toLegacy(s.type(), s.props()).orElseThrow(() -> new AssertionError("no legacy block for " + state));
    }

    private void assertLegacy(int id, int data, String state) {
        assertArrayEquals(new int[]{id, data}, legacy(state), state);
    }

    @Test
    void stairsKeepFacingAndHalf() {
        assertLegacy(53, 0, "oak_stairs[facing=east]");
        assertLegacy(53, 1, "oak_stairs[facing=west]");
        assertLegacy(53, 3, "oak_stairs[facing=north]");
        assertLegacy(53, 7, "oak_stairs[facing=north,half=top]");
        assertLegacy(53, 4, "oak_stairs[half=top,facing=east]");
        assertLegacy(53, 7, "oak_stairs[facing=north,half=top,shape=inner_left,waterlogged=false]"); // shape cannot be stored
    }

    @Test
    void unspecifiedPropertiesMeanTheModernDefault() {
        assertLegacy(53, 3, "oak_stairs"); // facing=north, half=bottom, like on 1.13+
        assertLegacy(53, 7, "oak_stairs[half=top]");
        assertLegacy(126, 0, "oak_slab");
        assertLegacy(17, 0, "oak_log");
    }

    @Test
    void modelDefaultsIncludePropertiesOmittedFromCompactReads() {
        BlockState stairs = BlockState.parse(table.defaultState("oak_stairs"));
        assertEquals("oak_stairs", stairs.type());
        assertEquals("north", stairs.props().get("facing"));
        assertEquals("bottom", stairs.props().get("half"));
        assertEquals("straight", stairs.props().get("shape"));
        assertEquals("air", table.defaultState("air"));
    }

    @Test
    void otherDirectionalBlocks() {
        assertLegacy(65, 5, "ladder[facing=east]");
        assertLegacy(68, 3, "wall_sign[facing=south]");
        assertLegacy(50, 5, "torch");
        assertLegacy(50, 4, "wall_torch[facing=north]");
        assertLegacy(54, 2, "chest[facing=north]");
        assertLegacy(61, 5, "furnace[facing=east]");
    }

    @Test
    void slabsAndLogs() {
        assertLegacy(126, 8, "oak_slab[type=top]");
        assertLegacy(43, 0, "stone_slab[type=double]");
        assertLegacy(17, 4, "oak_log[axis=x]");
        assertLegacy(17, 8, "oak_log[axis=z]");
        assertLegacy(17, 1, "spruce_log");
    }

    @Test
    void doorsPlaceEachHalfWithItsOwnProperties() {
        assertLegacy(64, 3, "oak_door[facing=north,half=lower]");
        assertLegacy(64, 7, "oak_door[facing=north,half=lower,open=true]");
        assertLegacy(64, 7, "oak_door[facing=north,half=lower,open=true,hinge=right]"); // hinge lives in the upper half
        assertLegacy(64, 8, "oak_door[half=upper,hinge=left]");
        assertLegacy(64, 9, "oak_door[half=upper,hinge=right]");
        assertLegacy(71, 0 + 1, "iron_door[facing=south,half=lower]");
        assertLegacy(64, 3, "oak_door"); // default half is lower
    }

    @Test
    void bedsHaveFootAndHead() {
        int[] foot = legacy("red_bed[part=foot,facing=south]");
        int[] head = legacy("red_bed[part=head,facing=south]");
        assertEquals(26, foot[0]);
        assertEquals(foot[1] | 8, head[1]);
    }

    @Test
    void liquidsPlaceStationaryButStillReadFlowing() {
        assertLegacy(9, 0, "water");
        assertLegacy(11, 0, "lava");
        assertEquals("water[level=0]", table.toModern(8, 0));
    }

    @Test
    void masksCoverEveryVariantOfWhatWasAskedFor() {
        List<int[]> logs = table.matching("oak_log", Map.of());
        assertTrue(logs.stream().allMatch(p -> p[0] == 17 && (p[1] & 3) == 0), "only oak logs, any axis");
        assertEquals(Set.of(0, 4, 8), logs.stream().map(p -> p[1]).filter(d -> d != 12).collect(Collectors.toSet()));
        assertEquals(1, table.matching("stone", Map.of()).size(), "stone must not match granite, diorite, andesite");
        assertEquals(1, table.matching("dirt", Map.of()).size(), "dirt must not match coarse dirt or podzol");
        assertEquals(8, table.matching("oak_stairs", Map.of()).size());
        assertEquals(4, table.matching("oak_stairs", Map.of("facing", "east")).size() + 2, "facing east: bottom + top");
    }

    @Test
    void readingGivesPropertiesBack() {
        assertEquals("oak_stairs[facing=north,half=top]", table.toModern(53, 7));
        assertEquals("red_wool", table.toModern(35, 14));
        assertEquals("oak_log[axis=x]", table.toModern(17, 4));
        assertEquals("ladder[facing=east]", table.toModern(65, 5));
        assertEquals("air", table.toModern(0, 0));
        assertNull(table.toModern(197 + 100, 0));
    }

    @Test
    void roundTripsEveryPlaceableState() {
        for (String type : table.names()) {
            for (LegacyBlockTable.Entry e : java.util.Objects.requireNonNull(entries(type))) {
                if (!e.place() || !e.read()) continue;
                int[] back = table.toLegacy(type, e.props()).orElseThrow();
                String a = table.toModern(back[0], back[1]);
                String b = table.toModern(e.id(), e.data());
                assertEquals(b, a, type + " " + e.props());
            }
        }
    }

    private List<LegacyBlockTable.Entry> entries(String type) {
        // every legacy data value of the type, via the public lookups
        List<LegacyBlockTable.Entry> out = new java.util.ArrayList<>();
        for (int[] p : table.matching(type, Map.of())) {
            String modern = table.toModern(p[0], p[1]);
            BlockState s = BlockState.parse(modern);
            out.add(new LegacyBlockTable.Entry(p[0], p[1], type, s.props(), true, true));
        }
        return out;
    }

    @Test
    void blocksThatDoNotExistOn18AreUnknownWithSuggestions() {
        for (String missing : List.of("lantern", "deepslate", "black_concrete", "cherry_planks", "observer", "end_rod", "mud_bricks")) {
            assertFalse(table.has(missing), missing);
        }
        assertTrue(table.suggest("lantern", 4).contains("glowstone"));
        assertTrue(table.suggest("black_concrete", 4).stream().anyMatch(n -> n.contains("black")), table.suggest("black_concrete", 4).toString());
        assertTrue(table.suggest("oak_stair", 4).contains("oak_stairs"));
        assertTrue(table.search("oak stairs", 10).contains("oak_stairs"));
    }

    @Test
    void renamedBlocksResolveThroughAliases() {
        assertTrue(table.has("short_grass"));
        assertLegacy(31, 1, "short_grass");
        assertTrue(table.has("terracotta"));
    }

    @Test
    void propertiesForBlockInfo() {
        Map<String, List<String>> stairs = table.properties("oak_stairs");
        assertEquals(List.of("north", "south", "east", "west").stream().sorted().toList(), stairs.get("facing").stream().sorted().toList());
        assertEquals(Set.of("facing", "half"), stairs.keySet());
        assertTrue(table.fixedProperties("oak_stairs").contains("shape"));
        assertTrue(table.fixedProperties("oak_stairs").contains("waterlogged"));
    }

    // ---- LegacyNames: what is actually handed to WorldEdit 6 ----

    @Test
    void multiplePropertiesSurviveTranslation() {
        assertEquals("53:4", LegacyNames.translate("oak_stairs[facing=east,half=top]", false));
        assertEquals("53:7", LegacyNames.translate("oak_stairs[half=top,facing=north]", false));
    }

    @Test
    void patternsKeepWeightsAndPassThroughSpecials() {
        assertEquals("70%98:0,30%98:1", LegacyNames.translate("70%stone_bricks,30%mossy_stone_bricks", false));
        assertEquals("35:14", LegacyNames.translate("35:14", false));
        assertEquals("#existing", LegacyNames.translate("#existing", false));
        assertEquals("50%53:7,50%1:0", LegacyNames.translate("50%oak_stairs[facing=north,half=top],50%stone", false));
    }

    @Test
    void masksExpandAndKeepNegation() {
        assertEquals("1:0", LegacyNames.translate("stone", true));
        assertEquals("1:0,3:0", LegacyNames.translate("stone,dirt", true));
        assertTrue(LegacyNames.translate("!oak_log", true).startsWith("!17:0,17:4,17:8"));
        assertEquals("#existing", LegacyNames.translate("#existing", true));
        assertEquals("!1:0 3:0", LegacyNames.translate("!stone dirt", true));
    }

    @Test
    void unknownBlocksFailWithSuggestions() {
        var e = assertThrows(me.abdiskiosk.minecraftaibuilder.common.EditException.class, () -> LegacyNames.translate("lantern", false));
        assertTrue(e.getMessage().contains("glowstone"), e.getMessage());
    }
}
