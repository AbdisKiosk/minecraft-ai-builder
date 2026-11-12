package me.abdiskiosk.minecraftaibuilder.plugin.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.abdiskiosk.minecraftaibuilder.common.BlockDiff;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.RelayProtocol;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import me.abdiskiosk.minecraftaibuilder.common.WorldEditBridge;
import me.abdiskiosk.minecraftaibuilder.plugin.BuildService;
import me.abdiskiosk.minecraftaibuilder.plugin.CallContext;
import me.abdiskiosk.minecraftaibuilder.common.StateArrays;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.Tool;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.ToolResult;
import me.abdiskiosk.minecraftaibuilder.plugin.History;
import me.abdiskiosk.minecraftaibuilder.plugin.PluginConfig;
import me.abdiskiosk.minecraftaibuilder.plugin.mcp.McpEndpoint;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.ToolRegistry;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.Tools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The plugin's relay client against a stand-in panel: requests reach the plugin without an open port. */
class RelayClientTest {

    private static final String TOKEN = "aibs_relaytest";

    private FakePanel panel;
    private RelayClient relay;
    private History history;
    private McpEndpoint mcp;
    private RestApi api;
    private BuildService service;
    private ToolRegistry registry;
    private String url;

    @BeforeEach
    void start() throws Exception {
        panel = new FakePanel(TOKEN);
        url = "http://127.0.0.1:" + panel.listen();

        Path dir = Files.createTempDirectory("relay");
        WorldEditBridge bridge = (WorldEditBridge) Proxy.newProxyInstance(WorldEditBridge.class.getClassLoader(),
                new Class<?>[]{WorldEditBridge.class}, (p, m, a) -> m.getName().equals("name") ? "fake" : null);
        PluginConfig config = new PluginConfig(4000, 2_000_000L, 262_144L, 0, 30,
                List.of(), "", null, List.of(), true, "", "", null, null, null);
        service = new BuildService(null, bridge, config, dir);
        History.Settings settings = new History.Settings(true, 1000, 100, 1 << 20, 4096, 1000);
        history = new History(null, service, settings);
        registry = new ToolRegistry(service, Logger.getAnonymousLogger());
        Tools.registerAll(registry, service);
        mcp = new McpEndpoint(registry, "test");
        api = new RestApi(registry);
        relay = client(TOKEN);
        relay.start();
    }

    private RelayClient client(String token) {
        return new RelayClient(url, token, mcp, api, history, null, null, service, () -> {
            JsonObject o = new JsonObject();
            o.addProperty("mc", "1.21.11");
            return o;
        }, Logger.getAnonymousLogger());
    }

    @AfterEach
    void stop() throws Exception {
        relay.stop();
        panel.stop(1000);
    }

