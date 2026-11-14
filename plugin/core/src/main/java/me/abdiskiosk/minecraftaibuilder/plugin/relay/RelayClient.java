package me.abdiskiosk.minecraftaibuilder.plugin.relay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.RelayProtocol;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import me.abdiskiosk.minecraftaibuilder.plugin.BuildService;
import me.abdiskiosk.minecraftaibuilder.plugin.AssetLibrary;
import me.abdiskiosk.minecraftaibuilder.plugin.CallContext;
import me.abdiskiosk.minecraftaibuilder.plugin.Drafts;
import me.abdiskiosk.minecraftaibuilder.plugin.History;
import me.abdiskiosk.minecraftaibuilder.plugin.Lessons;
import me.abdiskiosk.minecraftaibuilder.plugin.map.WorldMap;
import me.abdiskiosk.minecraftaibuilder.plugin.mcp.McpEndpoint;

import java.net.URI;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Connects out to the web panel and serves MCP, REST and history requests that arrive over the socket, so the
 * Minecraft server needs no open port. Reconnects with backoff; see {@link RelayProtocol} for the frames.
 */
public final class RelayClient implements BuildService.JobListener {

    private static final long PING_SECONDS = 20;
    private static final long DEAD_MILLIS = 65_000;
    private static final long MAX_BACKOFF = 60;
    private static final long PROGRESS_EVERY_MS = 250;
    private static final String REFUSED = "the panel refused: ";
    /** How long a player waits for the panel; an in-game save sends up to 5 MB. */
    private static final Duration ACCOUNT_TIMEOUT = Duration.ofSeconds(60);

    private final URI uri;
    private final String token;
    private final McpEndpoint mcp;
    private final RestApi api;
    private final History history;
    private final Drafts drafts;
    private final WorldMap map;
    private final BuildService service;
    private final Supplier<JsonObject> hello;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final ExecutorService workers = Executors.newFixedThreadPool(4, daemon("AIBuilder-Relay"));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(daemon("AIBuilder-Relay-Timer"));
    /** Connects and writes to the socket, one at a time and never on the server thread: both block. */
    private final ExecutorService sender = Executors.newSingleThreadExecutor(daemon("AIBuilder-Relay-Send"));
    private final Map<String, Long> lastProgress = new ConcurrentHashMap<>();
    /** Requests this plugin made of the panel, by id, waiting for their answer. */
    private final Map<Long, CompletableFuture<JsonObject>> answers = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong nextAsk = new java.util.concurrent.atomic.AtomicLong(1);
    private final ExecutorService uploads = Executors.newSingleThreadExecutor(daemon("AIBuilder-Relay-Upload"));
    private final java.util.concurrent.atomic.AtomicBoolean uploadQueued = new java.util.concurrent.atomic.AtomicBoolean();
    /** The panel on this connection keeps history in object storage. */
    private volatile boolean archive;
    private volatile boolean assets;
    /** A welcome arrived on this connection, and whether it offered account links (link, unlink, player_asset). */
    private volatile boolean welcomed;
    private volatile boolean links;

    private volatile WebSocketClient socket;
    private volatile boolean stopped;
    private volatile String status = "starting";
    private volatile long lastHeard;
    private volatile long lastBeat = System.currentTimeMillis();
    private long backoff = 1;

    /**
     * @param history null when the history is turned off
     * @param drafts  null when drafts are not available
     * @param map     null when the map is turned off
     */
    public RelayClient(String url, String token, McpEndpoint mcp, RestApi api, History history, Drafts drafts,
                       WorldMap map, BuildService service, Supplier<JsonObject> hello, Logger logger) {
        this.uri = relayUri(url);
        this.token = token;
        this.mcp = mcp;
        this.api = api;
        this.history = history;
        this.drafts = drafts;
        this.map = map;
        this.service = service;
        this.hello = hello;
        this.logger = logger;
        // Only a panel whose welcome said "assets":true has a library; without one, calls keep server blueprints.
        service.setAssetLibrary(new AssetLibrary(service.dataDir(), this::asset, () -> assets));
    }

