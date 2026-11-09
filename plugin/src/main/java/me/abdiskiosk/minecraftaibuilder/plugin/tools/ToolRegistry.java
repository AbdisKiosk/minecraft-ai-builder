package me.abdiskiosk.minecraftaibuilder.plugin.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.plugin.BuildService;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Tools shared by the MCP endpoint and the REST API, so both behave identically. */
public final class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final BuildService service;
    private final Logger logger;

    public ToolRegistry(BuildService service, Logger logger) {
        this.service = service;
        this.logger = logger;
    }

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public Collection<Tool> all() {
        return tools.values();
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /** Calls a tool; never throws, failures come back as {@link ToolResult#error}. */
    public ToolResult call(String name, JsonObject arguments) {
        Tool tool = tools.get(name);
        if (tool == null) return ToolResult.error("unknown tool '" + name + "'");
        JsonObject json = arguments == null ? new JsonObject() : arguments;
        try {
            Args bare = new Args(json, null, service);
            BuildService.Caller caller = service.resolve(bare.str("player", null), bare.str("world", null));
            return tool.handler().call(new Args(json, caller, service));
        } catch (EditException e) {
            return ToolResult.error(e.getMessage());
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException | ClassCastException
                 | NumberFormatException | IndexOutOfBoundsException e) {
            return ToolResult.error("bad arguments for " + name + ": " + e.getMessage());
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "tool " + name + " failed", e);
            return ToolResult.error(name + " failed: " + e);
        }
    }
}
