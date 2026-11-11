package me.abdiskiosk.minecraftaibuilder.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.abdiskiosk.minecraftaibuilder.common.RelayProtocol;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/** What the dashboard shows about one server: online state, versions, running and recent jobs. Kept in memory. */
final class ServerState {

    private static final int RECENT = 50;

    final String id;
    private String name;
    private boolean online;
    private JsonObject hello = new JsonObject();
    private long since;
    private final Map<String, JsonObject> running = new LinkedHashMap<>();
    private final Deque<JsonObject> recent = new ArrayDeque<>();

    ServerState(String id, String name) {
        this.id = id;
        this.name = name;
    }

    synchronized void name(String name) {
        this.name = name;
    }

    synchronized boolean online() {
        return online;
    }

    synchronized void connected(JsonObject hello) {
        this.online = true;
        this.hello = hello.deepCopy();
        this.hello.remove("t");
        this.since = System.currentTimeMillis();
        running.clear(); // the plugin re-sends job.start for whatever is still running
    }

    synchronized void disconnected() {
        online = false;
        since = System.currentTimeMillis();
        for (JsonObject job : running.values()) {
            job.addProperty("status", "unknown");
            remember(job);
        }
        running.clear();
    }

    synchronized void job(String event, JsonObject job) {
        String id = job.has("id") ? job.get("id").getAsString() : "?";
        if (event.equals(RelayProtocol.JOB_END)) {
            running.remove(id);
            remember(job);
        } else {
            running.put(id, job);
        }
    }

    private void remember(JsonObject job) {
        recent.addFirst(job);
        while (recent.size() > RECENT) recent.removeLast();
    }

    synchronized JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("online", online);
        o.addProperty("since", since);
        o.add("info", hello.deepCopy());
        JsonArray r = new JsonArray();
        running.values().forEach(j -> r.add(j.deepCopy()));
        o.add("running", r);
        JsonArray done = new JsonArray();
        recent.forEach(j -> done.add(j.deepCopy()));
        o.add("recent", done);
        return o;
    }
}
