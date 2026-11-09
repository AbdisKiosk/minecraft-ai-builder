package me.abdiskiosk.minecraftaibuilder.plugin.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.abdiskiosk.minecraftaibuilder.plugin.mcp.McpEndpoint;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.Tool;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.ToolRegistry;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.ToolResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One HTTP server for both clients: {@code POST /mcp} (MCP Streamable HTTP, for Claude Code) and
 * {@code POST /api/<tool>} (plain JSON, for scripts). Every route except {@code /api/health} needs the bearer token.
 */
public final class HttpApi {

    private static final int MAX_BODY = 16 * 1024 * 1024;

    private final String bind;
    private final int port;
    private final byte[] token;
    private final McpEndpoint mcp;
    private final ToolRegistry tools;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private HttpServer server;
    private ExecutorService executor;

    public HttpApi(String bind, int port, String token, McpEndpoint mcp, ToolRegistry tools, Logger logger) {
        this.bind = bind;
        this.port = port;
        this.token = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        this.mcp = mcp;
        this.tools = tools;
        this.logger = logger;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "AIBuilder-HTTP");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/mcp", this::guarded);
        server.createContext("/api/", this::guarded);
        server.start();
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    private void guarded(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/api/health")) {
                send(ex, 200, "{\"ok\":true}");
                return;
            }
            if (!authorised(ex)) {
                send(ex, 401, "{\"error\":\"missing or wrong bearer token\"}");
                return;
            }
            if (!originAllowed(ex)) {
                send(ex, 403, "{\"error\":\"origin not allowed\"}");
                return;
            }
            if (path.equals("/mcp")) {
                handleMcp(ex);
            } else {
                handleApi(ex, path.substring("/api/".length()));
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "HTTP request failed", e);
            try {
                send(ex, 500, "{\"error\":\"internal error; see the server log\"}");
            } catch (IOException | RuntimeException ignored) {
                // the response was already started or the client is gone
            }
        }
    }

    private void handleMcp(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            // Stateless server: no SSE stream and no sessions to delete.
            ex.getResponseHeaders().add("Allow", "POST");
            send(ex, 405, "");
            return;
        }
        JsonElement body;
        try {
            body = readJson(ex);
        } catch (JsonParseException | IllegalStateException e) {
            send(ex, 400, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"parse error\"}}");
            return;
        }
        JsonElement response = mcp.handle(body);
        if (response == null) {
            send(ex, 202, "");
        } else {
            send(ex, 200, gson.toJson(response));
        }
    }

    private void handleApi(HttpExchange ex, String name) throws IOException {
        if (name.equals("tools") && ex.getRequestMethod().equals("GET")) {
            JsonArray list = new JsonArray();
            for (Tool tool : tools.all()) list.add(tool.name());
            send(ex, 200, gson.toJson(list));
            return;
        }
        if (!ex.getRequestMethod().equals("POST")) {
            send(ex, 405, "{\"error\":\"use POST /api/<tool>\"}");
            return;
        }
        if (!tools.has(name)) {
            send(ex, 404, "{\"error\":\"unknown tool '" + name.replace("\"", "") + "'\"}");
            return;
        }
        JsonObject args;
        try {
            JsonElement body = readJson(ex);
            args = body.isJsonObject() ? body.getAsJsonObject() : new JsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            send(ex, 400, "{\"error\":\"body must be a JSON object\"}");
            return;
        }
        ToolResult result = tools.call(name, args);
        JsonObject out = result.data().deepCopy();
        if (result.png() != null) out.addProperty("png_base64", Base64.getEncoder().encodeToString(result.png()));
        send(ex, result.error() ? 400 : 200, gson.toJson(out));
    }

    private boolean authorised(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        return header != null && MessageDigest.isEqual(token, header.getBytes(StandardCharsets.UTF_8));
    }

    /** Blocks browsers on other sites from talking to the local server (DNS rebinding). */
    private static boolean originAllowed(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.equals("null")) return true;
        try {
            String host = URI.create(origin).getHost();
            return host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static JsonElement readJson(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) throw new JsonParseException("body too large");
            return JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
        }
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0) ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