    private static JsonObject frame(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void saysHelloAndAnswersMcpAndRestRequests() throws Exception {
        JsonObject hello = panel.hello();
        assertEquals(RelayProtocol.VERSION, hello.get("protocol").getAsInt());
        assertEquals("1.21.11", hello.get("mc").getAsString());
        assertTrue(hello.get("history").getAsBoolean());

        JsonObject tools = panel.request(frame("{\"t\":\"api\",\"tool\":\"tools\"}"));
        assertEquals(200, tools.get("status").getAsInt());
        assertTrue(tools.get("body").toString().contains("\"block_info\""), tools.toString());
        assertTrue(relay.status().startsWith("connected"), relay.status());

        JsonObject init = panel.request(frame("{\"t\":\"mcp\",\"body\":{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-06-18\"}}}"));
        assertEquals(200, init.get("status").getAsInt());
        assertEquals("2025-06-18", init.getAsJsonObject("body").getAsJsonObject("result").get("protocolVersion").getAsString());

        // A notification has no answer: the panel passes the 202 on with an empty body.
        JsonObject note = panel.request(frame("{\"t\":\"mcp\",\"body\":{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}}"));
        assertEquals(202, note.get("status").getAsInt());
        assertTrue(!note.has("body") || note.get("body").isJsonNull(), note.toString());

        JsonObject list = panel.request(frame("{\"t\":\"mcp\",\"body\":{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}}"));
        assertTrue(list.get("body").toString().contains("\"build\""), list.toString());

        JsonObject unknown = panel.request(frame("{\"t\":\"api\",\"tool\":\"no_such_tool\",\"args\":{}}"));
        assertEquals(404, unknown.get("status").getAsInt());
        assertEquals(400, panel.request(frame("{\"t\":\"nonsense\"}")).get("status").getAsInt());
    }

    @Test
    void servesHistoryAndSendsJobEvents() throws Exception {
        panel.hello();
        Box box = Box.of(new Vec3(0, 64, 0), new Vec3(1, 64, 0));
        BlockDiff diff = BlockDiff.of(box, new String[]{"air", "stone"}, new String[]{"oak_planks", "stone"});
        String id = history.record(new BuildService.JobInfo("j1", "done", "done", "fill", "test", "Alex", "world", box,
                2, 2, 1, System.currentTimeMillis(), 5, null, null, null, null, null), diff, List.of(box), null);

        JsonObject page = panel.request(frame("{\"t\":\"history\",\"op\":\"list\",\"args\":{}}"));
        assertEquals(200, page.get("status").getAsInt(), page.toString());
        assertTrue(page.get("body").toString().contains(id), page.toString());

        JsonObject get = frame("{\"t\":\"history\",\"op\":\"get\"}");
        JsonObject args = new JsonObject();
        args.addProperty("id", id);
        get.add("args", args);
        JsonObject entry = panel.request(get).getAsJsonObject("body");
        assertEquals(1, entry.get("added").getAsInt());
        assertEquals("oak_planks", entry.getAsJsonArray("types").get(0).getAsJsonObject().get("type").getAsString());

        assertEquals(400, panel.request(frame("{\"t\":\"history\",\"op\":\"get\",\"args\":{}}")).get("status").getAsInt());
        assertEquals(404, panel.request(frame("{\"t\":\"draft\",\"op\":\"list\"}")).get("status").getAsInt());

        relay.onJob(RelayProtocol.JOB_START, new BuildService.JobInfo("j7", "running", "build", "fill", "test", "Alex", "world",
                box, 100, 40, 40, System.currentTimeMillis(), 0, null, null, null, null, null));
        JsonObject event = panel.events.poll(10, TimeUnit.SECONDS);
        assertNotNull(event, "no job event arrived");
        assertEquals(RelayProtocol.JOB_START, event.get("event").getAsString());
        assertEquals("j7", event.getAsJsonObject("job").get("id").getAsString());
        assertEquals(40, event.getAsJsonObject("job").get("processed").getAsInt());
    }

    @Test
    void servesLessonsAndKeepsFeedbackForClaude() throws Exception {
        assertTrue(panel.hello().get("lessons").getAsBoolean());

        JsonObject added = panel.request(frame("{\"t\":\"lessons\",\"op\":\"add\",\"args\":{\"text\":\"Roofs are dark_oak\",\"by\":\"panel: Ann\"}}"));
        assertEquals(200, added.get("status").getAsInt(), added.toString());
        JsonObject lesson = added.getAsJsonObject("body").getAsJsonObject("lesson");
        assertEquals("manual", lesson.get("origin").getAsString());
        assertEquals("panel: Ann", lesson.get("by").getAsString());
        String id = lesson.get("id").getAsString();

        JsonObject fb = panel.request(frame("{\"t\":\"lessons\",\"op\":\"feedback\",\"args\":{\"id\":\"d1\",\"feedback\":\"Too dark\",\"remember\":true}}"));
        assertEquals(200, fb.get("status").getAsInt(), fb.toString());
        assertEquals("feedback", fb.getAsJsonObject("body").getAsJsonObject("lesson").get("origin").getAsString());

        JsonObject list = panel.request(frame("{\"t\":\"lessons\",\"op\":\"list\"}")).getAsJsonObject("body");
        assertEquals(2, list.getAsJsonArray("lessons").size());
        assertEquals(12, list.get("max").getAsInt());
        assertEquals("unavailable", list.getAsJsonArray("lessons").get(0).getAsJsonObject()
                .getAsJsonObject("health").get("status").getAsString());
        assertEquals(2, panel.request(frame("{\"t\":\"lessons\",\"op\":\"seen\"}")).getAsJsonObject("body").get("seen").getAsInt());

        JsonObject edited = panel.request(frame("{\"t\":\"lessons\",\"op\":\"edit\",\"args\":{\"id\":\"" + id + "\",\"text\":\"Roofs are spruce\"}}"));
        assertEquals("Roofs are spruce", edited.getAsJsonObject("body").getAsJsonObject("lesson").get("text").getAsString());
        assertEquals(200, panel.request(frame("{\"t\":\"lessons\",\"op\":\"delete\",\"args\":{\"id\":\"" + id + "\"}}")).get("status").getAsInt());
        assertEquals(404, panel.request(frame("{\"t\":\"lessons\",\"op\":\"delete\",\"args\":{\"id\":\"" + id + "\"}}")).get("status").getAsInt());
        assertEquals(400, panel.request(frame("{\"t\":\"lessons\",\"op\":\"add\",\"args\":{\"text\":\" \"}}")).get("status").getAsInt());
        assertEquals(400, panel.request(frame("{\"t\":\"lessons\",\"op\":\"nope\"}")).get("status").getAsInt());

        // Claude gets the feedback once, in get_context; the remembered lesson stays.
        JsonObject context = new JsonObject();
        service.lessons().addTo(context);
        assertEquals("[\"d1: Too dark\"]", context.get("feedback").toString());
        assertEquals("[\"Too dark\"]", context.get("lessons").toString());
    }

    @Test
    void servesCorrectionMeasurementsThroughTheExistingHistoryFrame() throws Exception {
        panel.hello();
        Box box = Box.of(new Vec3(0, 64, 0), new Vec3(0, 64, 0));
        BlockDiff diff = new BlockDiff.Builder().change(box.min(), "air", "stone").build();
        String id = history.record(new BuildService.JobInfo("j", "done", "done", "build", "panel mcp", "Claude", "world", box,
                1, 1, 1, System.currentTimeMillis(), 1, null, null, null, null, null), diff, List.of(box), null);
        JsonObject reply = panel.request(frame("{\"t\":\"history\",\"op\":\"correctionStats\",\"args\":{}}"));
        assertEquals(200, reply.get("status").getAsInt());
        JsonObject row = reply.getAsJsonObject("body").getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals(id, row.get("id").getAsString());
        assertEquals(1, row.get("placed").getAsInt());
        assertTrue(!row.has("corrected"), "listing statistics needs no world read and does not turn unknown into zero");
    }

    /** Tools read the world through Bukkit; this stand-in says every thread is the server thread. */
    private static void fakeServer() {
        if (org.bukkit.Bukkit.getServer() == null) {
            var world = (org.bukkit.World) Proxy.newProxyInstance(org.bukkit.World.class.getClassLoader(), new Class<?>[]{org.bukkit.World.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "getName" -> "world";
                        case "getMaxHeight" -> 256;
                        default -> null;
                    });
            org.bukkit.Bukkit.setServer((org.bukkit.Server) Proxy.newProxyInstance(org.bukkit.Server.class.getClassLoader(), new Class<?>[]{org.bukkit.Server.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "isPrimaryThread" -> true;
                        case "getOnlinePlayers" -> List.of();
                        case "getWorlds" -> List.of(world);
                        case "getWorld" -> world;
                        case "getLogger" -> Logger.getAnonymousLogger();
                        case "getName", "getVersion", "getBukkitVersion" -> "test";
                        default -> null;
                    }));
        }
    }

    /** A test tool that reports what the tools decide: the caller's account and whether it has a library. */
    private void registerProbe() {
        registry.register(new Tool("library_probe", "test", new JsonObject(), args -> {
            JsonObject out = new JsonObject();
            var identity = CallContext.identity();
            out.addProperty("user", identity == null ? 0 : identity.user());
            out.addProperty("library", service.personalLibrary() != null);
            if (args.has("name")) out.addProperty("blocks", service.personalLibrary().load(args.str("name"), 10).blueprint().cellCount());
            return ToolResult.of(out);
        }).unlisted());
    }

    @Test
    void aPanelWithoutALibraryLeavesAccountCallsOnServerBlueprints() throws Exception {
        fakeServer();
        registerProbe();
        panel.hello();
        panel.send("{\"t\":\"welcome\",\"archive\":false}"); // an older panel, or one without object storage
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        panel.assetHandler = request -> { asked.incrementAndGet(); return new JsonObject(); };
        String key = "a".repeat(64);
        JsonObject probe = panel.request(frame("{\"t\":\"api\",\"tool\":\"library_probe\",\"user\":7,\"assetKey\":\"" + key + "\",\"args\":{}}"));
        assertEquals(7, probe.getAsJsonObject("body").get("user").getAsInt(), "the account is still known");
        assertFalse(probe.getAsJsonObject("body").get("library").getAsBoolean());
        // get_context and save_blueprint decide by the same personalLibrary(): ToolsTest covers them without a library.
        JsonObject paste = panel.request(frame("{\"t\":\"api\",\"tool\":\"build\",\"user\":7,\"assetKey\":\"" + key
                + "\",\"args\":{\"ops\":[{\"op\":\"paste\",\"name\":\"window\",\"origin\":[0,64,0]}]}}"));
        String error = paste.getAsJsonObject("body").toString();
        assertTrue(error.contains("no saved blueprint 'window'"), error);
        assertFalse(error.contains("library"), error);
        assertEquals(0, asked.get(), "no asset frame goes to a panel that has no library");
    }

    @Test
    void anAccountCallWithoutAGrantRunsButCannotUseTheLibrary() throws Exception {
        registerProbe();
        panel.hello();
        // The panel had too many calls open on this socket to issue another grant.
        JsonObject plain = panel.request(frame("{\"t\":\"api\",\"tool\":\"library_probe\",\"user\":7,\"args\":{}}"));
        assertEquals(200, plain.get("status").getAsInt(), plain.toString());
        assertTrue(plain.getAsJsonObject("body").get("library").getAsBoolean());
        JsonObject asset = panel.request(frame("{\"t\":\"api\",\"tool\":\"library_probe\",\"user\":7,\"args\":{\"name\":\"window\"}}"));
        assertTrue(asset.getAsJsonObject("body").toString().contains("the panel is busy and could not open your asset library"), asset.toString());
        JsonObject bad = panel.request(frame("{\"t\":\"api\",\"tool\":\"library_probe\",\"user\":7,\"assetKey\":\"../x\",\"args\":{}}"));
        assertEquals(400, bad.get("status").getAsInt(), bad.toString());
    }

    @Test
    void thePanelsRefusalReachesClaudeInItsOwnWords() throws Exception {
        registerProbe();
        panel.hello();
        panel.assetStatus = 404;
        panel.assetHandler = request -> frame("{\"error\":\"no asset 'window' in your library\"}");
        JsonObject asset = panel.request(frame("{\"t\":\"api\",\"tool\":\"library_probe\",\"user\":7,\"assetKey\":\""
                + "a".repeat(64) + "\",\"args\":{\"name\":\"window\"}}"));
        assertEquals("no asset 'window' in your library", asset.getAsJsonObject("body").get("error").getAsString(), asset.toString());
    }

    @Test
    void authenticatedMcpAndRestCallsFetchAssetsAndRestoreWorkerIdentity() throws Exception {
        fakeServer();
        byte[] bytes = StateArrays.encode(new String[]{"stone"});
        var storage = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicInteger downloads = new java.util.concurrent.atomic.AtomicInteger();
        storage.createContext("/region", exchange -> {
            downloads.incrementAndGet(); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        storage.start();
        try {
            JsonObject meta = frame("{\"id\":\"abcdefgh2345\",\"size\":[1,1,1]}");
            meta.addProperty("bytes", bytes.length);
            meta.addProperty("sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
            meta.addProperty("url", "http://127.0.0.1:" + storage.getAddress().getPort() + "/region");
            java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
            panel.assetHandler = request -> {
                assertEquals("get", request.get("op").getAsString());
                assertTrue(List.of("a".repeat(64), "b".repeat(64)).contains(request.get("assetKey").getAsString()));
                assertEquals("window", request.getAsJsonObject("args").get("name").getAsString());
                assertTrue(!request.has("user"), "the panel authorizes a grant, not a plugin's choice of owner");
                requests.incrementAndGet(); return meta;
            };
            registry.register(new Tool("asset_probe", "test", new JsonObject(), args -> {
                JsonObject out = new JsonObject();
                var identity = CallContext.identity();
                out.addProperty("user", identity == null ? 0 : identity.user());
                if (identity != null) out.addProperty("blocks", service.assetLibrary().load("window", 10).blueprint().cellCount());
                return ToolResult.of(out);
            }).unlisted());
            JsonObject rest = panel.request(frame("{\"t\":\"api\",\"tool\":\"asset_probe\",\"user\":7,\"assetKey\":\"" + "a".repeat(64)
                    + "\",\"args\":{\"user\":99}}"));
            assertEquals(7, rest.getAsJsonObject("body").get("user").getAsInt());
            JsonObject mcp = panel.request(frame("{\"t\":\"mcp\",\"user\":8,\"assetKey\":\"" + "b".repeat(64)
                    + "\",\"body\":{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"asset_probe\",\"arguments\":{}}}}"));
            assertTrue(mcp.getAsJsonObject("body").getAsJsonObject("result").toString().contains("\\\"user\\\":8"), mcp.toString());
            for (int i = 0; i < 8; i++) {
                JsonObject anonymous = panel.request(frame("{\"t\":\"api\",\"tool\":\"asset_probe\",\"args\":{}}"));
                assertEquals(0, anonymous.getAsJsonObject("body").get("user").getAsInt(), "pooled workers must not retain identity");
            }
            assertEquals(2, requests.get());
            assertEquals(2, downloads.get());
        } finally {
            storage.stop(0);
        }
    }

    @Test
    void reportsATokenThePanelRejects() throws Exception {
        RelayClient wrong = client("aibs_wrong");
        wrong.start();
        try {
            for (int i = 0; i < 400 && !wrong.status().contains("rejected relay.token"); i++) Thread.sleep(25);
            assertTrue(wrong.status().contains("the panel rejected relay.token"), wrong.status());
        } finally {
            wrong.stop();
        }
    }

    @Test
    void relayUriAcceptsPanelAddresses() {
        assertEquals("wss://panel.example.com/relay", RelayClient.relayUri("https://panel.example.com").toString());
        assertEquals("wss://panel.example.com/relay", RelayClient.relayUri("wss://panel.example.com/").toString());
        assertEquals("ws://10.0.0.2:8080/custom", RelayClient.relayUri("http://10.0.0.2:8080/custom").toString());
    }
}
