package me.abdiskiosk.minecraftaibuilder.plugin.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.abdiskiosk.minecraftaibuilder.common.RelayProtocol;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for the panel's relay hub (panel/lib/server/hub.ts): it accepts one plugin, checks its token and
 * protocol the way the hub does, sends it request frames and keeps the events it sends.
 */
final class FakePanel extends WebSocketServer {

    /** The {@code event} frames the plugin sent, in order. */
    final BlockingQueue<JsonObject> events = new LinkedBlockingQueue<>();
    volatile java.util.function.Function<JsonObject, JsonObject> assetHandler = frame -> new JsonObject();
    volatile int assetStatus = 200;
    /** Answers link, unlink and player_asset frames; every one of them is kept in {@link #playerFrames}. */
    volatile java.util.function.Function<JsonObject, JsonObject> playerHandler = frame -> new JsonObject();
    volatile int playerStatus = 200;
    final BlockingQueue<JsonObject> playerFrames = new LinkedBlockingQueue<>();

    private final String token;
    private final CompletableFuture<JsonObject> hello = new CompletableFuture<>();
    private final CountDownLatch listening = new CountDownLatch(1);
    private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private volatile WebSocket plugin;

    FakePanel(String token) {
        super(new InetSocketAddress("127.0.0.1", 0));
        this.token = token;
        setReuseAddr(true);
    }

    /** Starts listening and returns the port. */
    int listen() throws InterruptedException {
        start();
        if (!listening.await(10, TimeUnit.SECONDS)) throw new AssertionError("the fake panel did not start");
        return getPort();
    }

    /** The plugin's hello frame, once it has connected. */
    JsonObject hello() throws Exception {
        return hello.get(10, TimeUnit.SECONDS);
    }

    /** Sends a frame as it is, such as another welcome. */
    void send(String text) throws Exception {
        hello();
        plugin.send(text);
    }

    /** Sends a request frame (without id) to the plugin and waits for its reply frame. */
    JsonObject request(JsonObject frame) throws Exception {
        hello();
        int id = nextId.getAndIncrement();
        CompletableFuture<JsonObject> reply = new CompletableFuture<>();
        pending.put(id, reply);
        frame.addProperty("id", id);
        plugin.send(frame.toString());
        return reply.get(10, TimeUnit.SECONDS);
    }

    @Override
    public void onStart() {
        listening.countDown();
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        if (!("Bearer " + token).equals(handshake.getFieldValue("Authorization"))) {
            conn.close(RelayProtocol.CLOSE_BAD_TOKEN, "unknown relay token");
        }
    }

    @Override
    public void onMessage(WebSocket conn, String text) {
        JsonObject frame = JsonParser.parseString(text).getAsJsonObject();
        switch (frame.get("t").getAsString()) {
            case RelayProtocol.HELLO -> {
                if (frame.get("protocol").getAsInt() != RelayProtocol.VERSION) {
                    conn.close(RelayProtocol.CLOSE_BAD_PROTOCOL, "panel protocol " + RelayProtocol.VERSION);
                    return;
                }
                plugin = conn;
                conn.send("{\"t\":\"" + RelayProtocol.WELCOME + "\",\"archive\":false,\"assets\":true,\"links\":true}");
                hello.complete(frame);
            }
            case RelayProtocol.REPLY -> {
                CompletableFuture<JsonObject> reply = pending.remove(frame.get("id").getAsInt());
                if (reply != null) reply.complete(frame);
            }
            case RelayProtocol.EVENT -> events.add(frame);
            case RelayProtocol.ASSET -> {
                JsonObject answer = new JsonObject();
                answer.addProperty("t", RelayProtocol.ANSWER);
                answer.add("id", frame.get("id"));
                answer.add("body", assetHandler.apply(frame));
                answer.addProperty("status", assetStatus);
                conn.send(answer.toString());
            }
            case RelayProtocol.LINK, RelayProtocol.UNLINK, RelayProtocol.PLAYER_ASSET -> {
                playerFrames.add(frame);
                JsonObject answer = new JsonObject();
                answer.addProperty("t", RelayProtocol.ANSWER);
                answer.add("id", frame.get("id"));
                answer.add("body", playerHandler.apply(frame));
                answer.addProperty("status", playerStatus);
                conn.send(answer.toString());
            }
            default -> throw new AssertionError("unexpected frame from the plugin: " + text);
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
    }

    @Override
    public void onError(WebSocket conn, Exception error) {
        hello.completeExceptionally(error);
    }
}
