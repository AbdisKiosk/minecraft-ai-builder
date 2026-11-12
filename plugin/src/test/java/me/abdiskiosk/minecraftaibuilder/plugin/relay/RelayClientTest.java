package me.abdiskiosk.minecraftaibuilder.plugin.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.abdiskiosk.minecraftaibuilder.common.BlockDiff;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.RelayProtocol;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import me.abdiskiosk.minecraftaibuilder.common.WorldEditBridge;
import me.abdiskiosk.minecraftaibuilder.panel.Auth;
import me.abdiskiosk.minecraftaibuilder.panel.PanelConfig;
import me.abdiskiosk.minecraftaibuilder.panel.PanelServer;
import me.abdiskiosk.minecraftaibuilder.plugin.BuildService;
import me.abdiskiosk.minecraftaibuilder.plugin.History;
import me.abdiskiosk.minecraftaibuilder.plugin.PluginConfig;
import me.abdiskiosk.minecraftaibuilder.plugin.http.RestApi;
import me.abdiskiosk.minecraftaibuilder.plugin.mcp.McpEndpoint;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.ToolRegistry;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.Tools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The plugin's relay client against a real panel: Claude Code's requests reach the plugin without an open port. */
class RelayClientTest {

    private PanelServer panel;
    private RelayClient relay;
    private String clientToken;
    private History history;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        Path dir = Files.createTempDirectory("relay");
        PanelConfig pc = PanelConfig.load(dir.resolve("panel.json"));
        String serverToken = Auth.newToken("aibs_");
        clientToken = Auth.newToken("aibc_");
        PanelConfig.Server s = new PanelConfig.Server();
        s.name = "Test";
        s.tokenHash = Auth.sha256(serverToken);
        pc.servers.put("test", s);
        PanelConfig.Client c = new PanelConfig.Client();
        c.label = "t";
        c.tokenHash = Auth.sha256(clientToken);
        pc.clients.add(c);
        pc.passwordHash = Auth.hashPassword("relay test password");
        pc.save();
        panel = new PanelServer(pc).start("127.0.0.1", 0);

        WorldEditBridge bridge = (WorldEditBridge) Proxy.newProxyInstance(WorldEditBridge.class.getClassLoader(),
                new Class<?>[]{WorldEditBridge.class}, (p, m, a) -> m.getName().equals("name") ? "fake" : null);
        PluginConfig config = new PluginConfig(false, "127.0.0.1", 0, "", 4000, 2_000_000L, 262_144L, 30,
                List.of(), "", null, List.of(), true, "", "", null);
        BuildService service = new BuildService(null, bridge, config, dir);
        History.Settings settings = new History.Settings(true, 1000, 100, 1 << 20, 4096, 1000);
        history = new History(null, service, settings);
        ToolRegistry registry = new ToolRegistry(service, Logger.getAnonymousLogger());
        Tools.registerAll(registry, service);
        McpEndpoint mcp = new McpEndpoint(registry, "test");
        relay = new RelayClient("http://127.0.0.1:" + panel.port(), serverToken, mcp, new RestApi(registry), history, service,
                () -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("mc", "1.21.11");
                    return o;
                }, Logger.getAnonymousLogger());
        relay.start();
    }

    @AfterEach
    void stop() {
        relay.stop();
        panel.stop();
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + panel.port() + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + clientToken)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Waits until the plugin has connected and said hello. */
    private HttpResponse<String> toolsWhenOnline() throws Exception {
        HttpResponse<String> r = null;
        for (int i = 0; i < 200; i++) {
            r = send("GET", "/s/test/api/tools", null);
            if (r.statusCode() == 200) return r;
            Thread.sleep(25);
        }
        throw new AssertionError("plugin never connected: " + (r == null ? "" : r.statusCode() + " " + r.body()) + " / " + relay.status());
    }

    @Test
    void claudeCodeReachesThePluginThroughThePanel() throws Exception {
        HttpResponse<String> tools = toolsWhenOnline();
        assertTrue(tools.body().contains("\"block_info\""), tools.body());
        assertTrue(relay.status().startsWith("connected"), relay.status());

        HttpResponse<String> init = send("POST", "/s/test/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}");
        assertEquals(200, init.statusCode());
        JsonObject result = JsonParser.parseString(init.body()).getAsJsonObject().getAsJsonObject("result");
        assertEquals("2025-06-18", result.get("protocolVersion").getAsString());

        assertEquals(202, send("POST", "/s/test/mcp", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").statusCode());

        HttpResponse<String> list = send("POST", "/s/test/mcp", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertTrue(list.body().contains("\"build\""), list.body());

        assertEquals(400, send("POST", "/s/test/api/fill", "[1,2]").statusCode());
    }

    @Test
    void historyIsServedThroughThePanel() throws Exception {
        toolsWhenOnline();
        Box box = Box.of(new Vec3(0, 64, 0), new Vec3(1, 64, 0));
        BlockDiff diff = BlockDiff.of(box, new String[]{"air", "stone"}, new String[]{"oak_planks", "stone"});
        String id = history.record(new BuildService.JobInfo("j1", "done", "done", "fill", "test", "Alex", "world", box,
                2, 2, 1, System.currentTimeMillis(), 5, null, null, null), diff, List.of(box));

        String cookie = login();
        HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + panel.port() + "/api/servers/test/history"))
                .header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, page.statusCode(), page.body());
        assertTrue(page.body().contains(id));

        HttpResponse<String> entry = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + panel.port() + "/api/servers/test/history/" + id))
                .header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString());
        JsonObject e = JsonParser.parseString(entry.body()).getAsJsonObject();
        assertEquals(1, e.get("added").getAsInt());
        assertEquals("oak_planks", e.getAsJsonArray("types").get(0).getAsJsonObject().get("type").getAsString());

        // Job events from the plugin show up in the panel's state.
        relay.onJob(RelayProtocol.JOB_START, new BuildService.JobInfo("j7", "running", "build", "fill", "test", "Alex", "world",
                box, 100, 40, 40, System.currentTimeMillis(), 0, null, null, null));
        String state = "";
        for (int i = 0; i < 100 && !state.contains("j7"); i++) {
            state = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + panel.port() + "/api/state"))
                    .header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString()).body();
            Thread.sleep(20);
        }
        assertTrue(state.contains("\"processed\":40"), state);
    }

    private String login() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + panel.port() + "/api/login"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"relay test password\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, r.statusCode());
        return r.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    @Test
    void relayUriAcceptsPanelAddresses() {
        assertEquals("wss://panel.example.com/relay", RelayClient.relayUri("https://panel.example.com").toString());
        assertEquals("wss://panel.example.com/relay", RelayClient.relayUri("wss://panel.example.com/").toString());
        assertEquals("ws://10.0.0.2:8080/custom", RelayClient.relayUri("http://10.0.0.2:8080/custom").toString());
    }
}
