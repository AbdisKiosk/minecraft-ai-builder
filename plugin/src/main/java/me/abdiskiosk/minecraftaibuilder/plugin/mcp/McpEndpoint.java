package me.abdiskiosk.minecraftaibuilder.plugin.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.Tool;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.ToolRegistry;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.ToolResult;

import java.util.Base64;
import java.util.List;

/**
 * Minimal stateless MCP server over Streamable HTTP: JSON-RPC requests in, single JSON responses out.
 * Supports initialize, ping, tools/list and tools/call; notifications are acknowledged and ignored.
 */
public final class McpEndpoint {

    static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    static final String INSTRUCTIONS = """
            Builds in a live Minecraft server through WorldEdit.
            - Start with get_context (player position/facing, selection, server version).
            - Coordinates: +y up, +x east, +z south. "~n" is relative to the player's feet. A region is "selection" (the player's WorldEdit selection; prefer it when there is one) or [x1,y1,z1,x2,y2,z2].
            - Every tool also takes "player" (who acts; default: the only online player, needed for ~ and selections when several are online) and "world".
            - Patterns use WorldEdit syntax and modern block names with states, e.g. 80%stone_bricks,20%andesite or oak_stairs[facing=east,half=top].
            - Blocks differ by server version (get_context.server). Unknown blocks and bad property values are rejected before anything is built; call block_info for valid names and properties (facing, half, ...).
            - Each tool call (or one batch) is one undo step; undo reverts it. Group related ops in one batch with "origin" and "vars" to keep calls short.
            - Large or repetitive builds: write a script with the aibuilder Python SDK instead of many tool calls.
            - Check work with check_region (text, cheapest) first, then render_region (size small unless detail matters), not read_region mode=full.""";

    private final ToolRegistry tools;
    private final String version;
    private final ToolStats stats = new ToolStats();
    // No HTML escaping: it would turn "=" and "'" into \\u003d / \\u0027 in the text Claude reads (states, errors).
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public McpEndpoint(ToolRegistry tools, String version) {
        this.tools = tools;
        this.version = version;
    }

    /** Sizes of everything sent to and from Claude since the last reset. */
    public ToolStats stats() {
        return stats;
    }

    /** @return the response, or null when the message only contained notifications/responses */
    public JsonElement handle(JsonElement message) {
        if (message.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement e : message.getAsJsonArray()) {
                JsonObject r = e.isJsonObject() ? handleOne(e.getAsJsonObject()) : error(null, -32600, "invalid request");
                if (r != null) out.add(r);
            }
            return out.isEmpty() ? null : out;
        }
        if (!message.isJsonObject()) return error(null, -32600, "invalid request");
        return handleOne(message.getAsJsonObject());
    }

    private JsonObject handleOne(JsonObject req) {
        try {
            return dispatch(req);
        } catch (RuntimeException e) { // e.g. a method or tool name that is not a string
            JsonElement id = req.get("id");
            return id == null || id.isJsonNull() ? null : error(id, -32600, "invalid request: " + e.getMessage());
        }
    }

    private JsonObject dispatch(JsonObject req) {
        JsonElement id = req.get("id");
        if (!req.has("method")) {
            return null; // a response from the client; we never send requests
        }
        if (id == null || id.isJsonNull()) {
            return null; // notification
        }
        String method = req.get("method").getAsString();
        JsonObject params = req.has("params") && req.get("params").isJsonObject() ? req.getAsJsonObject("params") : new JsonObject();
        return switch (method) {
            case "initialize" -> {
                stats.record(ToolStats.SESSION, 0, INSTRUCTIONS.length(), 0);
                yield result(id, initialize(params));
            }
            case "ping" -> result(id, new JsonObject());
            case "tools/list" -> {
                JsonObject list = listTools();
                stats.record(ToolStats.SESSION, 0, gson.toJson(list).length(), 0);
                yield result(id, list);
            }
            case "tools/call" -> {
                if (!params.has("name")) yield error(id, -32602, "missing tool name");
                String name = params.get("name").getAsString();
                if (!tools.has(name)) yield error(id, -32602, "unknown tool: " + name);
                JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                        ? params.getAsJsonObject("arguments") : new JsonObject();
                ToolResult called = tools.call(name, args);
                stats.record(name, gson.toJson(args).length(), gson.toJson(called.data()).length(), ToolStats.pngPixels(called.png()));
                yield result(id, callResult(called));
            }
            default -> error(id, -32601, "method not found: " + method);
        };
    }

    private JsonObject initialize(JsonObject params) {
        String requested = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : null;
        JsonObject out = new JsonObject();
        out.addProperty("protocolVersion", PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.get(0));
        JsonObject capabilities = new JsonObject();
        JsonObject toolCaps = new JsonObject();
        toolCaps.addProperty("listChanged", false);
        capabilities.add("tools", toolCaps);
        out.add("capabilities", capabilities);
        JsonObject info = new JsonObject();
        info.addProperty("name", "aibuilder");
        info.addProperty("version", version);
        out.add("serverInfo", info);
        out.addProperty("instructions", INSTRUCTIONS);
        return out;
    }

    private JsonObject listTools() {
        JsonArray list = new JsonArray();
        for (Tool tool : tools.all()) {
            if (!tool.listed()) continue;
            JsonObject t = new JsonObject();
            t.addProperty("name", tool.name());
            t.addProperty("description", tool.description());
            t.add("inputSchema", tool.inputSchema());
            list.add(t);
        }
        JsonObject out = new JsonObject();
        out.add("tools", list);
        return out;
    }

    JsonObject callResult(ToolResult r) {
        JsonArray content = new JsonArray();
        if (r.png() != null) {
            JsonObject image = new JsonObject();
            image.addProperty("type", "image");
            image.addProperty("data", Base64.getEncoder().encodeToString(r.png()));
            image.addProperty("mimeType", "image/png");
            content.add(image);
        }
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", gson.toJson(r.data()));
        content.add(text);
        JsonObject out = new JsonObject();
        out.add("content", content);
        out.addProperty("isError", r.error());
        return out;
    }

    private static JsonObject result(JsonElement id, JsonObject result) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id);
        o.add("result", result);
        return o;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id);
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        o.add("error", err);
        return o;
    }
}
