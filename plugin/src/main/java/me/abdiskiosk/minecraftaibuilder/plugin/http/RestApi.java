package me.abdiskiosk.minecraftaibuilder.plugin.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.Tool;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.ToolRegistry;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.ToolResult;

import java.util.Base64;

/** {@code /api/<tool>} as status + JSON, shared by the local HTTP server and the panel relay. */
public final class RestApi {

    public record Response(int status, JsonElement body) {
    }

    private final ToolRegistry tools;

    public RestApi(ToolRegistry tools) {
        this.tools = tools;
    }

    /** {@code GET /api/tools}. */
    public Response list() {
        JsonArray list = new JsonArray();
        for (Tool tool : tools.all()) list.add(tool.name());
        return new Response(200, list);
    }

    /** {@code POST /api/<name>}. */
    public Response call(String name, JsonObject args) {
        if (!tools.has(name)) return error(404, "unknown tool '" + name.replace("\"", "") + "'");
        ToolResult result = tools.call(name, args);
        JsonObject out = result.data().deepCopy();
        if (result.png() != null) out.addProperty("png_base64", Base64.getEncoder().encodeToString(result.png()));
        return new Response(result.error() ? 400 : 200, out);
    }

    public static Response error(int status, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return new Response(status, o);
    }
}
