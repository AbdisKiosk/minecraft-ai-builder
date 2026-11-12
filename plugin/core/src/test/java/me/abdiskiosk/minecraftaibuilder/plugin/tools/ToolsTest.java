package me.abdiskiosk.minecraftaibuilder.plugin.tools;

import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import me.abdiskiosk.minecraftaibuilder.common.Op;
import com.google.gson.JsonArray;
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
import me.abdiskiosk.minecraftaibuilder.common.RegionGuard;
import me.abdiskiosk.minecraftaibuilder.common.Schematic;
import me.abdiskiosk.minecraftaibuilder.plugin.BuildService;
import me.abdiskiosk.minecraftaibuilder.plugin.AssetLibrary;
import me.abdiskiosk.minecraftaibuilder.plugin.CallContext;
import me.abdiskiosk.minecraftaibuilder.common.StateArrays;
import me.abdiskiosk.minecraftaibuilder.plugin.History;
import me.abdiskiosk.minecraftaibuilder.common.BlockDiff;
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

    private class FakeBridge implements WorldEditBridge {
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
    void pasteAtPutsTheAnchorThereAndFacingTurnsTheAsset() throws Exception {
        // A 3x1x2 library asset (x, y, z) whose anchor is cell 2,0,0, facing north. Its bytes are already in the cache,
        // so the library resolves it through "get" without a download.
        // States the fake server knows, so the version-mismatch skip leaves them all in.
        String S = "oak_stairs[facing=north]", P = "oak_stairs[facing=east]";
        String[] states = {S, S, P, S, S, S};
        byte[] data = StateArrays.encode(states);
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
        Files.createDirectories(dataDir.resolve("asset-cache/7"));
        Files.write(dataDir.resolve("asset-cache/7/abcdefgh2345-" + sha + ".bin"), data);
        String[] stored = {"[2,0,0]", "\"north\""};
        service.setAssetLibrary(new AssetLibrary(dataDir, (op, args) -> JsonParser.parseString("{\"id\":\"abcdefgh2345\",\"sha256\":\"" + sha
                + "\",\"bytes\":" + data.length + ",\"size\":[3,1,2],\"url\":\"http://127.0.0.1:1/unused\""
                + (stored[0] == null ? "" : ",\"anchor\":" + stored[0]) + (stored[1] == null ? "" : ",\"facing\":" + stored[1]) + "}").getAsJsonObject()));
        CallContext.withIdentity(new CallContext.Identity(7, "a".repeat(64)), () -> {
            Op.Paste north = (Op.Paste) single("paste", args("{\"name\":\"window\",\"at\":[10,64,10]}"));
            assertEquals(new Vec3(8, 64, 10), north.origin());
            assertEquals(0, north.rotation());
            Op.Paste east = (Op.Paste) single("paste", args("{\"name\":\"window\",\"at\":[10,64,10],\"facing\":\"east\"}"));
            assertEquals(90, east.rotation());
            assertEquals(new Vec3(9, 64, 8), east.origin()); // the anchor turns to 1,0,2
            Op.Paste batched = (Op.Paste) Tools.batchOps(args("{\"origin\":[100,0,0],\"ops\":[{\"op\":\"paste\",\"name\":\"window\","
                    + "\"at\":[10,64,10],\"facing\":\"west\"}]}")).get(0);
            assertEquals(new Vec3(110, 64, 10), batched.origin(), "at is an offset from the batch origin, like origin");
            assertEquals(270, batched.rotation());
            assertEquals("give \"facing\" or \"rotation\", not both",
                    assertThrows(EditException.class, () -> single("paste", args("{\"name\":\"window\",\"at\":[0,0,0],\"facing\":\"east\",\"rotation\":90}"))).getMessage());
            assertTrue(assertThrows(EditException.class, () -> single("paste", args("{\"name\":\"window\",\"at\":[0,0,0],\"facing\":\"up\"}")))
                    .getMessage().startsWith("this asset faces north; paste turns only between north, south, east and west"));

            // Saved before anchors: at is the minimum corner, and facing has nothing to turn from.
            stored[0] = null; stored[1] = null;
            assertEquals(new Vec3(10, 64, 10),
                    ((Op.Paste) single("paste", args("{\"name\":\"window\",\"at\":[10,64,10],\"rotation\":90}"))).origin());
            assertTrue(assertThrows(EditException.class, () -> single("paste", args("{\"name\":\"window\",\"at\":[0,0,0],\"facing\":\"east\"}")))
                    .getMessage().startsWith("this blueprint has no stored facing; use origin and rotation"));

            // A floor piece faces up: it turns with rotation, never with a horizontal facing.
            stored[0] = "[1,0,1]"; stored[1] = "\"up\"";
            assertEquals(new Vec3(9, 64, 9), ((Op.Paste) single("paste", args("{\"name\":\"window\",\"at\":[10,64,10],\"facing\":\"up\"}"))).origin());
            assertTrue(assertThrows(EditException.class, () -> single("paste", args("{\"name\":\"window\",\"at\":[0,0,0],\"facing\":\"south\"}")))
                    .getMessage().startsWith("this asset faces up;"));
            return null;
        });
    }

    @Test
    void pasteTakesOriginOrAtAndFacingOnlyWithAnAnchoredAsset() {
        world.put(new Vec3(0, 64, 0), "stone");
        call("save_blueprint", "{\"name\":\"hut-1\",\"region\":[0,64,0,1,64,0]}");
        assertEquals("give exactly one of \"origin\" or \"at\"",
                assertThrows(EditException.class, () -> single("paste", args("{\"name\":\"hut-1\",\"origin\":[0,0,0],\"at\":[0,0,0]}"))).getMessage());
        assertEquals("give exactly one of \"origin\" or \"at\"",
                assertThrows(EditException.class, () -> single("paste", args("{\"name\":\"hut-1\"}"))).getMessage());
        assertEquals("\"facing\" needs \"at\" (where the anchor goes)",
                assertThrows(EditException.class, () -> single("paste", args("{\"name\":\"hut-1\",\"origin\":[0,0,0],\"facing\":\"east\"}"))).getMessage());
        // A server blueprint has no anchor: at places its minimum corner, and facing has nothing to turn from.
        Op.Paste at = (Op.Paste) single("paste", args("{\"name\":\"hut-1\",\"at\":[5,70,5],\"rotation\":180}"));
        assertEquals(new Vec3(5, 70, 5), at.origin());
        assertEquals(180, at.rotation());
        assertTrue(assertThrows(EditException.class, () -> single("paste", args("{\"name\":\"hut-1\",\"at\":[5,70,5],\"facing\":\"east\"}")))
                .getMessage().startsWith("this blueprint has no stored facing"));
    }

    @Test
    void authenticatedSavesUseTheLibraryAndLocalNamesStillWin() throws IOException {
        java.util.List<String> operations = new java.util.ArrayList<>();
        service.setAssetLibrary(new AssetLibrary(dataDir, (op, data) -> {
            operations.add(op);
            if (op.equals("list")) return JsonParser.parseString("{\"assets\":[{\"name\":\"window\",\"size\":[1,1,1],\"description\":\"oak\"}]}").getAsJsonObject();
            if (op.equals("save")) {
                assertEquals("oak window", data.get("description").getAsString());
                assertArrayEquals(new String[]{"stone"}, StateArrays.decode(java.util.Base64.getDecoder().decode(data.get("data").getAsString()), 1));
                return JsonParser.parseString("{\"saved\":\"window\",\"library\":true}").getAsJsonObject();
            }
            if (op.equals("check")) return new JsonObject();
            throw new AssertionError("local names must not request a personal asset");
        }));
        world.put(new Vec3(0, 64, 0), "stone");
        CallContext.withIdentity(new CallContext.Identity(7, "a".repeat(64)), () -> {
            JsonObject saved = call("save_blueprint", "{\"name\":\"window\",\"region\":[0,64,0,0,64,0],\"description\":\"oak window\"}").data();
            assertTrue(saved.get("library").getAsBoolean());
            assertFalse(Files.exists(dataDir.resolve("blueprints/window.json")));
            JsonObject context = registry.call("get_context", new JsonObject()).data();
            assertEquals("window", context.getAsJsonArray("assets").get(0).getAsJsonObject().get("name").getAsString());
            return null;
        });
        assertEquals(List.of("check", "save", "list"), operations);
        // A local blueprint remains usable even during a personal-library outage/name collision.
        call("save_blueprint", "{\"name\":\"window\",\"region\":[0,64,0,0,64,0]}");
        CallContext.withIdentity(new CallContext.Identity(7, "a".repeat(64)), () -> {
            assertInstanceOf(Op.Paste.class, single("paste", args("{\"name\":\"window\",\"origin\":[5,64,0]}")));
            return null;
        });
        assertEquals(3, operations.size());
    }

    @Test
    void failingLibraryDoesNotHideContextOrSilentlySaveLocally() {
        service.setAssetLibrary(new AssetLibrary(dataDir, (op, data) -> { throw new EditException("asset storage is down"); }));
        world.put(new Vec3(0, 64, 0), "stone");
        CallContext.withIdentity(new CallContext.Identity(7, "a".repeat(64)), () -> {
            assertEquals("asset storage is down", registry.call("get_context", new JsonObject()).data().get("assets_error").getAsString());
            assertEquals("asset storage is down", error("save_blueprint", "{\"name\":\"window\",\"region\":[0,64,0,0,64,0]}"));
            assertFalse(Files.exists(dataDir.resolve("blueprints/window.json")));
            EditException e = assertThrows(EditException.class, () -> single("paste", args("{\"name\":\"window\",\"origin\":[5,64,0]}")));
            assertEquals("no local blueprint 'window'; asset storage is down", e.getMessage(), "says where it looked");
            return null;
        });
    }

    @Test
    void withoutALibraryOnThePanelAnAccountCallKeepsServerBlueprints() {
        // welcome without "assets" (an older panel, or one without object storage): as if the call had no account.
        java.util.List<String> operations = new java.util.ArrayList<>();
        service.setAssetLibrary(new AssetLibrary(dataDir, (op, data) -> { operations.add(op); throw new AssertionError(op); }, () -> false));
        world.put(new Vec3(0, 64, 0), "stone");
        CallContext.withIdentity(new CallContext.Identity(7, "a".repeat(64)), () -> {
            JsonObject context = registry.call("get_context", new JsonObject()).data();
            assertFalse(context.has("assets"));
            assertFalse(context.has("assets_error"));
            String missing = assertThrows(EditException.class, () -> single("paste", args("{\"name\":\"window\",\"origin\":[5,64,0]}"))).getMessage();
            assertFalse(missing.contains("library"), missing);
            assertTrue(missing.startsWith("no saved blueprint 'window'"), missing);
            JsonObject saved = call("save_blueprint", "{\"name\":\"window\",\"region\":[0,64,0,0,64,0],\"description\":\"oak\"}").data();
            assertFalse(saved.has("library"));
            assertTrue(Files.exists(dataDir.resolve("blueprints/window.json")));
            assertInstanceOf(Op.Paste.class, single("paste", args("{\"name\":\"window\",\"origin\":[5,64,0]}")));
            return null;
        });
        assertEquals(List.of(), operations);
    }

    /** The largest asset listing get_context can carry: 30 assets, 40-character names, huge sizes, 80-character descriptions. */
    @Test
    void largestAssetListingStaysSmall() {
        JsonArray assets = new JsonArray();
        for (int i = 0; i < 40; i++) { // more than the panel sends: the plugin keeps 30
            JsonObject a = new JsonObject();
            a.addProperty("name", String.format("%02d", i) + "x".repeat(38));
            JsonArray size = new JsonArray();
            size.add(1_999_999); size.add(1); size.add(1);
            a.add("size", size);
            a.addProperty("description", "\"quoted\" \\ " + "d".repeat(69)); // 80 characters, some escaped in JSON
            assets.add(a);
        }
        JsonObject answer = new JsonObject();
        answer.add("assets", assets);
        service.setAssetLibrary(new AssetLibrary(dataDir, (op, data) -> answer));
        JsonObject plain = registry.call("get_context", new JsonObject()).data();
        JsonObject context = CallContext.withIdentity(new CallContext.Identity(7, "a".repeat(64)),
                () -> registry.call("get_context", new JsonObject()).data());
        assertEquals(AssetLibrary.MAX_LISTED, context.getAsJsonArray("assets").size());
        int added = context.toString().length() - plain.toString().length();
        // 30 x (name 40 + size 15 + description 80 + JSON punctuation and escapes) = 5,201 characters, about 1.5k tokens.
        assertEquals(5_201, added);
        assertTrue(added <= 5_500, "get_context's asset listing is capped at 5,500 characters; it was " + added);
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
        assertTrue(first.get("hint").getAsString().contains("lesson"));
        JsonObject second = registry.call("get_context", new JsonObject()).data();
        assertTrue(second.has("lessons"));
        assertFalse(second.has("feedback"), second.toString());
        assertFalse(second.has("hint"), second.toString());
    }

    /** A service whose history reads the fake world at once, as with FastAsyncWorldEdit. */
    private BuildService withHistory() {
        BuildService s = new BuildService(null, new FakeBridge() {
            @Override
            public boolean async() { return true; }
        }, config, tempDir());
        s.setRecorder(new History(null, s, new History.Settings(true, 1000, 100, 1 << 20, 4096, 1000)));
        return s;
    }

    /** Claude placed 6 oak_planks at y=64 from x=0, and a person changed {@code changed} of them to spruce_planks. */
    private String placedAndCorrected(BuildService s, int z, int changed) {
        BlockDiff.Builder d = new BlockDiff.Builder();
        for (int x = 0; x < 6; x++) {
            d.change(new Vec3(x, 64, z), "air", "oak_planks");
            world.put(new Vec3(x, 64, z), x < changed ? "spruce_planks" : "oak_planks");
        }
        Box box = Box.of(new Vec3(0, 64, z), new Vec3(5, 64, z));
        String id = s.history().record(new BuildService.JobInfo("j", "done", "done", "build", "panel mcp", "Claude", "world",
                box, 0, 0, 0, System.currentTimeMillis(), 1, null, null, null, null, null), d.build(), List.of(box), null);
        s.history().corrections(id);
        return id;
    }

    @Test
    void getContextTellsCorrectionsOnceWithTheHint() {
        BuildService s = withHistory();
        ToolRegistry r = new ToolRegistry(s, Logger.getAnonymousLogger());
        Tools.registerAll(r, s);
        String id = placedAndCorrected(s, 0, 4);
        JsonObject first = r.call("get_context", new JsonObject()).data();
        assertEquals("[{\"entry\":\"" + id + "\",\"share\":0.667,\"substitutions\":[\"oak_planks -> spruce_planks x4 at [0,64,0] [1,64,0]\"]}]",
                first.get("corrections").toString(), first.toString());
        assertTrue(first.has("hint"));
        JsonObject second = r.call("get_context", new JsonObject()).data();
        assertFalse(second.has("corrections") || second.has("hint"), second.toString());
    }

    @Test
    void theLessonToolNeedsACorrectionOrDeliveredFeedback() {
        BuildService s = withHistory();
        ToolRegistry r = new ToolRegistry(s, Logger.getAnonymousLogger());
        Tools.registerAll(r, s);
        String fixed = placedAndCorrected(s, 0, 4);
        String untouched = placedAndCorrected(s, 1, 0);
        s.lessons().feedback("d7", "Too tall", false, "panel: Ann");

        for (String refused : new String[]{"{\"text\":\"Use spruce\"}", "{\"text\":\"Use spruce\",\"entry\":\"" + untouched + "\"}",
                "{\"text\":\"Use spruce\",\"entry\":\"hnope\"}", "{\"text\":\"Keep it low\",\"entry\":\"d7\"}"}) {
            ToolResult res = r.call("lesson", JsonParser.parseString(refused).getAsJsonObject());
            assertTrue(res.error(), refused);
            assertTrue(res.data().toString().contains("must come from a correction or feedback"), res.data().toString());
        }
        assertTrue(s.lessons().list().isEmpty());

        assertFalse(r.call("lesson", JsonParser.parseString("{\"text\":\"Use spruce_planks, not oak_planks\",\"entry\":\"" + fixed + "\"}")
                .getAsJsonObject()).error());
        r.call("get_context", new JsonObject()); // delivers the feedback on d7
        assertFalse(r.call("lesson", JsonParser.parseString("{\"text\":\"Keep towers low\",\"entry\":\"d7\"}").getAsJsonObject()).error());
        assertEquals(List.of(List.of(fixed), List.of("d7")), s.lessons().list().stream().map(Lessons.Lesson::entries).toList());
    }

    /** The lesson tool is left out of tools/list, yet Claude can call it over MCP. */
    @Test
    void theUnlistedLessonToolIsCallableOverMcp() {
        service.lessons().feedback("h1", "Torches look cheap", false, "panel: Ann");
        service.lessons().takeFeedback();
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

    /** WorldGuard with a spawn and a shop; it would refuse every edit if protection were on. */
    private static final class FakeRegions implements RegionGuard {
        final Map<String, Box> all = Map.of(
                "spawn", Box.of(new Vec3(-20, -64, -20), new Vec3(20, 319, 20)),
                "shop", Box.of(new Vec3(100, 60, 100), new Vec3(110, 80, 110)));

        @Override
        public String name() {
            return "WorldGuard test";
        }

        @Override
        public List<String> denied(UUID actor, String world, Box box) {
            return List.of("spawn");
        }

        @Override
        public Map<String, Box> regions(String world) {
            return all;
        }

        @Override
        public Optional<Box> region(String world, String name) {
            return Optional.ofNullable(all.get(name));
        }
    }

    @Test
    void worldGuardRegionsAreBuildTargetsByName() {
        service.setGuard(new FakeRegions(), false);
        Args a = new Args(JsonParser.parseString("{\"region\":\"@spawn\"}").getAsJsonObject(), caller, service);
        assertEquals(Box.of(new Vec3(-20, -64, -20), new Vec3(20, 319, 20)), a.region("region"));
        String unknown = assertThrows(EditException.class, () -> new Args(JsonParser.parseString("{\"region\":\"@nowhere\"}")
                .getAsJsonObject(), caller, service).region("region")).getMessage();
        assertTrue(unknown.contains("WorldGuard regions work too"), unknown);
    }

    @Test
    void regionsAreOnlyAProtectionWhenAskedFor() {
        BuildService.Caller ann = new BuildService.Caller(UUID.randomUUID(), "Ann", "world", new Vec3(0, 64, 0), 0, List.of("Ann"), 0, 255);
        Box inSpawn = Box.of(new Vec3(0, 64, 0), new Vec3(0, 70, 0));
        service.setGuard(new FakeRegions(), false);
        service.checkEditable(ann, inSpawn, "pillar");
        service.setGuard(new FakeRegions(), true);
        String refused = assertThrows(EditException.class, () -> service.checkEditable(ann, inSpawn, "pillar")).getMessage();
        assertTrue(refused.contains("WorldGuard region spawn, where Ann may not build"), refused);
    }

    @Test
    void getContextListsRegionsNearestFirst() {
        Map<String, Box> all = new FakeRegions().all;
        assertEquals("{\"shop\":[100,60,100,110,80,110],\"spawn\":[-20,-64,-20,20,319,20]}",
                Tools.regions(all, new Vec3(105, 64, 105)).toString());
        assertEquals("{\"spawn\":[-20,-64,-20,20,319,20],\"shop\":[100,60,100,110,80,110]}",
                Tools.regions(all, new Vec3(0, 64, 0)).toString());
        Map<String, Box> many = new HashMap<>();
        for (int i = 0; i < 40; i++) many.put("plot" + i, Box.of(new Vec3(i * 10, 0, 0), new Vec3(i * 10 + 9, 10, 9)));
        JsonObject listed = Tools.regions(many, new Vec3(0, 64, 0));
        assertEquals(31, listed.size());
        assertTrue(listed.has("plot0"));
        assertEquals(10, listed.get("(more, use @name)").getAsInt());
    }

    /** A library whose one asset, "window", holds these states in a row along x, served over HTTP as a paste fetches it. */
    private <T> T withAsset(String[] states, java.util.function.Supplier<T> work) throws Exception {
        byte[] data = StateArrays.encode(states);
        com.sun.net.httpserver.HttpServer storage = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        storage.createContext("/asset", exchange -> {
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        storage.start();
        try {
            JsonObject meta = new JsonObject();
            meta.addProperty("id", "abcdefgh2345");
            meta.addProperty("sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data)));
            meta.addProperty("bytes", data.length);
            meta.add("size", JsonParser.parseString("[" + states.length + ",1,1]"));
            meta.addProperty("url", "http://127.0.0.1:" + storage.getAddress().getPort() + "/asset");
            service.setAssetLibrary(new AssetLibrary(dataDir, (op, args) -> meta));
            return CallContext.withIdentity(new CallContext.Identity(7, "a".repeat(64)), work);
        } finally {
            storage.stop(0);
        }
    }

    @Test
    void libraryPastesLeaveOutStatesThisServerLacksAndSayWhich() throws Exception {
        // Saved on a newer server: a block this one lacks, and a property value it does not have.
        String[] states = {"oak_stairs[facing=east]", "copper_bulb[lit=true]", "oak_stairs[facing=up]", "air", "copper_bulb[lit=false]"};
        withAsset(states, () -> {
            Args a = args("{\"name\":\"window\",\"origin\":[0,64,0]}");
            Op.Paste paste = (Op.Paste) single("paste", a);
            assertEquals(List.of("0,0,0=oak_stairs[facing=east]", "3,0,0=air"), cells(paste), "left-out cells keep what is there");
            assertEquals(List.of("copper_bulb", "oak_stairs[facing=up]"), a.skipped().stream().sorted().toList(),
                    "a missing block by name, a bad value by state");
            // A blueprint Claude wrote is still refused as a whole.
            String refused = error("build", "{\"ops\":[{\"op\":\"paste\",\"origin\":[0,64,0],"
                    + "\"blueprint\":{\"palette\":{\"c\":\"copper_bulb\"},\"layers\":[[\"c\"]]}}]}");
            assertTrue(refused.contains("unknown block 'copper_bulb'"), refused);
            return null;
        });
    }

    @Test
    void theSkippedListInAResultIsCapped() throws Exception {
        String[] states = new String[14];
        states[0] = "oak_stairs[facing=east]";
        for (int i = 1; i < states.length; i++) states[i] = "new_block_" + (char) ('a' + i);
        withAsset(states, () -> {
            // Every paste of a batch adds to the call's list.
            Args a = args("{\"ops\":[{\"op\":\"paste\",\"name\":\"window\",\"origin\":[0,64,0]},"
                    + "{\"op\":\"paste\",\"name\":\"window\",\"origin\":[0,70,0]}]}");
            assertEquals(2, Tools.batchOps(a).size());
            JsonObject result = new JsonObject();
            Tools.addSkipped(result, a.skipped());
            assertEquals(10, result.getAsJsonArray("skipped").size());
            assertEquals("new_block_b", result.getAsJsonArray("skipped").get(0).getAsString(), "sorted");
            assertEquals(13, result.get("skipped_total").getAsInt());
            JsonObject clean = new JsonObject();
            Tools.addSkipped(clean, java.util.Set.of());
            assertEquals("{}", clean.toString());
            return null;
        });
        String[] none = {"copper_bulb", "air"};
        String message = assertThrows(EditException.class, () -> withAsset(none,
                () -> single("paste", args("{\"name\":\"window\",\"origin\":[0,64,0]}"))), "air alone is not a paste").getMessage();
        assertEquals("this server has none of the blocks of asset 'window': copper_bulb", message);
    }
}
