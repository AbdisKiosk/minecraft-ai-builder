package me.abdiskiosk.minecraftaibuilder.plugin.tools;

import com.google.gson.JsonObject;

/** @param listed false for tools that stay callable (REST, old names) but are left out of the list sent to Claude */
public record Tool(String name, String description, JsonObject inputSchema, Handler handler, boolean listed) {

    public Tool(String name, String description, JsonObject inputSchema, Handler handler) {
        this(name, description, inputSchema, handler, true);
    }

    public Tool unlisted() {
        return new Tool(name, description, inputSchema, handler, false);
    }

    @FunctionalInterface
    public interface Handler {
        ToolResult call(Args args);
    }
}
