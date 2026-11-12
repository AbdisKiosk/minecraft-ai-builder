package me.abdiskiosk.minecraftaibuilder.panel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.abdiskiosk.minecraftaibuilder.common.RelayProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelServerTest {

    private static final String PASSWORD = "correct horse battery";

    private PanelServer panel;
    private String serverToken;
    private String stagingToken;
    private String clientToken;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        Path dir = Files.createTempDirectory("panel");
        PanelConfig config = PanelConfig.load(dir.resolve("panel.json"));
        serverToken = Auth.newToken("aibs_");
        clientToken = Auth.newToken("aibc_");
        PanelConfig.Server s = new PanelConfig.Server();
        s.name = "Survival";
        s.tokenHash = Auth.sha256(serverToken);
        config.servers.put("survival", s);
        stagingToken = Auth.newToken("aibs_");
        PanelConfig.Server staging = new PanelConfig.Server();
        staging.name = "Staging";
        staging.tokenHash = Auth.sha256(stagingToken);
        config.servers.put("staging", staging);
        PanelConfig.Client c = new PanelConfig.Client();
        c.label = "test";
        c.tokenHash = Auth.sha256(clientToken);
        config.clients.add(c);
        config.passwordHash = Auth.hashPassword(PASSWORD);
        config.save();
        panel = new PanelServer(config).start("127.0.0.1", 0);
    }

    @AfterEach
    void stop() {
        panel.stop();
    }

    /** Plays the plugin's side of the relay: answers request frames with {@code answer}. */
    private final class FakePlugin implements WebSocket.Listener {
        final BlockingQueue<JsonObject> received = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final Function<JsonObject, JsonObject> answer;
        WebSocket ws;
        private final StringBuilder buffer = new StringBuilder();

        FakePlugin(Function<JsonObject, JsonObject> answer) {
            this.answer = answer;
        }

        FakePlugin connect(String token, int protocol) throws Exception {
            ws = http.newWebSocketBuilder().header("Authorization", "Bearer " + token)
                    .buildAsync(URI.create("ws://127.0.0.1:" + panel.port() + RelayProtocol.PATH), this).get(5, TimeUnit.SECONDS);
            JsonObject hello = new JsonObject();
            hello.addProperty("t", RelayProtocol.HELLO);
            hello.addProperty("protocol", protocol);
            hello.addProperty("mc", "1.21.11");
            hello.addProperty("bridge", "fake");
            hello.addProperty("plugin", "test");
            ws.sendText(hello.toString(), true).get(5, TimeUnit.SECONDS);
            return this;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                JsonObject frame = JsonParser.parseString(buffer.toString()).getAsJsonObject();
                buffer.setLength(0);
                received.add(frame);
                JsonObject reply = answer.apply(frame);
                if (reply != null) {
                    reply.addProperty("t", RelayProtocol.REPLY);
                    reply.add("id", frame.get("id"));
                    ws.sendText(reply.toString(), true);
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            closed.complete(code);
            return null;
        }

        void send(JsonObject frame) throws Exception {
            ws.sendText(frame.toString(), true).get(5, TimeUnit.SECONDS);
        }
    }

    private static JsonObject reply(int status, String body) {
        JsonObject r = new JsonObject();
        r.addProperty("status", status);
        r.add("body", body == null ? null : JsonParser.parseString(body));
        return r;
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + panel.port() + path))
                .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + panel.port() + path)).timeout(Duration.ofSeconds(10));
        if (cookie != null) b.header("Cookie", cookie);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void waitOnline() throws Exception {
        waitOnline(1);
    }

    private void waitOnline(int servers) throws Exception {
        String cookie = login();
        for (int i = 0; i < 100; i++) {
            if (get("/api/state", cookie).body().split("\"online\":true", -1).length - 1 >= servers) return;
            Thread.sleep(20);
        }
        throw new AssertionError("servers never came online");
    }

    private HttpResponse<String> postWithCookie(String path, String cookie, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + panel.port() + path)).header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void promoteCopiesARegionIntoTheTargetsDraft() throws Exception {
        new FakePlugin(f -> {
            JsonObject args = f.getAsJsonObject("args");
            assertEquals("export", f.get("op").getAsString());
            assertEquals("world", args.get("world").getAsString());
            return reply(200, "{\"data\":\"QUJD\",\"bytes\":3}");
        }).connect(stagingToken, RelayProtocol.VERSION);
        FakePlugin target = new FakePlugin(f -> {
            JsonObject args = f.getAsJsonObject("args");
            assertEquals("import", f.get("op").getAsString());
            return reply(200, "{\"draft\":\"d1\",\"got\":\"" + args.get("data").getAsString() + "\",\"origin\":"
                    + args.get("origin") + ",\"size\":" + args.get("size") + ",\"air\":" + args.get("air") + "}");
        }).connect(serverToken, RelayProtocol.VERSION);
        waitOnline(2);
        String cookie = login();
        HttpResponse<String> r = postWithCookie("/api/promote", cookie,
                "{\"from\":\"staging\",\"to\":\"survival\",\"world\":\"world\",\"box\":[10,64,5,0,70,9],\"air\":false}");
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("{\"draft\":\"d1\",\"got\":\"QUJD\",\"origin\":[0,64,5],\"size\":[11,7,5],\"air\":false}", r.body());
        JsonObject label = target.received.poll(5, TimeUnit.SECONDS).getAsJsonObject("args");
        assertTrue(label.get("label").getAsString().startsWith("promote from staging"));

        assertEquals(404, postWithCookie("/api/promote", cookie,
                "{\"from\":\"nope\",\"to\":\"survival\",\"world\":\"world\",\"box\":[0,0,0,1,1,1]}").statusCode());
        assertEquals(400, postWithCookie("/api/promote", cookie, "{\"from\":\"staging\"}").statusCode());
    }

    @Test
    void openDraftsShowInTheState() throws Exception {
        FakePlugin plugin = new FakePlugin(f -> reply(200, "{}")).connect(serverToken, RelayProtocol.VERSION);
        waitOnline();
        String cookie = login();
        plugin.send(JsonParser.parseString("{\"t\":\"event\",\"event\":\"draft.update\",\"draft\":{\"id\":\"d7\",\"status\":\"open\",\"added\":3}}").getAsJsonObject());
        String state = "";
        for (int i = 0; i < 100 && !state.contains("d7"); i++) {
            state = get("/api/state", cookie).body();
            Thread.sleep(20);
        }
        assertTrue(state.contains("\"d7\""), state);
        plugin.send(JsonParser.parseString("{\"t\":\"event\",\"event\":\"draft.update\",\"draft\":{\"id\":\"d7\",\"status\":\"approved\"}}").getAsJsonObject());
        for (int i = 0; i < 100 && state.contains("d7"); i++) {
            state = get("/api/state", cookie).body();
            Thread.sleep(20);
        }
        assertTrue(!state.contains("d7"), state);
    }

    private String login() throws Exception {
        HttpResponse<String> r = post("/api/login", null, "{\"password\":\"" + PASSWORD + "\"}");
        assertEquals(204, r.statusCode(), r.body());
        String setCookie = r.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(setCookie.contains("HttpOnly"));
        return setCookie.split(";")[0];
    }

    @Test
    void forwardsRestCallsToTheServer() throws Exception {
        FakePlugin plugin = new FakePlugin(f -> reply(200, "{\"job\":\"j1\",\"tool\":\"" + f.get("tool").getAsString() + "\"}")).connect(serverToken, RelayProtocol.VERSION);
        waitOnline();
        HttpResponse<String> r = post("/s/survival/api/fill", clientToken, "{\"region\":[0,0,0,1,1,1],\"pattern\":\"stone\"}");
        assertEquals(200, r.statusCode());
        assertEquals("{\"job\":\"j1\",\"tool\":\"fill\"}", r.body());
        JsonObject frame = plugin.received.poll(5, TimeUnit.SECONDS);
        assertEquals(RelayProtocol.API, frame.get("t").getAsString());
        assertEquals("stone", frame.getAsJsonObject("args").get("pattern").getAsString());
    }

    @Test
    void forwardsMcpAndPassesThroughNoContent() throws Exception {
        new FakePlugin(f -> {
            JsonObject body = f.getAsJsonObject("body");
            return body.has("id") ? reply(200, "{\"jsonrpc\":\"2.0\",\"id\":" + body.get("id") + ",\"result\":{}}") : reply(202, null);
        }).connect(serverToken, RelayProtocol.VERSION);
        waitOnline();
        HttpResponse<String> r = post("/s/survival/mcp", clientToken, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}");
        assertEquals(200, r.statusCode());
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}", r.body());
        HttpResponse<String> n = post("/s/survival/mcp", clientToken, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(202, n.statusCode());
        assertEquals("", n.body());
    }

    @Test
    void refusesMissingTokensAndUnknownServers() throws Exception {
        assertEquals(401, post("/s/survival/api/fill", null, "{}").statusCode());
        assertEquals(401, post("/s/survival/api/fill", serverToken, "{}").statusCode()); // server token is not a client token
        assertEquals(404, post("/s/nope/api/fill", clientToken, "{}").statusCode());
        HttpResponse<String> offline = post("/s/survival/api/fill", clientToken, "{}");
        assertEquals(503, offline.statusCode());
        assertTrue(offline.body().contains("not connected"));
        assertEquals(401, get("/api/state", null).statusCode());
        assertEquals(401, post("/api/login", null, "{\"password\":\"wrong\"}").statusCode());
    }

    @Test
    void closesSocketsWithBadTokenOrProtocol() throws Exception {
        FakePlugin badToken = new FakePlugin(f -> null).connect("aibs_nope", RelayProtocol.VERSION);
        assertEquals(RelayProtocol.CLOSE_BAD_TOKEN, badToken.closed.get(5, TimeUnit.SECONDS));
        FakePlugin badProtocol = new FakePlugin(f -> null).connect(serverToken, RelayProtocol.VERSION + 1);
        assertEquals(RelayProtocol.CLOSE_BAD_PROTOCOL, badProtocol.closed.get(5, TimeUnit.SECONDS));
    }

    @Test
    void newerConnectionReplacesTheOld() throws Exception {
        FakePlugin first = new FakePlugin(f -> reply(200, "{\"from\":1}")).connect(serverToken, RelayProtocol.VERSION);
        waitOnline();
        FakePlugin second = new FakePlugin(f -> reply(200, "{\"from\":2}")).connect(serverToken, RelayProtocol.VERSION);
        assertEquals(RelayProtocol.CLOSE_REPLACED, first.closed.get(5, TimeUnit.SECONDS));
        assertEquals("{\"from\":2}", post("/s/survival/api/fill", clientToken, "{}").body());
        assertTrue(!second.closed.isDone());
    }

    @Test
    void historyAndJobEventsReachTheUi() throws Exception {
        FakePlugin plugin = new FakePlugin(f -> reply(200, "{\"op\":\"" + f.get("op").getAsString() + "\",\"id\":"
                + f.getAsJsonObject("args").get("id") + "}")).connect(serverToken, RelayProtocol.VERSION);
        waitOnline();
        String cookie = login();
        HttpResponse<String> r = get("/api/servers/survival/history/habc", cookie);
        assertEquals(200, r.statusCode());
        assertEquals("{\"op\":\"get\",\"id\":\"habc\"}", r.body());

        JsonObject event = JsonParser.parseString("{\"t\":\"event\",\"event\":\"job.start\",\"job\":{\"id\":\"j9\",\"status\":\"running\",\"total\":100,\"processed\":10}}").getAsJsonObject();
        plugin.send(event);
        for (int i = 0; i < 100 && !get("/api/state", cookie).body().contains("j9"); i++) Thread.sleep(20);
        assertTrue(get("/api/state", cookie).body().contains("\"j9\""));

        // Revert needs a same-origin write.
        HttpRequest cross = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + panel.port() + "/api/servers/survival/history/habc/revert"))
                .header("Cookie", cookie).header("Origin", "https://evil.example").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(403, http.send(cross, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void blocksForThe3dViewAreForwarded() throws Exception {
        FakePlugin plugin = new FakePlugin(f -> reply(200, "{\"t\":\"" + f.get("t").getAsString() + "\",\"op\":\"" + f.get("op").getAsString()
                + "\",\"id\":" + f.getAsJsonObject("args").get("id") + ",\"before\":[[0,8]]}")).connect(serverToken, RelayProtocol.VERSION);
        waitOnline();
        String cookie = login();
        HttpResponse<String> entry = get("/api/servers/survival/history/habc/blocks", cookie);
        assertEquals(200, entry.statusCode());
        assertEquals("{\"t\":\"" + RelayProtocol.HISTORY + "\",\"op\":\"blocks\",\"id\":\"habc\",\"before\":[[0,8]]}", entry.body());
        HttpResponse<String> draft = get("/api/servers/survival/drafts/d1/blocks", cookie);
        assertEquals(200, draft.statusCode());
        assertEquals("{\"t\":\"" + RelayProtocol.DRAFT + "\",\"op\":\"blocks\",\"id\":\"d1\",\"before\":[[0,8]]}", draft.body());
        assertEquals(401, get("/api/servers/survival/history/habc/blocks", null).statusCode());
        plugin.ws.abort();
    }
}
