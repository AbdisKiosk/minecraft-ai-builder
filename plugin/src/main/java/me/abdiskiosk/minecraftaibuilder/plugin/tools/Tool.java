package me.abdiskiosk.minecraftaibuilder.plugin.tools;

import com.google.gson.JsonObject;

public record Tool(String name, String description, JsonObject inputSchema, Handler handler) {

    @FunctionalInterface
    public interface Handler {
        ToolResult call(Args args);
    }
}
