package me.abdiskiosk.minecraftaibuilder.plugin.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.Tool;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.ToolRegistry;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class McpEndpointTest {

    private final McpEndpoint endpoint;

    McpEndpointTest() {
        ToolRegistry registry = new ToolRegistry(null, Logger.getAnonymousLogger());
        registry.register(new Tool("echo", "test tool", JsonParser.parseString("{\"type\":\"object\"}").getAsJsonObject(),
                args -> ToolResult.of(new JsonObject())));
        endpoint = new McpEndpoint(registry, "test");
    }

    private JsonObject call(String json) {
        JsonElement r = endpoint.handle(JsonParser.parseString(json));
        return r == null ? null : r.getAsJsonObject();
    }

    @Test
    void initializeNegotiatesVersion() {
        JsonObject r = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}");
        JsonObject result = r.getAsJsonObject("result");
        assertEquals("2025-03-26", result.get("protocolVersion").getAsString());
        assertTrue(result.getAsJsonObject("capabilities").has("tools"));
        assertEquals("aibuilder", result.getAsJsonObject("serverInfo").get("name").getAsString());

        JsonObject unknown = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}");
        assertEquals(McpEndpoint.PROTOCOL_VERSIONS.get(0), unknown.getAsJsonObject("result").get("protocolVersion").getAsString());
    }

    @Test
    void notificationsGetNoResponse() {
        assertNull(call("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
    }

    @Test
    void listsTools() {
        JsonObject r = call("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"tools/list\"}");
        JsonObject tool = r.getAsJsonObject("result").getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("echo", tool.get("name").getAsString());
        assertTrue(tool.has("inputSchema"));
        assertEquals("a", r.get("id").getAsString());
    }

    @Test
    void unknownMethodAndToolAreErrors() {
        assertEquals(-32601, call("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\"}")
                .getAsJsonObject("error").get("code").getAsInt());
        assertEquals(-32602, call("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\"}}")
                .getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void pingReturnsEmptyResult() {
        assertEquals(0, call("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ping\"}").getAsJsonObject("result").size());
    }
}
