package me.abdiskiosk.minecraftaibuilder.panel;

import com.google.gson.JsonObject;
import io.javalin.websocket.WsContext;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** One Minecraft server's socket: sends request frames and matches the replies by id. */
final class ServerConnection {

    final String serverId;
    private final WsContext ctx;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    volatile boolean ready;

    ServerConnection(String serverId, WsContext ctx) {
        this.serverId = serverId;
        this.ctx = ctx;
    }

    String sessionId() {
        return ctx.sessionId();
    }

    /** Jetty's blocking send must not be used from two threads at once. */
    synchronized void send(String text) {
        ctx.send(text);
    }

    void close(int code, String reason) {
        try {
            ctx.closeSession(code, reason);
        } catch (RuntimeException ignored) {
            // already closed
        }
    }

    /** Sends {@code frame} with a fresh id; the future completes with the reply frame. */
    CompletableFuture<JsonObject> request(JsonObject frame, Duration timeout) {
        long id = nextId.getAndIncrement();
        frame.addProperty("id", id);
        CompletableFuture<JsonObject> reply = new CompletableFuture<>();
        pending.put(id, reply);
        reply.whenComplete((r, e) -> pending.remove(id));
        try {
            send(frame.toString());
        } catch (RuntimeException e) {
            reply.completeExceptionally(new RelayHub.OfflineException(serverId));
            return reply;
        }
        return reply.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    void complete(long id, JsonObject reply) {
        CompletableFuture<JsonObject> f = pending.get(id);
        if (f != null) f.complete(reply);
    }

    void failAll() {
        for (CompletableFuture<JsonObject> f : pending.values()) f.completeExceptionally(new RelayHub.OfflineException(serverId));
        pending.clear();
    }
}