    /** Accepts {@code https://panel.example.com} as well as {@code wss://panel.example.com/relay}. */
    public static URI relayUri(String url) {
        String u = url.trim();
        if (u.startsWith("https://")) u = "wss://" + u.substring(8);
        else if (u.startsWith("http://")) u = "ws://" + u.substring(7);
        URI parsed = URI.create(u);
        if (!"ws".equals(parsed.getScheme()) && !"wss".equals(parsed.getScheme())) {
            throw new IllegalArgumentException("relay.url must start with wss://, ws://, https:// or http://");
        }
        String path = parsed.getRawPath();
        if (path == null || path.isEmpty() || path.equals("/")) u = u.replaceAll("/+$", "") + RelayProtocol.PATH;
        return URI.create(u);
    }

    public String status() {
        return status;
    }

    public URI uri() {
        return uri;
    }

    public void start() {
        service.addListener(this);
        if (drafts != null) drafts.addListener(this::onDraft);
        connect();
        timer.scheduleAtFixedRate(this::heartbeat, PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS);
    }

    public void stop() {
        stopped = true;
        WebSocketClient s = socket;
        socket = null;
        if (s != null) {
            try {
                sender.submit(() -> s.close(WebSocketClient.NORMAL_CLOSURE, "server stopping")).get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // a send that hangs or a sender already shut down: drop the connection below
            }
            s.abort();
        }
        sender.shutdownNow();
        workers.shutdownNow();
        uploads.shutdownNow();
        timer.shutdownNow();
    }

    private void connect() {
        if (stopped) return;
        try {
            sender.execute(this::open);
        } catch (java.util.concurrent.RejectedExecutionException stopping) {
            // stop() ran in between
        }
    }

    private void open() {
        if (stopped) return;
        Listener listener = new Listener();
        WebSocketClient ws;
        try {
            ws = WebSocketClient.connect(uri, Collections.singletonMap("Authorization", "Bearer " + token), 10_000, listener);
        } catch (IOException | RuntimeException e) {
            failed(describe(e));
            return;
        }
        listener.ws = ws;
        synchronized (this) {
            if (stopped) {
                ws.abort();
                return;
            }
            socket = ws;
            backoff = 1;
        }
        lastHeard = System.currentTimeMillis();
        if (!status.startsWith("connected")) logger.info("Relay connected to " + uri);
        status = "connected to " + uri;
        sendHello();
        // Reading starts once the socket is current, so a close the panel sent at once (a rejected token, say) is ours.
        ws.start("AIBuilder-Relay-Read");
    }

    private synchronized void failed(String why) {
        socket = null;
        archive = false;
        assets = false;
        welcomed = false;
        links = false;
        for (CompletableFuture<JsonObject> f : answers.values()) f.completeExceptionally(new IllegalStateException("disconnected"));
        answers.clear();
        if (stopped) return;
        String next = "retrying in " + backoff + "s: " + why;
        // Log changes only, so a panel that is down for hours does not flood the console.
        if (!status.endsWith(why)) logger.warning("Relay " + uri + ": " + why);
        status = next;
        timer.schedule(this::connect, backoff, TimeUnit.SECONDS);
        backoff = Math.min(MAX_BACKOFF, backoff * 2);
    }

    private void heartbeat() {
        long now = System.currentTimeMillis();
        long gap = now - lastBeat;
        lastBeat = now;
        WebSocketClient s = socket;
        if (s == null) return;
        // A late heartbeat means this process was paused (the machine slept), not that the panel went quiet:
        // give the panel a fresh ping to answer instead of dropping a working connection.
        if (gap > 2 * PING_SECONDS * 1000) lastHeard = now;
        if (now - lastHeard > DEAD_MILLIS) {
            s.abort();
            failed("no answer from the panel");
            return;
        }
        enqueue(s, WebSocketClient::sendPing);
    }

    private void sendHello() {
        JsonObject o;
        try {
            o = hello.get();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Relay hello failed", e);
            o = new JsonObject();
        }
        o.addProperty("t", RelayProtocol.HELLO);
        o.addProperty("protocol", RelayProtocol.VERSION);
        o.addProperty("history", history != null);
        o.addProperty("review", drafts != null && drafts.reviewOn());
        o.addProperty("map", map != null);
        o.addProperty("lessons", true);
        send(o);
        for (BuildService.JobInfo job : service.running()) onJob(RelayProtocol.JOB_START, job);
        if (drafts != null) drafts.openSummaries().forEach(this::onDraft);
    }

