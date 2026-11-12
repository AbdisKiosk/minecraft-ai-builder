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
import me.abdiskiosk.minecraftaibuilder.plugin.CallContext;
import me.abdiskiosk.minecraftaibuilder.plugin.Drafts;
import me.abdiskiosk.minecraftaibuilder.plugin.History;
import me.abdiskiosk.minecraftaibuilder.plugin.http.RestApi;
import me.abdiskiosk.minecraftaibuilder.plugin.mcp.McpEndpoint;

import java.net.URI;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
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

    private final URI uri;
    private final String token;
    private final McpEndpoint mcp;
    private final RestApi api;
    private final History history;
    private final Drafts drafts;
    private final BuildService service;
    private final Supplier<JsonObject> hello;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService workers = Executors.newFixedThreadPool(4, daemon("AIBuilder-Relay"));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(daemon("AIBuilder-Relay-Timer"));
    private final Map<String, Long> lastProgress = new ConcurrentHashMap<>();
    /** Requests this plugin made of the panel, by id, waiting for their answer. */
    private final Map<Long, CompletableFuture<JsonObject>> answers = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong nextAsk = new java.util.concurrent.atomic.AtomicLong(1);
    private final ExecutorService uploads = Executors.newSingleThreadExecutor(daemon("AIBuilder-Relay-Upload"));
    private final java.util.concurrent.atomic.AtomicBoolean uploadQueued = new java.util.concurrent.atomic.AtomicBoolean();
    /** The panel on this connection keeps history in object storage. */
    private volatile boolean archive;

    private volatile WebSocket socket;
    private volatile boolean stopped;
    private volatile String status = "starting";
    private volatile long lastHeard;
    private volatile long lastBeat = System.currentTimeMillis();
    private long backoff = 1;
    /** Sends must not overlap (java.net.http.WebSocket rule), so each waits for the previous one. */
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    /**
     * @param history null when the history is turned off
     * @param drafts  null when drafts are not available
     */
    public RelayClient(String url, String token, McpEndpoint mcp, RestApi api, History history, Drafts drafts,
                       BuildService service, Supplier<JsonObject> hello, Logger logger) {
        this.uri = relayUri(url);
        this.token = token;
        this.mcp = mcp;
        this.api = api;
        this.history = history;
        this.drafts = drafts;
        this.service = service;
        this.hello = hello;
        this.logger = logger;
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
        WebSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.sendClose(WebSocket.NORMAL_CLOSURE, "server stopping").get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                s.abort();
            }
        }
        workers.shutdownNow();
        uploads.shutdownNow();
        timer.shutdownNow();
    }

    private void connect() {
        if (stopped) return;
        http.newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, new Listener())
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        failed(describe(error));
                        return;
                    }
                    synchronized (this) {
                        socket = ws;
                        sendChain = CompletableFuture.completedFuture(null);
                        backoff = 1;
                    }
                    lastHeard = System.currentTimeMillis();
                    if (!status.startsWith("connected")) logger.info("Relay connected to " + uri);
                    status = "connected to " + uri;
                    sendHello();
                });
    }

    private synchronized void failed(String why) {
        socket = null;
        archive = false;
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
        WebSocket s = socket;
        if (s == null) return;
        // A late heartbeat means this process was paused (the machine slept), not that the panel went quiet:
        // give the panel a fresh ping to answer instead of dropping a working connection.
        if (gap > 2 * PING_SECONDS * 1000) lastHeard = now;
        if (now - lastHeard > DEAD_MILLIS) {
            s.abort();
            failed("no answer from the panel");
            return;
        }
        enqueue(s, ws -> ws.sendPing(ByteBuffer.allocate(0)));
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
        workers.submit(() -> {
            RestApi.Response r;
            try {
                r = switch (type) {
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
                    default -> RestApi.error(400, "unknown frame type '" + type + "'");
                };
            } catch (EditException e) {
                r = RestApi.error(400, e.getMessage());
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Relay request failed", e);
                r = RestApi.error(500, "internal error; see the server log");
            }
            JsonObject reply = new JsonObject();
            reply.addProperty("t", RelayProtocol.REPLY);
            reply.add("id", id);
            reply.addProperty("status", r.status());
            reply.add("body", r.body() == null ? JsonNull.INSTANCE : r.body());
            send(reply);
        });
    }

    // ---- history archive ----

    private void welcome(JsonObject frame) {
        if (history == null || !frame.has("archive") || !frame.get("archive").getAsBoolean()) return;
        archive = true;
        history.archiveTo(this::scheduleUploads);
        scheduleUploads();
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
                HttpRequest put = HttpRequest.newBuilder(URI.create(urls.get(f.getKey()).getAsString()))
                        .timeout(Duration.ofMinutes(5))
                        .header("Content-Type", "application/gzip")
                        .PUT(HttpRequest.BodyPublishers.ofFile(f.getValue()))
                        .build();
                HttpResponse<String> r = http.send(put, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() / 100 != 2) throw new IOException("storage answered " + r.statusCode() + " for " + f.getKey());
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

    /** Asks the panel and waits for its answer's body; fails unless the answer is 2xx. */
    private JsonObject answered(JsonObject ask) throws IOException, InterruptedException {
        long id = nextAsk.getAndIncrement();
        ask.addProperty("id", id);
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        answers.put(id, f);
        send(ask);
        JsonObject answer;
        try {
            answer = f.get(60, TimeUnit.SECONDS);
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
            throw new IOException("the panel refused: " + msg);
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
            case "render" -> new RestApi.Response(200, history.render(str(args, "id"),
                    args.has("view") ? args.get("view").getAsString() : null,
                    args.has("corner") ? args.get("corner").getAsString() : null));
            case "blocks" -> new RestApi.Response(200, history.blocks(str(args, "id")));
            case "revert" -> new RestApi.Response(200, args.has("diff")
                    ? history.revertArchived(str(args, "id"), args.getAsJsonObject("entry"),
                            java.util.Base64.getDecoder().decode(str(args, "diff")), bool(args, "force"))
                    : history.revert(str(args, "id"), bool(args, "force")));
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
            case "reject" -> new RestApi.Response(200, drafts.reject(str(args, "id"), by));
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
        WebSocket s = socket;
        if (s == null) return;
        String text = gson.toJson(frame);
        enqueue(s, ws -> ws.sendText(text, true));
    }

    private synchronized void enqueue(WebSocket s, java.util.function.Function<WebSocket, CompletableFuture<WebSocket>> op) {
        sendChain = sendChain.handle((v, e) -> null)
                .thenCompose(v -> s == socket ? op.apply(s) : CompletableFuture.completedFuture(s))
                .exceptionally(e -> {
                    logger.log(Level.FINE, "Relay send failed", e);
                    return null;
                });
    }

    private void closed(WebSocket ws, int code, String reason) {
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

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            lastHeard = System.currentTimeMillis();
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                handle(text);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket ws, ByteBuffer message) {
            lastHeard = System.currentTimeMillis();
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket ws, ByteBuffer message) {
            lastHeard = System.currentTimeMillis();
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            closed(ws, code, reason);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
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
