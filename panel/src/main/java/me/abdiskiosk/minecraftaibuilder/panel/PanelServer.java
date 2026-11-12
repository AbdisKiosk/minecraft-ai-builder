package me.abdiskiosk.minecraftaibuilder.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
import io.javalin.http.staticfiles.Location;
import me.abdiskiosk.minecraftaibuilder.common.RelayProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The panel's HTTP side.
 * <ul>
 *   <li>{@code /relay}: WebSocket the Minecraft servers connect out to (server token).</li>
 *   <li>{@code /s/<id>/mcp}, {@code /s/<id>/api/<tool>}: the plugin's own endpoints, proxied (client token), so
 *       {@code AIBUILDER_URL=https://panel/s/<id>} works for Claude Code and the Python SDK unchanged.</li>
 *   <li>{@code /api/...}, {@code /events}: the web UI's data (login session).</li>
 * </ul>
 */
public final class PanelServer {

    private static final Logger LOG = LoggerFactory.getLogger(PanelServer.class);
    private static final long MAX_BODY = 16L * 1024 * 1024;
    /** MCP tool calls wait up to the plugin's job-wait-seconds (30 s by default) and renders can be slow. */
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(150);
    private static final Duration HISTORY_TIMEOUT = Duration.ofMinutes(6);

    private final PanelConfig config;
    private final Auth auth = new Auth();
    private final Events events = new Events();
    private final RelayHub hub;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "panel-timer");
        t.setDaemon(true);
        return t;
    });
    private Javalin app;
    private FileTime configTime;

    public PanelServer(PanelConfig config) {
        this.config = config;
        this.hub = new RelayHub(config, events);
    }

    public PanelServer start(String host, int port) {
        app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.http.maxRequestSize = MAX_BODY;
            cfg.staticFiles.add(s -> {
                s.hostedPath = "/";
                s.directory = "/web";
                s.location = Location.CLASSPATH;
                // Jar entries all carry the same fixed timestamp, so Last-Modified never changes between builds and
                // browsers would keep an old UI after an upgrade. The files are small: always fetch them.
                s.headers = java.util.Map.of("Cache-Control", "no-store");
            });
            cfg.jetty.modifyWebSocketServletFactory(f -> {
                f.setMaxTextMessageSize(64L * 1024 * 1024);
                f.setIdleTimeout(Duration.ofSeconds(120));
            });
        });

        app.ws(RelayProtocol.PATH, ws -> {
            ws.onConnect(hub::onConnect);
            ws.onMessage(hub::onMessage);
            ws.onClose(hub::onClose);
            ws.onError(ctx -> LOG.debug("relay socket error", ctx.error()));
        });

        // ---- proxied plugin endpoints (client token) ----
        app.get("/s/{server}/api/health", ctx -> {
            String id = ctx.pathParam("server");
            JsonObject o = new JsonObject();
            o.addProperty("ok", hub.known(id));
            ctx.status(hub.known(id) ? 200 : 404).contentType("application/json").result(o.toString());
        });
        app.before("/s/*", ctx -> {
            if (ctx.path().endsWith("/api/health")) return;
            String token = Auth.bearer(ctx.header("Authorization"));
            if (token == null || !config.isClientTokenHash(Auth.sha256(token))) {
                ctx.header("WWW-Authenticate", "Bearer");
                halt(ctx, 401, "missing or wrong bearer token (make one with: add-client)");
            }
        });
        app.post("/s/{server}/mcp", this::mcp);
        app.get("/s/{server}/mcp", ctx -> ctx.header("Allow", "POST").status(405));
        app.delete("/s/{server}/mcp", ctx -> ctx.header("Allow", "POST").status(405));
        app.get("/s/{server}/api/tools", ctx -> {
            JsonObject f = new JsonObject();
            f.addProperty("t", RelayProtocol.API);
            f.addProperty("tool", "tools");
            forward(ctx, f, TOOL_TIMEOUT);
        });
        app.post("/s/{server}/api/{tool}", ctx -> {
            JsonElement body = json(ctx);
            if (body == null || !body.isJsonObject()) {
                if (body != null || !ctx.body().isBlank()) {
                    error(ctx, 400, "body must be a JSON object");
                    return;
                }
                body = new JsonObject();
            }
            JsonObject f = new JsonObject();
            f.addProperty("t", RelayProtocol.API);
            f.addProperty("tool", ctx.pathParam("tool"));
            f.add("args", body);
            forward(ctx, f, TOOL_TIMEOUT);
        });

        // ---- web UI (session) ----
        app.get("/api/health", ctx -> ctx.contentType("application/json").result("{\"ok\":true}"));
        app.post("/api/login", this::login);
        app.before("/api/*", ctx -> {
            String p = ctx.path();
            if (p.equals("/api/login") || p.equals("/api/health")) return;
            requireSession(ctx);
        });
        app.before("/events", this::requireSession);
        app.post("/api/logout", ctx -> {
            auth.endSession(ctx.cookie(Auth.SESSION_COOKIE));
            ctx.removeCookie(Auth.SESSION_COOKIE, "/");
            ctx.status(204);
        });
        app.get("/api/state", ctx -> ctx.contentType("application/json").result(state().toString()));
        app.get("/api/servers/{server}/history", ctx -> {
            JsonObject args = new JsonObject();
            String limit = ctx.queryParam("limit");
            if (limit != null) args.addProperty("limit", parseInt(limit, 50));
            String before = ctx.queryParam("before");
            if (before != null) args.addProperty("before", before);
            forward(ctx, history("list", args), HISTORY_TIMEOUT);
        });
        app.get("/api/servers/{server}/history/{id}", ctx ->
                forward(ctx, history("get", idArgs(ctx)), HISTORY_TIMEOUT));
        app.get("/api/servers/{server}/history/{id}/render", ctx -> {
            JsonObject args = idArgs(ctx);
            if (ctx.queryParam("view") != null) args.addProperty("view", ctx.queryParam("view"));
            if (ctx.queryParam("corner") != null) args.addProperty("corner", ctx.queryParam("corner"));
            forward(ctx, history("render", args), HISTORY_TIMEOUT);
        });
        app.get("/api/servers/{server}/history/{id}/blocks", ctx ->
                forward(ctx, history("blocks", idArgs(ctx)), HISTORY_TIMEOUT));
        app.post("/api/servers/{server}/history/{id}/revert", ctx -> {
            JsonObject args = idArgs(ctx);
            JsonElement body = json(ctx);
            args.addProperty("force", body != null && body.isJsonObject() && body.getAsJsonObject().has("force")
                    && body.getAsJsonObject().get("force").getAsBoolean());
            forward(ctx, history("revert", args), HISTORY_TIMEOUT);
        });
        app.get("/api/servers/{server}/drafts", ctx -> forward(ctx, draft("list", new JsonObject()), HISTORY_TIMEOUT));
        app.get("/api/servers/{server}/drafts/{id}", ctx -> forward(ctx, draft("get", idArgs(ctx)), HISTORY_TIMEOUT));
        app.get("/api/servers/{server}/drafts/{id}/render", ctx -> {
            JsonObject args = idArgs(ctx);
            if (ctx.queryParam("view") != null) args.addProperty("view", ctx.queryParam("view"));
            if (ctx.queryParam("corner") != null) args.addProperty("corner", ctx.queryParam("corner"));
            forward(ctx, draft("render", args), HISTORY_TIMEOUT);
        });
        app.get("/api/servers/{server}/drafts/{id}/blocks", ctx ->
                forward(ctx, draft("blocks", idArgs(ctx)), HISTORY_TIMEOUT));
        app.post("/api/servers/{server}/drafts/{id}/approve", ctx -> {
            JsonObject args = idArgs(ctx);
            JsonElement body = json(ctx);
            args.addProperty("force", body != null && body.isJsonObject() && body.getAsJsonObject().has("force")
                    && body.getAsJsonObject().get("force").getAsBoolean());
            args.addProperty("by", "panel");
            forward(ctx, draft("approve", args), HISTORY_TIMEOUT);
        });
        app.post("/api/servers/{server}/drafts/{id}/reject", ctx -> {
            JsonObject args = idArgs(ctx);
            args.addProperty("by", "panel");
            forward(ctx, draft("reject", args), HISTORY_TIMEOUT);
        });
        app.post("/api/promote", this::promote);
        app.sse("/events", client -> events.add(client, state().toString()));

        app.start(host, port);

        configTime = modified();
        timer.scheduleAtFixedRate(this::housekeeping, 5, 5, TimeUnit.SECONDS);
        timer.scheduleAtFixedRate(events::ping, 25, 25, TimeUnit.SECONDS);
        LOG.info("Panel listening on http://{}:{} ({} servers, {} client tokens, config {})", host, app.port(),
                config.servers.size(), config.clients.size(), config.file());
        if (config.passwordHash.isEmpty()) LOG.warn("No web UI password yet; run: java -jar AIBuilderPanel.jar set-password");
        return this;
    }

    public int port() {
        return app.port();
    }

    public void stop() {
        timer.shutdownNow();
        hub.closeAll();
        if (app != null) app.stop();
    }

    private JsonObject state() {
        JsonObject o = new JsonObject();
        o.add("servers", hub.snapshot());
        return o;
    }

    // ---- handlers ----

    private void mcp(Context ctx) {
        JsonElement body;
        try {
            body = JsonParser.parseString(ctx.body());
        } catch (JsonParseException e) {
            ctx.status(400).contentType("application/json")
                    .result("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"parse error\"}}");
            return;
        }
        JsonObject f = new JsonObject();
        f.addProperty("t", RelayProtocol.MCP);
        f.add("body", body);
        forward(ctx, f, TOOL_TIMEOUT);
    }

    private void login(Context ctx) {
        String remote = remote(ctx);
        if (!auth.mayTryLogin(remote)) {
            error(ctx, 429, "too many attempts; wait a minute");
            return;
        }
        if (config.passwordHash.isEmpty()) {
            error(ctx, 403, "no password set; run: java -jar AIBuilderPanel.jar set-password");
            return;
        }
        JsonElement body = json(ctx);
        String password = body != null && body.isJsonObject() && body.getAsJsonObject().has("password")
                ? body.getAsJsonObject().get("password").getAsString() : null;
        if (!Auth.checkPassword(password, config.passwordHash)) {
            auth.loginFailed(remote);
            error(ctx, 401, "wrong password");
            return;
        }
        Cookie cookie = new Cookie(Auth.SESSION_COOKIE, auth.newSession());
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSameSite(SameSite.STRICT);
        cookie.setSecure(https(ctx));
        cookie.setMaxAge((int) (Auth.SESSION_MILLIS / 1000));
        ctx.cookie(cookie);
        ctx.status(204);
    }

    private void requireSession(Context ctx) {
        if (!auth.validSession(ctx.cookie(Auth.SESSION_COOKIE))) {
            halt(ctx, 401, "log in first");
        } else if (!ctx.method().name().equals("GET") && !sameOrigin(ctx)) {
            // Writes must come from the panel's own page.
            halt(ctx, 403, "cross-site request refused");
        }
    }

    /** Sends {@code frame} to the server in the path and answers with its reply's status and body. */
    private void forward(Context ctx, JsonObject frame, Duration timeout) {
        String server = ctx.pathParam("server");
        if (!hub.known(server)) {
            error(ctx, 404, "unknown server '" + server + "'");
            return;
        }
        ctx.future(() -> hub.request(server, frame, timeout).handle((reply, e) -> {
            if (e != null) {
                Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                if (cause instanceof RelayHub.OfflineException) error(ctx, 503, cause.getMessage());
                else if (cause instanceof TimeoutException) error(ctx, 504, "server '" + server + "' did not answer in " + timeout.toSeconds() + "s");
                else {
                    LOG.warn("forward to {} failed", server, cause);
                    error(ctx, 502, "relay failed: " + cause);
                }
                return null;
            }
            int status = reply.has("status") ? reply.get("status").getAsInt() : 502;
            JsonElement body = reply.get("body");
            ctx.status(status);
            if (body != null && !(body instanceof JsonNull)) ctx.contentType("application/json").result(body.toString());
            return null;
        }));
    }

    /**
     * Copies a region from one server into another server's draft: {@code {from, to, world, box, toWorld, origin,
     * air, skipUnknown}}. Nothing is placed until the target draft is approved.
     */
    private void promote(Context ctx) {
        JsonElement body = json(ctx);
        if (body == null || !body.isJsonObject()) {
            error(ctx, 400, "body must be a JSON object");
            return;
        }
        JsonObject b = body.getAsJsonObject();
        String from, to, world, toWorld;
        JsonArray box, origin;
        try {
            from = b.get("from").getAsString();
            to = b.get("to").getAsString();
            world = b.get("world").getAsString();
            toWorld = b.has("toWorld") ? b.get("toWorld").getAsString() : world;
            box = b.getAsJsonArray("box");
            origin = b.has("origin") ? b.getAsJsonArray("origin") : null;
            if (box == null || box.size() != 6 || (origin != null && origin.size() != 3)) throw new IllegalStateException();
        } catch (RuntimeException e) {
            error(ctx, 400, "give from, to, world, box [x1,y1,z1,x2,y2,z2] and optionally toWorld, origin [x,y,z], air, skipUnknown");
            return;
        }
        for (String id : new String[]{from, to}) {
            if (!hub.known(id)) {
                error(ctx, 404, "unknown server '" + id + "'");
                return;
            }
        }
        int[] v = new int[6];
        for (int i = 0; i < 6; i++) v[i] = box.get(i).getAsInt();
        int minX = Math.min(v[0], v[3]), minY = Math.min(v[1], v[4]), minZ = Math.min(v[2], v[5]);
        JsonArray size = new JsonArray();
        size.add(Math.abs(v[3] - v[0]) + 1);
        size.add(Math.abs(v[4] - v[1]) + 1);
        size.add(Math.abs(v[5] - v[2]) + 1);
        JsonArray target = origin != null ? origin : new JsonArray();
        if (origin == null) {
            target.add(minX);
            target.add(minY);
            target.add(minZ);
        }
        JsonObject exportArgs = new JsonObject();
        exportArgs.addProperty("world", world);
        exportArgs.add("box", box);
        boolean air = !b.has("air") || b.get("air").getAsBoolean();
        boolean skipUnknown = b.has("skipUnknown") && b.get("skipUnknown").getAsBoolean();
        String label = "promote from " + from + " " + world + " " + minX + " " + minY + " " + minZ;
        ctx.future(() -> hub.request(from, draft("export", exportArgs), HISTORY_TIMEOUT).thenCompose(exported -> {
            int status = exported.has("status") ? exported.get("status").getAsInt() : 502;
            if (status != 200) return java.util.concurrent.CompletableFuture.completedFuture(exported);
            JsonObject importArgs = new JsonObject();
            importArgs.addProperty("world", toWorld);
            importArgs.add("origin", target);
            importArgs.add("size", size);
            importArgs.add("data", exported.getAsJsonObject("body").get("data"));
            importArgs.addProperty("air", air);
            importArgs.addProperty("skipUnknown", skipUnknown);
            importArgs.addProperty("label", label);
            return hub.request(to, draft("import", importArgs), HISTORY_TIMEOUT);
        }).handle((reply, e) -> {
            if (e != null) {
                Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                if (cause instanceof RelayHub.OfflineException) error(ctx, 503, cause.getMessage());
                else if (cause instanceof TimeoutException) error(ctx, 504, "a server did not answer in time");
                else error(ctx, 502, "promote failed: " + cause);
                return null;
            }
            ctx.status(reply.has("status") ? reply.get("status").getAsInt() : 502);
            JsonElement out = reply.get("body");
            if (out != null && !(out instanceof JsonNull)) ctx.contentType("application/json").result(out.toString());
            return null;
        }));
    }

    private void housekeeping() {
        auth.purgeExpired();
        FileTime now = modified();
        if (now == null || now.equals(configTime)) return;
        configTime = now;
        try {
            PanelConfig fresh = PanelConfig.load(config.file());
            synchronized (config) {
                config.passwordHash = fresh.passwordHash;
                config.servers = fresh.servers;
                config.clients = fresh.clients;
            }
            hub.reconcile();
            LOG.info("Reloaded {}: {} servers, {} client tokens", config.file(), fresh.servers.size(), fresh.clients.size());
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not reload {}: {}", config.file(), e.toString());
        }
    }

    private FileTime modified() {
        try {
            return Files.exists(config.file()) ? Files.getLastModifiedTime(config.file()) : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ---- helpers ----

    /** Answers with a JSON error and skips the endpoint; for before-handlers. */
    private static void halt(Context ctx, int status, String message) {
        error(ctx, status, message);
        ctx.skipRemainingHandlers();
    }

    private static void error(Context ctx, int status, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        ctx.status(status).contentType("application/json").result(o.toString());
    }

    private static JsonElement json(Context ctx) {
        String body = ctx.body();
        if (body.isBlank()) return null;
        try {
            return JsonParser.parseString(body);
        } catch (JsonParseException e) {
            return null;
        }
    }

    private static JsonObject history(String op, JsonObject args) {
        JsonObject f = new JsonObject();
        f.addProperty("t", RelayProtocol.HISTORY);
        f.addProperty("op", op);
        f.add("args", args);
        return f;
    }

    private static JsonObject draft(String op, JsonObject args) {
        JsonObject f = new JsonObject();
        f.addProperty("t", RelayProtocol.DRAFT);
        f.addProperty("op", op);
        f.add("args", args);
        return f;
    }

    private static JsonObject idArgs(Context ctx) {
        JsonObject args = new JsonObject();
        args.addProperty("id", ctx.pathParam("id"));
        return args;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean https(Context ctx) {
        String proto = ctx.header("X-Forwarded-Proto");
        return "https".equalsIgnoreCase(proto) || "https".equalsIgnoreCase(ctx.scheme());
    }

    /** The client address; behind a local reverse proxy, the first X-Forwarded-For entry. */
    private static String remote(Context ctx) {
        String ip = ctx.ip();
        String fwd = ctx.header("X-Forwarded-For");
        if (fwd != null && (ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1") || ip.equals("::1"))) {
            return fwd.split(",")[0].trim();
        }
        return ip;
    }

    private static boolean sameOrigin(Context ctx) {
        String origin = ctx.header("Origin");
        if (origin == null) return true; // not a browser cross-site request (those always send Origin on POST)
        String host = ctx.header("X-Forwarded-Host");
        if (host == null) host = ctx.host();
        try {
            URI o = URI.create(origin);
            String originHost = o.getPort() == -1 ? o.getHost() : o.getHost() + ":" + o.getPort();
            return host != null && host.equalsIgnoreCase(originHost);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
