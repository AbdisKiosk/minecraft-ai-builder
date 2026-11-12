package me.abdiskiosk.minecraftaibuilder.plugin.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.ToolRegistry;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class McpEndpointEscapingTest {

    private final McpEndpoint endpoint;

    McpEndpointEscapingTest() {
        endpoint = new McpEndpoint(new ToolRegistry(null, Logger.getAnonymousLogger()), "test");
    }

    private JsonObject call(String json) {
        JsonElement r = endpoint.handle(JsonParser.parseString(json));
        return r == null ? null : r.getAsJsonObject();
    }

    @Test
    void toolTextIsNotHtmlEscaped() {
        JsonObject data = new JsonObject();
        data.addProperty("palette", "oak_stairs[facing=east,half=top] <>&'");
        JsonObject result = endpoint.callResult(ToolResult.of(data));
        String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(text.contains("oak_stairs[facing=east,half=top] <>&'"), text);
        assertFalse(text.contains("\\u003d"), text);
    }

    @Test
    void malformedRequestsGetAnErrorInsteadOfAbortingTheConnection() {
        JsonObject r = call("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":{\"not\":\"a string\"}}");
        assertEquals(7, r.get("id").getAsInt());
        assertEquals(-32600, r.getAsJsonObject("error").get("code").getAsInt());
        JsonObject name = call("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{\"name\":[1]}}");
        assertTrue(name.has("error"));
    }
}
