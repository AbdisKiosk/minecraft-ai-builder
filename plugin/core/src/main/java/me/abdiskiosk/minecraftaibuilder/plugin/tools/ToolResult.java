package me.abdiskiosk.minecraftaibuilder.plugin.tools;

import com.google.gson.JsonObject;

/** Structured result of a tool call, optionally with a PNG image. */
public record ToolResult(JsonObject data, byte[] png, boolean error) {

    public static ToolResult of(JsonObject data) {
        return new ToolResult(data, null, false);
    }

    public static ToolResult image(byte[] png, JsonObject data) {
        return new ToolResult(data, png, false);
    }

    public static ToolResult error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return new ToolResult(o, null, true);
    }
}
