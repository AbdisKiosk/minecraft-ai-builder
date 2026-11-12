package me.abdiskiosk.minecraftaibuilder.plugin.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.abdiskiosk.minecraftaibuilder.common.BlockDiff;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.RelayProtocol;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import me.abdiskiosk.minecraftaibuilder.common.WorldEditBridge;
import me.abdiskiosk.minecraftaibuilder.plugin.BuildService;
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
        ToolRegistry registry = new ToolRegistry(service, Logger.getAnonymousLogger());
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
