package me.abdiskiosk.minecraftaibuilder.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsMessageContext;
import me.abdiskiosk.minecraftaibuilder.common.RelayProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Accepts the servers' relay sockets and forwards requests to them. */
final class RelayHub {

    static final class OfflineException extends RuntimeException {
        OfflineException(String serverId) {
            super("server '" + serverId + "' is not connected to the panel");
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(RelayHub.class);

    private final PanelConfig config;
    private final Events events;
    /** Live (hello received) connection per server id. */
    private final Map<String, ServerConnection> live = new ConcurrentHashMap<>();
    /** Every open socket by Jetty session id, including ones still waiting for hello. */
    private final Map<String, ServerConnection> sockets = new ConcurrentHashMap<>();
    private final Map<String, ServerState> states = new ConcurrentHashMap<>();

    RelayHub(PanelConfig config, Events events) {
        this.config = config;
        this.events = events;
        reconcile();
    }

    /** Brings the state list in line with panel.json, and drops sockets of servers that were removed. */
    void reconcile() {
        Map<String, PanelConfig.Server> servers;
        synchronized (config) {
            servers = Map.copyOf(config.servers);
        }
        servers.forEach((id, s) -> states.computeIfAbsent(id, k -> new ServerState(k, s.name)).name(s.name));
        for (String id : states.keySet()) {
            if (servers.containsKey(id)) continue;
            states.remove(id);
            ServerConnection c = live.remove(id);
            if (c != null) c.close(RelayProtocol.CLOSE_BAD_TOKEN, "server removed from the panel");
        }
    }

    void onConnect(WsConnectContext ctx) {
        String token = Auth.bearer(ctx.header("Authorization"));
        String id = token == null ? null : config.serverForTokenHash(Auth.sha256(token));
        if (id == null) {
            LOG.warn("Relay connection from {} with an unknown token", ctx.host());
            ctx.closeSession(RelayProtocol.CLOSE_BAD_TOKEN, "unknown relay token");
            return;
        }
        sockets.put(ctx.sessionId(), new ServerConnection(id, ctx));
    }

    void onMessage(WsMessageContext ctx) {
        ServerConnection c = sockets.get(ctx.sessionId());
        if (c == null) return;
        JsonObject frame;
        try {
            frame = JsonParser.parseString(ctx.message()).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            LOG.warn("Server {} sent a frame that is not a JSON object", c.serverId);
            return;
        }
        String type = frame.has("t") ? frame.get("t").getAsString() : "";
        switch (type) {
            case RelayProtocol.HELLO -> hello(c, frame);
            case RelayProtocol.REPLY -> {
                if (c.ready && frame.has("id")) c.complete(frame.get("id").getAsLong(), frame);
            }
            case RelayProtocol.EVENT -> {
                if (!c.ready) return;
                String event = frame.get("event").getAsString();
                JsonObject job = frame.getAsJsonObject("job");
                ServerState s = states.get(c.serverId);
                if (s != null) s.job(event, job);
                events.job(c.serverId, event, job);
            }
            default -> LOG.warn("Server {} sent an unknown frame type '{}'", c.serverId, type);
        }
    }

    private void hello(ServerConnection c, JsonObject frame) {
        int protocol = frame.has("protocol") ? frame.get("protocol").getAsInt() : -1;
        if (protocol != RelayProtocol.VERSION) {
            String update = protocol < RelayProtocol.VERSION ? "update the AIBuilder plugin" : "update the panel";
            LOG.warn("Server {} speaks relay protocol {}, the panel {}: {}", c.serverId, protocol, RelayProtocol.VERSION, update);
            c.close(RelayProtocol.CLOSE_BAD_PROTOCOL, "panel protocol " + RelayProtocol.VERSION + ", plugin " + protocol + "; " + update);
            return;
        }
        c.ready = true;
        ServerConnection old = live.put(c.serverId, c);
        if (old != null && old != c) {
            old.failAll();
            old.close(RelayProtocol.CLOSE_REPLACED, "replaced by a newer connection");
        }
        ServerState s = states.get(c.serverId);
        if (s == null) return;
        s.connected(frame);
        LOG.info("Server {} connected ({} {}, {})", c.serverId, str(frame, "mc"), str(frame, "bridge"), str(frame, "plugin"));
        events.server(s.toJson());
    }

    void onClose(WsCloseContext ctx) {
        ServerConnection c = sockets.remove(ctx.sessionId());
        if (c == null) return;
        c.failAll();
        if (live.remove(c.serverId, c)) {
            ServerState s = states.get(c.serverId);
            if (s != null) {
                s.disconnected();
                events.server(s.toJson());
            }
            LOG.info("Server {} disconnected ({} {})", c.serverId, ctx.status(), ctx.reason());
        }
    }

    boolean known(String serverId) {
        return states.containsKey(serverId);
    }

    /**
     * Sends a request frame (without id) to a server.
     *
     * @return the reply frame; fails with {@link OfflineException} or a timeout
     */
    CompletableFuture<JsonObject> request(String serverId, JsonObject frame, Duration timeout) {
        ServerConnection c = live.get(serverId);
        if (c == null) return CompletableFuture.failedFuture(new OfflineException(serverId));
        return c.request(frame, timeout);
    }

    JsonArray snapshot() {
        JsonArray out = new JsonArray();
        states.values().stream().sorted(java.util.Comparator.comparing(s -> s.id)).forEach(s -> out.add(s.toJson()));
        return out;
    }

    /** Closes every socket (panel shutdown). */
    void closeAll() {
        for (ServerConnection c : sockets.values()) c.close(1001, "panel stopping");
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "?" : e.getAsString();
    }
}
