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
import me.abdiskiosk.minecraftaibuilder.common.Schematic;
import me.abdiskiosk.minecraftaibuilder.plugin.BuildService;
import me.abdiskiosk.minecraftaibuilder.plugin.Lessons;
import me.abdiskiosk.minecraftaibuilder.plugin.mcp.McpEndpoint;
import me.abdiskiosk.minecraftaibuilder.plugin.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
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
            World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                    (proxy, method, params) -> switch (method.getName()) {
                        case "getName" -> "world";
                        case "getMaxHeight" -> 256;
                        default -> method.getReturnType() == boolean.class ? Boolean.FALSE : method.getReturnType() == int.class ? 0 : null;
                    });
            Bukkit.setServer((Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                    (proxy, method, params) -> {
                        Object answer = switch (method.getName()) {
                            case "isPrimaryThread" -> Boolean.TRUE;
                            case "getLogger" -> logger;
                            case "getName", "getVersion", "getBukkitVersion", "toString" -> "test";
                            case "getOnlinePlayers" -> List.of();
                            case "getWorlds" -> List.of(world);
                            case "getWorld" -> "world".equals(params[0]) ? world : null;
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
        /** Any schematic file holds one east-facing stair on top of air. */
        public Schematic readSchematic(Path file, long maxVolume) {
            return new Schematic(1, 2, 1, new String[]{"air", "oak_stairs[facing=east]"});
        }
    }

    private final PluginConfig config = new PluginConfig(4000, 2_000_000L, 262_144L, 0, 30,
            List.of(), "", null, List.of(), true, "", "", null, null, null);
    private final Path dataDir = tempDir();
    private final BuildService service = new BuildService(null, new FakeBridge(), config, dataDir);

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("aibuilder-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Op single(String type, Args a) {
        List<Op> ops = Tools.parseOp(type, a);
        assertEquals(1, ops.size());
        return ops.get(0);
    }
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
        assertEquals(expected, ((Op.Fill) single("fill", args("{\"region\":[3,70,9,1,64,2],\"pattern\":\"stone\"}"))).box());
        assertEquals(expected, ((Op.Fill) single("fill", args("{\"region\":{\"min\":[1,64,2],\"max\":[3,70,9]},\"pattern\":\"stone\"}"))).box());
        assertEquals("\"region\" must be \"selection\", \"@area\" or [x1,y1,z1,x2,y2,z2]", error("fill", "{\"region\":[1,2,3],\"pattern\":\"stone\"}"));
        assertTrue(error("fill", "{\"region\":\"@plot\",\"pattern\":\"stone\"}").startsWith("unknown area \"@plot\""));
    }

    @Test
    void fillModesCoverTheOldTools() {
        String region = "\"region\":[0,0,0,4,4,4],\"pattern\":\"stone\"";
        assertInstanceOf(Op.Fill.class, single("fill", args("{" + region + "}")));
        assertInstanceOf(Op.Walls.class, single("fill", args("{" + region + ",\"mode\":\"walls\"}")));
        assertInstanceOf(Op.Outline.class, single("fill", args("{" + region + ",\"mode\":\"outline\"}")));
        Op.Replace replace = (Op.Replace) single("fill", args("{" + region + ",\"from\":\"dirt,grass_block\"}"));
        assertEquals("dirt,grass_block", replace.from());
        assertEquals("mode must be solid, walls or outline", error("fill", "{" + region + ",\"mode\":\"hollow\"}"));
        assertEquals("\"from\" only works with mode solid", error("fill", "{" + region + ",\"mode\":\"walls\",\"from\":\"dirt\"}"));
        // The old names stay valid for batch ops and REST scripts.
        assertInstanceOf(Op.Walls.class, single("walls", args("{" + region + "}")));
        assertInstanceOf(Op.Outline.class, single("outline", args("{" + region + "}")));
        assertInstanceOf(Op.Replace.class, single("replace", args("{" + region + ",\"from\":\"dirt\"}")));
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
        Op.Blocks blocks = (Op.Blocks) single("blocks", args("{\"origin\":[10,64,10],\"blocks\":{\"oak_fence\":[[0,0,0],[1,0,0]],\"torch\":[[0,1,0]]}}"));
        assertEquals(3, blocks.blocks().size());
        assertEquals(new Op.BlockAt(new Vec3(11, 64, 10), "oak_fence"), blocks.blocks().get(1));
        assertTrue(error("set_blocks", "{\"blocks\":{\"stone\":[[0,0]]}}").startsWith("blocks[\"stone\"][0] must be [x,y,z] or a box"));
        assertTrue(error("set_blocks", "{\"blocks\":\"stone\"}").startsWith("\"blocks\" must be"));
    }

    @Test
    void groupedBlocksTakeBoxesAndCompleteDoors() {
        Op.Blocks blocks = (Op.Blocks) single("blocks", args("{\"origin\":[10,64,10],\"blocks\":{"
                + "\"oak_log\":[[0,0,0,0,2,0],[4,0,0]],\"oak_door[facing=south]\":[[2,0,0]]}}"));
        assertEquals(List.of(
                new Op.BlockAt(new Vec3(10, 64, 10), "oak_log"), new Op.BlockAt(new Vec3(10, 65, 10), "oak_log"),
                new Op.BlockAt(new Vec3(10, 66, 10), "oak_log"), new Op.BlockAt(new Vec3(14, 64, 10), "oak_log"),
                new Op.BlockAt(new Vec3(12, 64, 10), "oak_door[facing=south]"),
                new Op.BlockAt(new Vec3(12, 65, 10), "oak_door[facing=south,half=upper]")), blocks.blocks());
        assertTrue(error("set_blocks", "{\"blocks\":{\"stone\":[[0,0,0,1000,100,1000]]}}").contains("use fill"));
    }

    @Test
    void roofTreeAndLightAreBuildOps() {
        List<Op> ops = Tools.batchOps(args("{\"origin\":[100,64,0],\"vars\":{\"P\":\"oak_planks\"},\"ops\":["
                + "{\"op\":\"roof\",\"region\":[0,5,0,8,5,6],\"stairs\":\"oak_stairs\",\"gable\":\"$P\"},"
                + "{\"op\":\"tree\",\"at\":[12,0,3],\"type\":\"birch\"},"
                + "{\"op\":\"light\",\"region\":[0,0,0,8,4,6]}]}"));
        // 9 wide, 7 deep: the ridge runs along x, the longer side, and the slopes face north and south.
        Op.Blocks stairs = (Op.Blocks) ops.get(0);
        assertTrue(stairs.blocks().contains(new Op.BlockAt(new Vec3(99, 69, -1), "oak_stairs[facing=south]")));
        assertTrue(stairs.blocks().contains(new Op.BlockAt(new Vec3(109, 69, 7), "oak_stairs[facing=north]")));
        assertTrue(ops.stream().anyMatch(op -> op instanceof Op.Fill f && f.pattern().equals("oak_planks")));
        assertEquals(new Op.Tree(new Vec3(112, 64, 3), "birch"), ops.get(ops.size() - 2));
        assertEquals(new Op.Light(Box.of(new Vec3(100, 64, 0), new Vec3(108, 68, 6)), "torch"), ops.get(ops.size() - 1));

        assertEquals("ops[0] (roof): style must be gable or hip",
                error("build", "{\"ops\":[{\"op\":\"roof\",\"region\":[0,5,0,8,5,6],\"stairs\":\"oak_stairs\",\"style\":\"flat\"}]}"));
        assertTrue(error("build", "{\"ops\":[{\"op\":\"dome\"}]}").startsWith("ops[0] (dome): unknown op 'dome'; use fill, blocks, roof, tree"));
        // One op may be given without the brackets.
        assertEquals(1, Tools.batchOps(args("{\"ops\":{\"op\":\"tree\",\"at\":[0,64,0]}}")).size());
    }

    @Test
    void checkRegionLimitSetsHowManyCoordinatesAreListed() {
        for (int x = 0; x <= 8; x++) world.put(new Vec3(x, 66, 0), "torch");
        String region = "\"region\":[0,64,0,8,68,0],\"checks\":\"unsupported\"";
        assertEquals(5, call("check_region", "{" + region + "}").data().getAsJsonArray("issues").get(0).getAsJsonObject().getAsJsonArray("at").size());
        assertEquals(9, call("check_region", "{" + region + ",\"limit\":20}").data().getAsJsonArray("issues").get(0).getAsJsonObject().getAsJsonArray("at").size());
        assertEquals("limit must be 1..100", error("check_region", "{" + region + ",\"limit\":0}"));
    }

    @Test
    void checkRegionListsIssuesAsCoordinates() {
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) world.put(new Vec3(x, 64, z), "stone");
        }
        String region = "\"region\":[0,64,0,4,68,4]";
        assertEquals("{\"issues\":[]}", call("check_region", "{" + region + "}").data().toString());

        world.put(new Vec3(3, 66, 3), "stone");
        world.put(new Vec3(1, 66, 1), "sand");
        JsonObject result = call("check_region", "{" + region + "}").data();
        assertEquals("{\"issues\":[{\"kind\":\"floating\",\"count\":2,\"at\":[[1,66,1],[3,66,3]],"
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

    private static List<String> cells(Op.Paste paste) {
        List<String> out = new java.util.ArrayList<>();
        paste.blueprint().forEachCell((x, y, z, p) -> out.add(x + "," + y + "," + z + "=" + p));
        return out;
    }

    @Test
    void cloneRepeatsPastesOfTheSourceAtEachStep() {
        world.put(new Vec3(0, 64, 0), "oak_stairs[facing=east]");
        world.put(new Vec3(1, 64, 0), "stone");
        // The batch origin shifts region and to, but not step; (2,64,0) is air and is skipped.
        List<Op> ops = Tools.batchOps(args("{\"origin\":[10,0,0],\"ops\":[{\"op\":\"clone\",\"region\":[-10,64,0,-8,64,0],"
                + "\"to\":[0,70,0],\"repeat\":3,\"step\":[0,0,5],\"rotation\":90}]}"));
        assertEquals(3, ops.size());
        for (int i = 0; i < 3; i++) {
            Op.Paste paste = (Op.Paste) ops.get(i);
            assertEquals(new Vec3(10, 70, 5 * i), paste.origin());
            assertEquals(90, paste.rotation());
            assertEquals(List.of("0,0,0=oak_stairs[facing=east]", "1,0,0=stone"), cells(paste));
        }
        Op.Paste withAir = (Op.Paste) single("clone", args("{\"region\":[0,64,0,2,64,0],\"to\":[0,70,0],\"air\":true}"));
        assertEquals(3, withAir.blueprint().cellCount());
        Op.Paste flipped = (Op.Paste) single("clone", args("{\"region\":[0,64,0,2,64,0],\"to\":[0,70,0],\"flip\":\"x\"}"));
        assertEquals(List.of("1,0,0=stone", "2,0,0=oak_stairs[facing=west]"), cells(flipped));
    }

    @Test
    void cloneArgumentsAreChecked() {
        String region = "\"region\":[0,64,0,1,64,1],\"to\":[0,70,0]";
        assertEquals("repeat needs \"step\": [dx,dy,dz] between copies", error("clone", "{" + region + ",\"repeat\":2}"));
        assertEquals("repeat must be 1..64", error("clone", "{" + region + ",\"repeat\":65,\"step\":[1,0,0]}"));
        assertEquals("flip must be \"x\" or \"z\"", error("clone", "{" + region + ",\"flip\":\"y\"}"));
        assertEquals("rotation must be 0, 90, 180 or 270", error("clone", "{" + region + ",\"rotation\":45}"));
        assertTrue(error("batch", "{\"ops\":[{\"op\":\"clone\"," + region + ",\"mirror\":\"x\"}]}").startsWith("ops[0] (clone): unknown argument \"mirror\""));
    }

    @Test
    void savedBlueprintsArePlacedByName() throws IOException {
        world.put(new Vec3(0, 64, 0), "oak_stairs[facing=east]");
        world.put(new Vec3(2, 64, 0), "stone");
        JsonObject saved = call("save_blueprint", "{\"name\":\"hut-1\",\"region\":[0,64,0,2,65,0]}").data();
        assertEquals("{\"saved\":\"hut-1\",\"size\":\"3x2x1\",\"blocks\":2}", saved.toString());
        assertTrue(Files.isRegularFile(dataDir.resolve("blueprints/hut-1.json")));

        Op.Paste paste = (Op.Paste) single("paste", args("{\"name\":\"hut-1\",\"origin\":[5,5,5]}"));
        assertEquals(new Vec3(5, 5, 5), paste.origin());
        assertTrue(paste.blueprint().palette().containsValue("oak_stairs[facing=east]"));
        assertEquals(6, paste.blueprint().cellCount()); // saved air places air

        assertEquals("name must be 1-40 of a-z, 0-9, _ and -", error("save_blueprint", "{\"name\":\"Hut\",\"region\":[0,64,0,1,64,1]}"));
        assertEquals("no saved blueprint 'barn'; saved: hut-1", error("place_blueprint", "{\"name\":\"barn\",\"origin\":[0,0,0]}"));
        assertEquals("give exactly one of \"blueprint\", \"path\" or \"name\"",
                error("place_blueprint", "{\"name\":\"hut-1\",\"path\":\"/tmp/x.json\",\"origin\":[0,0,0]}"));
        assertEquals("give exactly one of \"blueprint\", \"path\" or \"name\"", error("place_blueprint", "{\"origin\":[0,0,0]}"));
    }

    @Test
    void schematicFilesComeFromTheBridge() throws IOException {
        Path file = Files.writeString(dataDir.resolve("tower.schem"), "not read by the fake bridge");
        Op.Paste paste = (Op.Paste) single("paste", args("{\"path\":" + new com.google.gson.JsonPrimitive(file.toString()) + ",\"origin\":[0,0,0]}"));
        assertEquals(2, paste.blueprint().sizeY());
        assertEquals(List.of("0,1,0=oak_stairs[facing=east]"), cells(paste)); // air is skipped
        assertEquals("path must be a .json, .schem or .schematic file",
                error("place_blueprint", "{\"path\":\"/tmp/tower.nbt\",\"origin\":[0,0,0]}"));
    }

    @Test
    void foundationFillsFromTheGroundAsReadBeforeTheBatch() {
        for (int x = 0; x <= 2; x++) world.put(new Vec3(x, 64, 0), "stone");
        world.put(new Vec3(2, 65, 0), "dirt");
        world.put(new Vec3(0, 65, 0), "poppy");
        List<Op> ops = Tools.batchOps(args("{\"ops\":[{\"op\":\"foundation\",\"region\":[0,64,0,2,66,0],\"pattern\":\"cobblestone\"},"
                + "{\"op\":\"fill\",\"region\":[0,67,0,2,67,0],\"pattern\":\"oak_planks\"}]}"));
        assertEquals(List.of(new Op.Fill(Box.of(new Vec3(0, 65, 0), new Vec3(1, 66, 0)), "cobblestone"),
                new Op.Fill(Box.of(new Vec3(2, 66, 0), new Vec3(2, 66, 0)), "cobblestone"),
                new Op.Fill(Box.of(new Vec3(0, 67, 0), new Vec3(2, 67, 0)), "oak_planks")), ops);

        // Ground already at the top: no ops, and a call with nothing else to do says so instead of failing.
        assertTrue(Tools.parseOp("foundation", args("{\"region\":[2,64,0,2,65,0],\"pattern\":\"dirt\"}")).isEmpty());
        assertEquals("{\"changed\":0,\"note\":\"nothing to change: the terrain already fits\"}",
                call("batch", "{\"ops\":[{\"op\":\"foundation\",\"region\":[2,64,0,2,65,0],\"pattern\":\"dirt\"}]}").data().toString());
        assertEquals("ops is empty", error("batch", "{\"ops\":[]}"));
    }

    @Test
    void overlayArgumentsAreChecked() {
        world.put(new Vec3(0, 64, 0), "grass_block");
        String region = "\"region\":[0,60,0,0,70,0],\"pattern\":\"dirt_path\"";
        assertEquals(List.of(new Op.Fill(Box.of(new Vec3(0, 64, 0), new Vec3(0, 64, 0)), "dirt_path")),
                Tools.parseOp("overlay", args("{" + region + "}")));
        assertEquals(List.of(new Op.Fill(Box.of(new Vec3(0, 65, 0), new Vec3(0, 66, 0)), "dirt_path")),
                Tools.parseOp("overlay", args("{" + region + ",\"raise\":2}")));
        assertEquals("give \"depth\" or \"raise\", not both", error("overlay", "{" + region + ",\"depth\":2,\"raise\":1}"));
        assertEquals("depth must be 1..16", error("overlay", "{" + region + ",\"depth\":0}"));
        assertEquals("raise must be 0..16", error("overlay", "{" + region + ",\"raise\":17}"));
        assertTrue(error("batch", "{\"ops\":[{\"op\":\"overlay\"," + region + ",\"height\":1}]}")
                .startsWith("ops[0] (overlay): unknown argument \"height\""));
    }

    @Test
    void smoothParsesWithDefaultIterations() {
        assertEquals(new Op.Smooth(Box.of(new Vec3(0, 60, 0), new Vec3(9, 70, 9)), 2),
                single("smooth", args("{\"region\":[0,60,0,9,70,9]}")));
        assertEquals("iterations must be 1..10", error("smooth", "{\"region\":[0,60,0,9,70,9],\"iterations\":0}"));
        assertEquals("iterations must be 1..10", error("smooth", "{\"region\":[0,60,0,9,70,9],\"iterations\":11}"));
    }

    @Test
    void markersArePositionsThatAnOriginDoesNotShift() {
        service.markers().set("world", "door", new Vec3(5, 64, 7));
        assertEquals(new Vec3(5, 64, 7), ((Op.Sphere) single("sphere", args("{\"center\":\"@door\",\"radius\":2,\"pattern\":\"stone\"}"))).center());
        List<Op> ops = Tools.batchOps(args("{\"origin\":\"@door\",\"ops\":["
                + "{\"op\":\"fill\",\"region\":[0,0,0,1,1,1],\"pattern\":\"stone\"},"
                + "{\"op\":\"line\",\"from\":\"@Door\",\"to\":[2,0,0],\"pattern\":\"stone\"}]}"));
        assertEquals(Box.of(new Vec3(5, 64, 7), new Vec3(6, 65, 8)), ((Op.Fill) ops.get(0)).box());
        assertEquals(new Vec3(5, 64, 7), ((Op.Line) ops.get(1)).from());
        assertEquals(new Vec3(7, 64, 7), ((Op.Line) ops.get(1)).to());
        assertEquals(new Vec3(5, 64, 7), ((Op.Blocks) single("blocks", args("{\"origin\":\"@door\",\"blocks\":[[0,0,0,\"stone\"]]}"))).blocks().get(0).pos());
    }

    @Test
    void anUnknownMarkerListsTheKnownOnes() {
        assertTrue(error("sphere", "{\"center\":\"@gate\",\"radius\":2,\"pattern\":\"stone\"}").contains("none set yet. A player sets one with /aibuilder mark <name>"));
        service.markers().set("world", "well", new Vec3(0, 64, 0));
        service.markers().set("world", "door", new Vec3(5, 64, 7));
        assertEquals("unknown marker \"@gate\" in world 'world'; markers: door, well. A player sets one with /aibuilder mark <name>.",
                error("sphere", "{\"center\":\"@gate\",\"radius\":2,\"pattern\":\"stone\"}"));
    }

    @Test
    void tellChecksItsArguments() {
        assertEquals("missing \"message\"", error("tell", "{}"));
        assertEquals("message is 257 characters; max 256", error("tell", "{\"message\":\"" + "x".repeat(257) + "\"}"));
        assertEquals("message is empty", error("tell", "{\"message\":\"\\u00a7\\n\"}"));
        assertTrue(error("tell", "{\"message\":\"hi\",\"teleport\":[0,64,0]}").startsWith("teleport needs a player. 2 players are online"));

        PluginConfig noTeleport = new PluginConfig(4000, 2_000_000L, 262_144L, 0, 30,
                List.of(), "", null, List.of(), false, "", "", null, null, null);
        ToolRegistry other = new ToolRegistry(service, Logger.getAnonymousLogger());
        Tools.registerAll(other, new BuildService(null, new FakeBridge(), noTeleport, null));
        Tool tell = other.all().stream().filter(x -> x.name().equals("tell")).findFirst().orElseThrow();
        assertEquals("teleport is off; set players.allow-teleport: true in config.yml", assertThrows(EditException.class,
                () -> tell.handler().call(args("{\"message\":\"hi\",\"teleport\":[0,64,0]}"))).getMessage());
    }

    @Test
    void getContextListsLessonsAndDeliversFeedbackOnce() {
        assertFalse(registry.call("get_context", new JsonObject()).data().has("lessons"));
        service.lessons().add("Floors are spruce_planks", Lessons.MANUAL, List.of(), "Ann");
        service.lessons().feedback("d1", "The tower is too tall", false, "panel: Ann");

        JsonObject first = registry.call("get_context", new JsonObject()).data();
        assertEquals("[\"Floors are spruce_planks\"]", first.get("lessons").toString(), first.toString());
        assertEquals("[\"d1: The tower is too tall\"]", first.get("feedback").toString());
        JsonObject second = registry.call("get_context", new JsonObject()).data();
        assertTrue(second.has("lessons"));
        assertFalse(second.has("feedback"), second.toString());
    }

    /** The lesson tool is left out of tools/list, yet Claude can call it over MCP. */
    @Test
    void theUnlistedLessonToolIsCallableOverMcp() {
        McpEndpoint mcp = new McpEndpoint(registry, "test");
        String list = mcp.handle(JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).toString();
        assertFalse(list.contains("\"lesson\""), list);

        JsonObject r = mcp.handle(JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"lesson\",\"arguments\":{\"text\":\"Use lanterns, not torches\",\"entry\":\"h1\"}}}")).getAsJsonObject();
        JsonObject result = r.getAsJsonObject("result");
        assertFalse(result.get("isError").getAsBoolean(), r.toString());
        Lessons.Lesson saved = service.lessons().list().get(0);
        assertEquals("Use lanterns, not torches", saved.text());
        assertEquals(Lessons.CLAUDE, saved.origin());
        assertEquals(List.of("h1"), saved.entries());
        assertFalse(saved.seen());

        JsonObject typo = mcp.handle(JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"lesson\",\"arguments\":{\"lesson\":\"x\"}}}")).getAsJsonObject().getAsJsonObject("result");
        assertTrue(typo.get("isError").getAsBoolean());
    }
}
