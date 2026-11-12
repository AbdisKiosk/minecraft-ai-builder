package me.abdiskiosk.minecraftaibuilder.panel;

import com.google.gson.JsonObject;
import io.javalin.http.sse.SseClient;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Pushes server and job changes to open browser tabs over server-sent events. */
final class Events {

    private final Set<SseClient> clients = ConcurrentHashMap.newKeySet();

    void add(SseClient client, String snapshot) {
        client.keepAlive();
        client.onClose(() -> clients.remove(client));
        clients.add(client);
        send(client, "snapshot", snapshot);
    }

    void server(JsonObject state) {
        broadcast("server", state.toString());
    }

    void job(String serverId, String event, JsonObject job) {
        JsonObject o = new JsonObject();
        o.addProperty("server", serverId);
        o.addProperty("event", event);
        o.add("job", job);
        broadcast("job", o.toString());
    }

    void draft(String serverId, JsonObject draft) {
        JsonObject o = new JsonObject();
        o.addProperty("server", serverId);
        o.add("draft", draft);
        broadcast("draft", o.toString());
    }

    /** Keeps proxies from closing idle streams. */
    void ping() {
        for (SseClient c : clients) {
            synchronized (c) {
                try {
                    c.sendComment("ping");
                } catch (RuntimeException e) {
                    clients.remove(c);
                }
            }
        }
    }

    int size() {
        return clients.size();
    }

    private void broadcast(String event, String data) {
        for (SseClient c : clients) send(c, event, data);
    }

    private void send(SseClient c, String event, String data) {
        synchronized (c) {
            try {
                if (c.terminated()) {
                    clients.remove(c);
                    return;
                }
                c.sendEvent(event, data);
            } catch (RuntimeException e) {
                clients.remove(c);
            }
        }
    }
}
