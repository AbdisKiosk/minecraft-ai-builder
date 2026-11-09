package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BlockCheckTest {

    /** A tiny server that only knows stone, oak_stairs and glowstone. */
    private static final class FakeCatalog implements BlockCatalog {
        private final boolean strict;

        FakeCatalog(boolean strict) {
            this.strict = strict;
        }

        public String describe() { return "Minecraft 1.8.8"; }
        public boolean legacy() { return true; }
        public boolean strictProperties() { return strict; }
        public Optional<BlockInfo> info(String type) {
            return switch (type) {
                case "stone", "glowstone" -> Optional.of(new BlockInfo(type, Map.of(), List.of(), null));
                case "oak_stairs" -> Optional.of(new BlockInfo(type,
                        Map.of("facing", List.of("north", "south", "east", "west"), "half", List.of("bottom", "top")),
                        List.of("shape", "waterlogged"), null));
                default -> Optional.empty();
            };
        }
        public List<String> suggest(String type, int max) { return type.contains("lantern") ? List.of("glowstone") : List.of(); }
        public List<String> search(String query, int max) { return List.of(); }
    }

    private static final Box BOX = Box.of(new Vec3(0, 0, 0), new Vec3(1, 1, 1));

    private static List<String> check(boolean strict, Op... ops) {
        return BlockCheck.check(new FakeCatalog(strict), List.of(ops));
    }

    @Test
    void validOpsPass() {
        assertTrue(check(false, new Op.Fill(BOX, "70%stone,30%oak_stairs[facing=east,half=top]")).isEmpty());
        assertTrue(check(false, new Op.Replace(BOX, "stone,!glowstone", "#existing")).isEmpty());
    }

    @Test
    void unknownBlocksAreAllReportedOnceWithSuggestions() {
        var e = assertThrows(EditException.class, () -> check(false,
                new Op.Fill(BOX, "stone"), new Op.Walls(BOX, "lantern"), new Op.Fill(BOX, "lantern"), new Op.Fill(BOX, "deepslate")));
        assertTrue(e.getMessage().contains("unknown block 'lantern' on Minecraft 1.8.8. Closest: glowstone"), e.getMessage());
        assertTrue(e.getMessage().contains("unknown block 'deepslate'"), e.getMessage());
        assertEquals(1, e.getMessage().split("unknown block 'lantern'", -1).length - 1, "reported once");
        assertTrue(e.getMessage().contains("Nothing was placed"));
    }

    @Test
    void badValuesOfKnownPropertiesAreErrors() {
        var e = assertThrows(EditException.class, () -> check(false, new Op.Fill(BOX, "oak_stairs[facing=up]")));
        assertTrue(e.getMessage().contains("'up' is not a value of facing (north, south, east, west)"), e.getMessage());
    }

    @Test
    void propertiesTheServerCannotStoreAreAcceptedSilently() {
        assertTrue(check(false, new Op.Fill(BOX, "oak_stairs[facing=east,shape=inner_left,waterlogged=false]")).isEmpty());
    }

    @Test
    void unknownPropertyNamesWarnOnLegacyAndFailOnStrictServers() {
        List<String> warnings = check(false, new Op.Fill(BOX, "oak_stairs[direction=north]"));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("unknown property 'direction' was ignored"), warnings.get(0));
        assertTrue(warnings.get(0).contains("facing, half"), warnings.get(0));
        assertThrows(EditException.class, () -> check(true, new Op.Fill(BOX, "oak_stairs[direction=north]")));
    }

    @Test
    void blockOpsAndPastesAreChecked() {
        assertThrows(EditException.class, () -> check(false,
                new Op.Blocks(List.of(new Op.BlockAt(new Vec3(0, 0, 0), "stone"), new Op.BlockAt(new Vec3(1, 0, 0), "lantern")))));
        Blueprint bp = Blueprint.of(Map.of('L', "lantern"), List.of(List.of("L")));
        assertThrows(EditException.class, () -> check(false, new Op.Paste(bp, new Vec3(0, 0, 0), 0)));
    }

    @Test
    void syntaxErrorsAreReportedAsErrors() {
        assertThrows(EditException.class, () -> check(false, new Op.Fill(BOX, "oak_stairs[facing=east")));
    }
}
