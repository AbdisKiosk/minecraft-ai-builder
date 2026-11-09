package me.abdiskiosk.minecraftaibuilder.plugin.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.abdiskiosk.minecraftaibuilder.common.BlockCatalog;
import me.abdiskiosk.minecraftaibuilder.common.BlockInfo;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.EditHandle;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.SelectionInfo;
import me.abdiskiosk.minecraftaibuilder.common.WorldEditBridge;
import me.abdiskiosk.minecraftaibuilder.plugin.BuildService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ToolsTest {

    private static final class FakeCatalog implements BlockCatalog {
        public String describe() { return "Minecraft 1.8.8"; }
        public boolean legacy() { return true; }
        public boolean strictProperties() { return false; }
        public Optional<BlockInfo> info(String type) {
            return type.equals("oak_stairs")
                    ? Optional.of(new BlockInfo("oak_stairs", Map.of("facing", List.of("north", "south", "east", "west")), List.of(), null))
                    : Optional.empty();
        }
        public List<String> suggest(String type, int max) { return type.contains("stair") ? List.of("oak_stairs") : List.of(); }
        public List<String> search(String query, int max) { return List.of("oak_stairs", "spruce_stairs"); }
    }

    private static final class FakeBridge implements WorldEditBridge {
        public String name() { return "fake"; }
        public boolean async() { return false; }
        public BlockCatalog catalog() { return new FakeCatalog(); }
        public Optional<SelectionInfo> selection(UUID player) { return Optional.empty(); }
        public EditHandle begin(UUID actor, String world) { throw new UnsupportedOperationException(); }
        public int undo(UUID actor, int steps) { return steps; }
        public String[] read(String world, Box box) { return new String[0]; }
    }

    private final BuildService service = new BuildService(null, new FakeBridge(), null);
    private final BuildService.Caller caller = new BuildService.Caller(null, "console", "world", null, 0, List.of("Ann", "Bob"), 0, 255);
    private final ToolRegistry registry = new ToolRegistry(service, Logger.getAnonymousLogger());

    ToolsTest() {
        Tools.registerAll(registry, service);
    }

    private ToolResult call(String tool, String json) {
        Tool t = registry.all().stream().filter(x -> x.name().equals(tool)).findFirst().orElseThrow();
        return t.handler().call(new Args(JsonParser.parseString(json).getAsJsonObject(), caller, service));
    }

    private String error(String tool, String json) {
        return assertThrows(EditException.class, () -> call(tool, json)).getMessage();
    }

    @Test
    void requiredNumbersAreRequired() {
        assertEquals("missing \"radius\"", error("sphere", "{\"center\":[0,64,0],\"pattern\":\"stone\"}"));
        assertEquals("missing \"height\"", error("cylinder", "{\"base\":[0,64,0],\"radius\":3,\"pattern\":\"stone\"}"));
    }

    @Test
    void typosAndWrongTypesAreNamed() {
        assertTrue(error("sphere", "{\"center\":[0,64,0],\"raduis\":3,\"pattern\":\"stone\"}").contains("unknown argument \"raduis\" for sphere"));
        assertTrue(error("sphere", "{\"center\":[0,64,0],\"radius\":\"big\",\"pattern\":\"stone\"}").contains("\"radius\" must be a number"));
        assertTrue(error("cylinder", "{\"base\":[0,64,0],\"radius\":3,\"height\":4,\"pattern\":\"stone\",\"hollow\":\"yes\"}").contains("\"hollow\" must be true or false"));
    }

    @Test
    void batchErrorsNameTheOpThatFailed() {
        String message = error("batch", "{\"ops\":[{\"op\":\"fill\",\"region\":{\"min\":[0,0,0],\"max\":[1,1,1]},\"pattern\":\"stone\"},{\"op\":\"sphere\",\"center\":[0,5,0],\"pattern\":\"stone\"}]}");
        assertEquals("ops[1] (sphere): missing \"radius\"", message);
        assertTrue(error("batch", "{\"ops\":[{\"op\":\"teleport\"}]}").startsWith("ops[0] (teleport): unknown op 'teleport'"));
    }

    @Test
    void setBlocksEntriesAreChecked() {
        String message = error("set_blocks", "{\"blocks\":[[0,0,0,\"stone\"],[1,0,0]]}");
        assertTrue(message.startsWith("blocks[1] must be [x,y,z,\"state\"]"), message);
    }

    @Test
    void undoStepsAreBounded() {
        assertEquals("steps must be between 1 and 50", error("undo", "{\"steps\":0}"));
        assertEquals("steps must be between 1 and 50", error("undo", "{\"steps\":500}"));
    }

    @Test
    void relativeCoordinatesNeedAnUnambiguousPlayer() {
        String message = error("sphere", "{\"center\":[\"~\",64,0],\"radius\":3,\"pattern\":\"stone\"}");
        assertTrue(message.contains("2 players are online (Ann, Bob); pass \"player\""), message);
    }

    @Test
    void blockInfoAnswersForValidAndUnknownBlocks() {
        JsonObject valid = call("block_info", "{\"name\":\"minecraft:oak_stairs\"}").data();
        assertTrue(valid.get("valid").getAsBoolean());
        assertEquals(4, valid.getAsJsonObject("properties").getAsJsonArray("facing").size());

        JsonObject unknown = call("block_info", "{\"name\":\"oak_stair_slab\"}").data();
        assertFalse(unknown.get("valid").getAsBoolean());
        assertEquals("oak_stairs", unknown.getAsJsonArray("closest").get(0).getAsString());
        assertEquals("Minecraft 1.8.8", unknown.get("server").getAsString());

        assertEquals(2, call("block_info", "{\"search\":\"stairs\"}").data().getAsJsonArray("matches").size());
    }
}
