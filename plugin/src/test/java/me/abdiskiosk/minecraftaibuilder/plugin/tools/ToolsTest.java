package me.abdiskiosk.minecraftaibuilder.plugin.tools;

import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import me.abdiskiosk.minecraftaibuilder.common.Op;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.abdiskiosk.minecraftaibuilder.common.BlockCatalog;
import me.abdiskiosk.minecraftaibuilder.common.BlockInfo;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.EditHandle;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.SelectionInfo;
import me.abdiskiosk.minecraftaibuilder.common.WorldEditBridge;
import me.abdiskiosk.minecraftaibuilder.common.RegionCodec;
import me.abdiskiosk.minecraftaibuilder.plugin.BuildService;
import me.abdiskiosk.minecraftaibuilder.plugin.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
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

    /** BuildService.read runs on the server thread; this stand-in server says every thread is that thread. */
    static {
        if (Bukkit.getServer() == null) {
            Logger logger = Logger.getAnonymousLogger();
            Bukkit.setServer((Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                    (proxy, method, params) -> {
                        Object answer = switch (method.getName()) {
                            case "isPrimaryThread" -> Boolean.TRUE;
                            case "getLogger" -> logger;
                            case "getName", "getVersion", "getBukkitVersion", "toString" -> "test";
                            default -> null;
                        };
                        if (answer == null && method.getReturnType() == boolean.class) return Boolean.FALSE;
                        if (answer == null && method.getReturnType() == int.class) return 0;
                        return answer;
                    }));
        }
    }

    /** Blocks the fake world holds; everything else reads as air. */
    private final Map<Vec3, String> world = new HashMap<>();

    private final class FakeBridge implements WorldEditBridge {
        public String name() { return "fake"; }
        public boolean async() { return false; }
        public BlockCatalog catalog() { return new FakeCatalog(); }
        public Optional<SelectionInfo> selection(UUID player) { return Optional.empty(); }
        public EditHandle begin(UUID actor, String world) { throw new UnsupportedOperationException(); }
        public int undo(UUID actor, int steps) { return steps; }
        public String[] read(String worldName, Box box) {
            String[] states = new String[(int) box.volume()];
            Arrays.fill(states, "air");
            world.forEach((p, state) -> {
                if (box.contains(Box.of(p, p))) states[RegionCodec.index(box, p.x(), p.y(), p.z())] = state;
            });
            return states;
        }
    }

    private final PluginConfig config = new PluginConfig("127.0.0.1", 0, "", 4000, 2_000_000L, 262_144L, 30,
            List.of(), "", null, List.of());
    private final BuildService service = new BuildService(null, new FakeBridge(), config);
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

    private Args args(String json) {
        return new Args(JsonParser.parseString(json).getAsJsonObject(), caller, service);
    }

    @Test
    void regionTakesSixNumbers() {
        Box expected = Box.of(new Vec3(1, 64, 2), new Vec3(3, 70, 9));
        assertEquals(expected, ((Op.Fill) Tools.parseOp("fill", args("{\"region\":[3,70,9,1,64,2],\"pattern\":\"stone\"}"))).box());
        assertEquals(expected, ((Op.Fill) Tools.parseOp("fill", args("{\"region\":{\"min\":[1,64,2],\"max\":[3,70,9]},\"pattern\":\"stone\"}"))).box());
        assertEquals("\"region\" must be \"selection\" or [x1,y1,z1,x2,y2,z2]", error("fill", "{\"region\":[1,2,3],\"pattern\":\"stone\"}"));
    }

    @Test
    void fillModesCoverTheOldTools() {
        String region = "\"region\":[0,0,0,4,4,4],\"pattern\":\"stone\"";
        assertInstanceOf(Op.Fill.class, Tools.parseOp("fill", args("{" + region + "}")));
        assertInstanceOf(Op.Walls.class, Tools.parseOp("fill", args("{" + region + ",\"mode\":\"walls\"}")));
        assertInstanceOf(Op.Outline.class, Tools.parseOp("fill", args("{" + region + ",\"mode\":\"outline\"}")));
        Op.Replace replace = (Op.Replace) Tools.parseOp("fill", args("{" + region + ",\"from\":\"dirt,grass_block\"}"));
        assertEquals("dirt,grass_block", replace.from());
        assertEquals("mode must be solid, walls or outline", error("fill", "{" + region + ",\"mode\":\"hollow\"}"));
        assertEquals("\"from\" only works with mode solid", error("fill", "{" + region + ",\"mode\":\"walls\",\"from\":\"dirt\"}"));
        // The old names stay valid for batch ops and REST scripts.
        assertInstanceOf(Op.Walls.class, Tools.parseOp("walls", args("{" + region + "}")));
        assertInstanceOf(Op.Outline.class, Tools.parseOp("outline", args("{" + region + "}")));
        assertInstanceOf(Op.Replace.class, Tools.parseOp("replace", args("{" + region + ",\"from\":\"dirt\"}")));
        assertTrue(registry.has("walls") && registry.has("replace") && registry.has("get_selection"));
    }

    @Test
    void batchOriginShiftsEveryCoordinateAndVarsNameBlocks() {
        List<Op> ops = Tools.batchOps(args("{\"origin\":[100,64,-20],\"vars\":{\"W\":\"70%stone,30%andesite\"},\"ops\":["
                + "{\"op\":\"fill\",\"region\":[0,0,0,4,3,4],\"pattern\":\"$W\"},"
                + "{\"op\":\"blocks\",\"blocks\":{\"$W\":[[1,5,1]],\"oak_stairs[facing=east]\":[[2,5,2]]}},"
                + "{\"op\":\"blocks\",\"origin\":[1,1,1],\"blocks\":[[0,0,0,\"$W\"]]},"
                + "{\"op\":\"cylinder\",\"base\":[2,0,2],\"radius\":2,\"height\":3,\"pattern\":\"stone\"},"
                + "{\"op\":\"paste\",\"origin\":[0,10,0],\"blueprint\":{\"palette\":{\"$\":\"$W\",\"_\":\"air\"},\"layers\":[[\"3$_\"]]}}]}"));
        Op.Fill fill = (Op.Fill) ops.get(0);
        assertEquals(Box.of(new Vec3(100, 64, -20), new Vec3(104, 67, -16)), fill.box());
        assertEquals("70%stone,30%andesite", fill.pattern());
        Op.Blocks grouped = (Op.Blocks) ops.get(1);
        assertEquals(new Op.BlockAt(new Vec3(101, 69, -19), "70%stone,30%andesite"), grouped.blocks().get(0));
        assertEquals(new Op.BlockAt(new Vec3(102, 69, -18), "oak_stairs[facing=east]"), grouped.blocks().get(1));
        assertEquals(new Op.BlockAt(new Vec3(101, 65, -19), "70%stone,30%andesite"), ((Op.Blocks) ops.get(2)).blocks().get(0));
        assertEquals(new Vec3(102, 64, -18), ((Op.Cylinder) ops.get(3)).base());
        Op.Paste paste = (Op.Paste) ops.get(4);
        assertEquals(new Vec3(100, 74, -20), paste.origin());
        assertEquals("70%stone,30%andesite", paste.blueprint().palette().get('$'));
        assertEquals(4, paste.blueprint().sizeX());

        assertEquals("ops[0] (fill): unknown var $X; vars: W",
                error("batch", "{\"vars\":{\"W\":\"stone\"},\"ops\":[{\"op\":\"fill\",\"region\":[0,0,0,1,1,1],\"pattern\":\"$X\"}]}"));
    }

    @Test
    void groupedBlocksAreChecked() {
        Op.Blocks blocks = (Op.Blocks) Tools.parseOp("blocks", args("{\"origin\":[10,64,10],\"blocks\":{\"oak_fence\":[[0,0,0],[1,0,0]],\"torch\":[[0,1,0]]}}"));
        assertEquals(3, blocks.blocks().size());
        assertEquals(new Op.BlockAt(new Vec3(11, 64, 10), "oak_fence"), blocks.blocks().get(1));
        assertTrue(error("set_blocks", "{\"blocks\":{\"stone\":[[0,0]]}}").startsWith("blocks[\"stone\"][0] must be [x,y,z]"));
        assertTrue(error("set_blocks", "{\"blocks\":\"stone\"}").startsWith("\"blocks\" must be"));
    }

    @Test
    void checkRegionListsIssuesAsCoordinates() {
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) world.put(new Vec3(x, 64, z), "stone");
        }
        String region = "\"region\":[0,64,0,4,68,4]";
        assertEquals("{\"issues\":[]}", call("check_region", "{" + region + "}").data().toString());

        world.put(new Vec3(2, 66, 2), "stone");
        world.put(new Vec3(1, 66, 1), "sand");
        JsonObject result = call("check_region", "{" + region + "}").data();
        assertEquals("{\"issues\":[{\"kind\":\"floating\",\"count\":2,\"at\":[[1,66,1],[2,66,2]],"
                + "\"hint\":\"not connected to the ground or the region's sides\"},{\"kind\":\"unsupported\",\"count\":1,"
                + "\"at\":[[1,66,1]],\"hint\":\"breaks or falls: needs a block below, or behind it for wall blocks\"}]}",
                result.toString());
        JsonObject only = call("check_region", "{" + region + ",\"checks\":\"unsupported, dark\"}").data();
        assertEquals(1, only.getAsJsonArray("issues").size());
        assertEquals("unsupported", only.getAsJsonArray("issues").get(0).getAsJsonObject().get("kind").getAsString());
    }

    @Test
    void checkRegionNamesTheValidChecks() {
        assertEquals("unknown check 'floaty'; use floating, unsupported, halves, stairs, unconnected, dark",
                error("check_region", "{\"region\":[0,64,0,4,68,4],\"checks\":\"floating,floaty\"}"));
        assertTrue(error("check_region", "{\"region\":[0,64,0,4,68,4],\"mode\":\"full\"}").contains("unknown argument \"mode\""));
    }
}