    private void onDraft(JsonObject summary) {
        if (socket == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("t", RelayProtocol.EVENT);
        o.addProperty("event", RelayProtocol.DRAFT_UPDATE);
        o.add("draft", summary);
        send(o);
    }

    @Override
    public void onJob(String event, BuildService.JobInfo job) {
        if (socket == null) return;
        if (event.equals(RelayProtocol.JOB_PROGRESS)) {
            long now = System.currentTimeMillis();
            Long last = lastProgress.get(job.id());
            if (last != null && now - last < PROGRESS_EVERY_MS) return;
            lastProgress.put(job.id(), now);
        } else if (event.equals(RelayProtocol.JOB_END)) {
            lastProgress.remove(job.id());
        }
        JsonObject o = new JsonObject();
        o.addProperty("t", RelayProtocol.EVENT);
        o.addProperty("event", event);
        o.add("job", gson.toJsonTree(job));
        send(o);
    }

    private void handle(String text) {
        JsonObject frame;
        try {
            frame = JsonParser.parseString(text).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            logger.warning("Relay: ignoring a frame that is not a JSON object");
            return;
        }
        String type = frame.has("t") ? frame.get("t").getAsString() : "";
        JsonElement id = frame.get("id");
        if (type.equals(RelayProtocol.WELCOME)) {
            welcome(frame);
            return;
        }
        if (type.equals(RelayProtocol.ANSWER)) {
            CompletableFuture<JsonObject> f = id == null ? null : answers.remove(id.getAsLong());
            if (f != null) f.complete(frame);
            return;
        }
        if (id == null) return;
        if (type.equals(RelayProtocol.MAP)) {
            workers.submit(() -> map(frame, id));
            return;
        }
        workers.submit(() -> {
            RestApi.Response r;
            try {
                r = CallContext.withIdentity(identity(frame), () -> switch (type) {
                    case RelayProtocol.MCP -> {
                        JsonElement body = frame.get("body");
                        JsonElement out = CallContext.withSource("panel mcp", () -> mcp.handle(body));
                        yield out == null ? new RestApi.Response(202, JsonNull.INSTANCE) : new RestApi.Response(200, out);
                    }
                    case RelayProtocol.API -> {
                        String tool = frame.get("tool").getAsString();
                        JsonObject args = frame.has("args") && frame.get("args").isJsonObject() ? frame.getAsJsonObject("args") : new JsonObject();
                        yield tool.equals("tools") ? api.list() : CallContext.withSource("panel rest", () -> api.call(tool, args));
                    }
                    case RelayProtocol.HISTORY -> history(frame);
                    case RelayProtocol.DRAFT -> draft(frame);
                    case RelayProtocol.LESSONS -> lessons(frame);
                    default -> RestApi.error(400, "unknown frame type '" + type + "'");
                });
            } catch (EditException e) {
                r = RestApi.error(400, e.getMessage());
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Relay request failed", e);
                r = RestApi.error(500, "internal error; see the server log");
            }
            reply(id, r);
        });
    }

    private void reply(JsonElement id, RestApi.Response r) {
        JsonObject reply = new JsonObject();
        reply.addProperty("t", RelayProtocol.REPLY);
        reply.add("id", id);
        reply.addProperty("status", r.status());
        reply.add("body", r.body() == null ? JsonNull.INSTANCE : r.body());
        send(reply);
    }

    /** Map requests answer when their tiles are drawn, without holding a worker while they wait. */
    private void map(JsonObject frame, JsonElement id) {
        if (map == null) {
            reply(id, RestApi.error(404, "the map is turned off on this server (map.enabled in the plugin's config.yml)"));
            return;
        }
        JsonObject args = frame.has("args") && frame.get("args").isJsonObject() ? frame.getAsJsonObject("args") : new JsonObject();
        String op = frame.has("op") ? frame.get("op").getAsString() : "";
        CompletableFuture<JsonElement> work;
        try {
            work = map.handle(op, args);
        } catch (RuntimeException e) {
            work = CompletableFuture.failedFuture(e);
        }
        work.whenComplete((body, error) -> {
            Throwable e = error;
            while (e instanceof java.util.concurrent.CompletionException && e.getCause() != null) e = e.getCause();
            if (e == null) {
                reply(id, new RestApi.Response(200, body));
            } else if (e instanceof EditException) {
                reply(id, RestApi.error(400, e.getMessage()));
            } else {
                logger.log(Level.WARNING, "Relay map request failed", e);
                reply(id, RestApi.error(500, "internal error; see the server log"));
            }
        });
    }

    // ---- history archive ----

    private void welcome(JsonObject frame) {
        assets = frame.has("assets") && frame.get("assets").getAsBoolean();
        links = frame.has("links") && frame.get("links").getAsBoolean();
        welcomed = true;
        if (history == null || !frame.has("archive") || !frame.get("archive").getAsBoolean()) return;
        archive = true;
        history.archiveTo(this::scheduleUploads);
        scheduleUploads();
    }

    private static CallContext.Identity identity(JsonObject frame) {
        if (!frame.has("user")) return null; // old panels and non-tool requests keep local blueprint behaviour
        long user = frame.get("user").getAsLong();
        // No grant: the panel has too many calls open on this socket; asset operations then refuse, others run.
        String key = frame.has("assetKey") ? frame.get("assetKey").getAsString() : null;
        if (user < 1 || (key != null && !key.matches("[a-f0-9]{64}"))) throw new EditException("invalid authenticated asset caller");
        return new CallContext.Identity(user, key);
    }

    private JsonObject asset(String op, JsonObject args) {
        CallContext.Identity caller = CallContext.identity();
        if (caller == null || !assets || socket == null) throw new EditException("the personal asset library is unavailable; connect an updated panel with object storage");
        if (caller.assetKey() == null) throw new EditException("the panel is busy and could not open your asset library; try again");
        JsonObject frame = new JsonObject();
        frame.addProperty("t", RelayProtocol.ASSET);
        frame.addProperty("op", op);
        frame.addProperty("assetKey", caller.assetKey());
        frame.add("args", args);
        try {
            return answered(frame);
        } catch (IOException e) {
            // The panel's own words name the library ("no asset 'x' in your library"); a transport failure does not.
            String msg = e.getMessage();
            throw new EditException(msg.startsWith(REFUSED) ? msg.substring(REFUSED.length()) : "asset library: " + msg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EditException("asset request interrupted");
        }
    }

    // ---- players: account links and in-game saves ----
    // These block until the panel answers (at most ACCOUNT_TIMEOUT), so callers run them off the server thread.

    /** Links the player to the panel account that made {@code code}; answers {"linked":true,"already":bool}. */
    public JsonObject link(UUID player, String name, String code) {
        JsonObject frame = playerFrame(RelayProtocol.LINK, player, name);
        frame.addProperty("code", code);
        return ask(frame);
    }

    /** Removes the player's link; answers {"unlinked":true}. */
    public JsonObject unlink(UUID player, String name) {
        return ask(playerFrame(RelayProtocol.UNLINK, player, name));
    }

    /**
     * The library of the panel account the player is linked to, as {@link AssetLibrary#saveRegion} takes it: only
     * {@code list}, {@code check} and {@code save}. The panel finds the account from the link; the plugin never names
     * one. Throws at once when this panel cannot take in-game requests, before anything is read.
     */
    public BiFunction<String, JsonObject, JsonObject> playerLibrary(UUID player, String name) {
        playerFrame(RelayProtocol.PLAYER_ASSET, player, name);
        if (!assets) throw new EditException("the panel has no asset library yet (it needs object storage); ask its owner to set one up");
        return (op, args) -> {
            JsonObject frame = playerFrame(RelayProtocol.PLAYER_ASSET, player, name);
            frame.addProperty("op", op);
            frame.add("args", args);
            return ask(frame);
        };
    }

    private JsonObject playerFrame(String type, UUID player, String name) {
        if (socket == null || !welcomed) throw new EditException("this server is not connected to the panel right now; try again in a moment");
        if (!links) throw new EditException("this panel does not support account linking yet; ask its owner to update it");
        JsonObject frame = new JsonObject();
        frame.addProperty("t", type);
        JsonObject who = new JsonObject();
        who.addProperty("uuid", player.toString());
        who.addProperty("name", name);
        frame.add("player", who);
        return frame;
    }

    private JsonObject ask(JsonObject frame) {
        try {
            return answered(frame, ACCOUNT_TIMEOUT);
        } catch (IOException e) {
            String msg = e.getMessage();
            // The panel's own words tell the player what to do; a dropped or silent connection is worth another try.
            throw new EditException(msg.startsWith(REFUSED) ? msg.substring(REFUSED.length()) : msg + "; try again in a moment");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EditException("interrupted");
        }
    }

    /** Uploads the entries the panel does not have yet, one at a time, on the upload thread. */
    private void scheduleUploads() {
        if (!archive || !uploadQueued.compareAndSet(false, true)) return;
        try {
            uploads.submit(() -> {
                uploadQueued.set(false);
                for (String id : history.pendingArchives()) {
                    if (!archive || socket == null) return;
                    try {
                        upload(id);
                    } catch (RuntimeException | IOException e) {
                        logger.log(Level.WARNING, "Relay: could not archive history " + id + " (" + describe(e) + "); trying again later");
                        return;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // stopping
        }
    }

    private void upload(String id) throws IOException, InterruptedException {
        Map<String, java.nio.file.Path> files = history.archiveFiles(id);
        if (!files.isEmpty()) {
            JsonObject ask = new JsonObject();
            ask.addProperty("t", RelayProtocol.UPLOAD);
            ask.addProperty("kind", "history");
            ask.addProperty("entry", id);
            JsonArray list = new JsonArray();
            for (Map.Entry<String, java.nio.file.Path> f : files.entrySet()) {
                JsonObject one = new JsonObject();
                one.addProperty("name", f.getKey());
                one.addProperty("size", java.nio.file.Files.size(f.getValue()));
                list.add(one);
            }
            ask.add("files", list);
            JsonObject urls = answered(ask).getAsJsonObject("urls");
            for (Map.Entry<String, java.nio.file.Path> f : files.entrySet()) {
                int status = put(URI.create(urls.get(f.getKey()).getAsString()), f.getValue());
                if (status / 100 != 2) throw new IOException("storage answered " + status + " for " + f.getKey());
            }
        }
        JsonObject done = new JsonObject();
        done.addProperty("t", RelayProtocol.UPLOADED);
        done.addProperty("kind", "history");
        done.addProperty("entry", id);
        done.add("detail", history.archiveDetail(id));
        JsonArray names = new JsonArray();
        files.keySet().forEach(names::add);
        done.add("files", names);
        answered(done);
        history.markArchived(id);
    }

    /** Uploads a file with one PUT and returns the status; redirects are not followed. */
    private static int put(URI to, java.nio.file.Path file) throws IOException {
        HttpURLConnection c = (HttpURLConnection) to.toURL().openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(5 * 60_000);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("PUT");
            c.setRequestProperty("Content-Type", "application/gzip");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(java.nio.file.Files.size(file));
            try (java.io.OutputStream out = c.getOutputStream()) {
                java.nio.file.Files.copy(file, out);
            }
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    /** Asks the panel and waits for its answer's body; fails unless the answer is 2xx. */
    private JsonObject answered(JsonObject ask) throws IOException, InterruptedException {
        return answered(ask, Duration.ofSeconds(60));
    }

    private JsonObject answered(JsonObject ask, Duration timeout) throws IOException, InterruptedException {
        long id = nextAsk.getAndIncrement();
        ask.addProperty("id", id);
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        answers.put(id, f);
        send(ask);
        JsonObject answer;
        try {
            answer = f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("disconnected from the panel");
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("the panel did not answer");
        } finally {
            answers.remove(id);
        }
        int status = answer.has("status") ? answer.get("status").getAsInt() : 500;
        JsonElement body = answer.get("body");
        if (status / 100 != 2) {
            String msg = body != null && body.isJsonObject() && body.getAsJsonObject().has("error")
                    ? body.getAsJsonObject().get("error").getAsString() : "status " + status;
            throw new IOException(REFUSED + msg);
        }
        return body != null && body.isJsonObject() ? body.getAsJsonObject() : new JsonObject();
    }

    private RestApi.Response history(JsonObject frame) {
        if (history == null) return RestApi.error(404, "history is turned off on this server (history.enabled)");
        JsonObject args = frame.has("args") && frame.get("args").isJsonObject() ? frame.getAsJsonObject("args") : new JsonObject();
        String op = frame.has("op") ? frame.get("op").getAsString() : "";
        return CallContext.withSource("panel", () -> switch (op) {
            case "list" -> new RestApi.Response(200, history.list(
                    args.has("limit") ? Math.max(1, Math.min(500, args.get("limit").getAsInt())) : 50,
                    args.has("before") ? args.get("before").getAsString() : null));
            case "get" -> new RestApi.Response(200, history.get(str(args, "id")));
            case "correctionStats" -> new RestApi.Response(200, history.correctionStats());
            case "render" -> new RestApi.Response(200, history.render(str(args, "id"),
                    args.has("view") ? args.get("view").getAsString() : null,
                    args.has("corner") ? args.get("corner").getAsString() : null));
            case "blocks" -> new RestApi.Response(200, history.blocks(str(args, "id")));
            case "corrections" -> new RestApi.Response(200, history.corrections(str(args, "id")));
            case "correctionRender" -> new RestApi.Response(200, history.correctionRender(str(args, "id"),
                    args.has("view") ? args.get("view").getAsString() : null,
                    args.has("corner") ? args.get("corner").getAsString() : null));
            case "correctionBlocks" -> new RestApi.Response(200, history.correctionBlocks(str(args, "id")));
            case "revert" -> {
                JsonObject done = args.has("diff")
                        ? history.revertArchived(str(args, "id"), args.getAsJsonObject("entry"),
                                java.util.Base64.getDecoder().decode(str(args, "diff")), bool(args, "force"))
                        : history.revert(str(args, "id"), bool(args, "force"));
                feedback(args);
                yield new RestApi.Response(200, done);
            }
            default -> RestApi.error(400, "unknown history op '" + op + "'");
        });
    }

    private RestApi.Response draft(JsonObject frame) {
        if (drafts == null) return RestApi.error(404, "drafts are not available on this server");
        JsonObject args = frame.has("args") && frame.get("args").isJsonObject() ? frame.getAsJsonObject("args") : new JsonObject();
        String op = frame.has("op") ? frame.get("op").getAsString() : "";
        String by = args.has("by") ? args.get("by").getAsString() : "panel";
        return CallContext.withSource("panel", () -> switch (op) {
            case "list" -> new RestApi.Response(200, drafts.list());
            case "get" -> new RestApi.Response(200, drafts.get(str(args, "id")));
            case "render" -> new RestApi.Response(200, drafts.render(str(args, "id"),
                    args.has("view") ? args.get("view").getAsString() : null,
                    args.has("corner") ? args.get("corner").getAsString() : null));
            case "blocks" -> new RestApi.Response(200, drafts.blocks(str(args, "id")));
            case "approve" -> new RestApi.Response(200, drafts.approve(str(args, "id"), bool(args, "force"), by));
            case "reject" -> {
                JsonObject done = drafts.reject(str(args, "id"), by);
                feedback(args);
                yield new RestApi.Response(200, done);
            }
            case "export" -> new RestApi.Response(200, drafts.export(str(args, "world"), box(args.getAsJsonArray("box"))));
            case "import" -> {
                JsonArray origin = args.getAsJsonArray("origin");
                JsonArray size = args.getAsJsonArray("size");
                yield new RestApi.Response(200, drafts.importRegion(str(args, "world"),
                        new Vec3(origin.get(0).getAsInt(), origin.get(1).getAsInt(), origin.get(2).getAsInt()),
                        size.get(0).getAsInt(), size.get(1).getAsInt(), size.get(2).getAsInt(), str(args, "data"),
                        !args.has("air") || bool(args, "air"), bool(args, "skipUnknown"),
                        args.has("label") ? args.get("label").getAsString() : null));
            }
            default -> RestApi.error(400, "unknown draft op '" + op + "'");
        });
    }

    /**
     * Feedback that came with a reject or revert: {"feedback":"...","remember":true}. The action already happened, so
     * feedback that cannot be stored is logged rather than turned into a failed reply.
     */
    private void feedback(JsonObject args) {
        if (!args.has("feedback") || args.get("feedback").getAsString().isBlank()) return;
        try {
            service.lessons().feedback(str(args, "id"), str(args, "feedback"), bool(args, "remember"), by(args));
        } catch (EditException e) {
            logger.warning("Relay: feedback on " + str(args, "id") + " was not kept: " + e.getMessage());
        }
    }

    /** The panel's Lessons page, and feedback given after a reject or revert ({@code feedback}). */
    private RestApi.Response lessons(JsonObject frame) {
        JsonObject args = frame.has("args") && frame.get("args").isJsonObject() ? frame.getAsJsonObject("args") : new JsonObject();
        String op = frame.has("op") ? frame.get("op").getAsString() : "";
        Lessons lessons = service.lessons();
        return switch (op) {
            case "list" -> new RestApi.Response(200, history == null ? lessons.listJson() : history.lessonReport(lessons.list()));
            case "add" -> {
                java.util.List<String> from = args.has("entry") ? java.util.List.of(str(args, "entry")) : java.util.List.of();
                Lessons.Saved saved = lessons.add(str(args, "text"), Lessons.MANUAL, from, by(args));
                JsonObject o = new JsonObject();
                o.add("lesson", Lessons.json(saved.lesson()));
                if (saved.replaced() != null) o.add("replaced", Lessons.json(saved.replaced()));
                yield new RestApi.Response(200, o);
            }
            case "edit" -> {
                JsonObject o = new JsonObject();
                o.add("lesson", Lessons.json(lessons.edit(str(args, "id"), str(args, "text"))));
                yield new RestApi.Response(200, o);
            }
            case "delete" -> {
                if (!lessons.remove(str(args, "id"))) yield RestApi.error(404, "no lesson '" + str(args, "id") + "'");
                JsonObject o = new JsonObject();
                o.addProperty("deleted", str(args, "id"));
                yield new RestApi.Response(200, o);
            }
            case "seen" -> {
                JsonObject o = new JsonObject();
                o.addProperty("seen", lessons.markSeen());
                yield new RestApi.Response(200, o);
            }
            case "feedback" -> new RestApi.Response(200,
                    lessons.feedback(str(args, "id"), str(args, "feedback"), bool(args, "remember"), by(args)));
            default -> RestApi.error(400, "unknown lessons op '" + op + "'");
        };
    }

    private static String by(JsonObject args) {
        return args.has("by") && !args.get("by").getAsString().isBlank() ? args.get("by").getAsString() : "panel";
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && o.get(key).getAsBoolean();
    }

    private static Box box(JsonArray a) {
        if (a == null || a.size() != 6) throw new EditException("box must be [x1,y1,z1,x2,y2,z2]");
        return Box.of(new Vec3(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()),
                new Vec3(a.get(3).getAsInt(), a.get(4).getAsInt(), a.get(5).getAsInt()));
    }

    private static String str(JsonObject o, String key) {
        if (!o.has(key)) throw new EditException("missing '" + key + "'");
        return o.get(key).getAsString();
    }

    private void send(JsonObject frame) {
        WebSocketClient s = socket;
        if (s == null) return;
        String text = gson.toJson(frame);
        enqueue(s, ws -> ws.sendText(text));
    }

    private interface Send {
        void to(WebSocketClient ws) throws IOException;
    }

    private void enqueue(WebSocketClient s, Send op) {
        try {
            sender.execute(() -> {
                if (s != socket) return;
                try {
                    op.to(s);
                } catch (IOException | RuntimeException e) {
                    logger.log(Level.FINE, "Relay send failed", e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopping) {
            // stop() ran in between
        }
    }

    private void closed(WebSocketClient ws, int code, String reason) {
        if (ws != socket) return;
        String why = switch (code) {
            case RelayProtocol.CLOSE_BAD_TOKEN -> "the panel rejected relay.token";
            case RelayProtocol.CLOSE_BAD_PROTOCOL -> "the panel speaks another relay protocol: " + reason;
            case RelayProtocol.CLOSE_REPLACED -> "another server connected with the same relay.token";
            default -> "closed (" + code + (reason == null || reason.isEmpty() ? "" : ", " + reason) + ")";
        };
        if (code == RelayProtocol.CLOSE_BAD_TOKEN || code == RelayProtocol.CLOSE_BAD_PROTOCOL || code == RelayProtocol.CLOSE_REPLACED) {
            synchronized (this) {
                backoff = MAX_BACKOFF;
            }
        }
        failed(why);
    }

    private final class Listener implements WebSocketClient.Listener {
        /** Set before reading starts. */
        private WebSocketClient ws;

        @Override
        public void onText(String text) {
            lastHeard = System.currentTimeMillis();
            handle(text);
        }

        @Override
        public void onHeard() {
            lastHeard = System.currentTimeMillis();
        }

        @Override
        public void onClose(int code, String reason) {
            closed(ws, code, reason);
        }

        @Override
        public void onError(Throwable error) {
            if (ws == socket) failed(describe(error));
        }
    }

    private static String describe(Throwable t) {
        while (t.getCause() != null && (t instanceof java.util.concurrent.CompletionException || t.getMessage() == null)) t = t.getCause();
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
