package me.abdiskiosk.minecraftaibuilder.plugin.mcp;

import me.abdiskiosk.minecraftaibuilder.plugin.tools.Tools;
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

    /**
     * The tool list is sent to Claude in every session, so its size is a fixed token cost: more than a whole small
     * build costs in tool calls. It was 11,183 characters (about 3,300 tokens) at first and 7,765 with 17 tools;
     * one build tool whose ops are a line each brought it to about 5,400 while adding roof, tree and light.
     */
    @Test
    void toolListStaysWithinBudget() {
        ToolRegistry registry = new ToolRegistry(null, Logger.getAnonymousLogger());
        Tools.registerAll(registry, null);
        McpEndpoint real = new McpEndpoint(registry, "test");
        JsonObject list = real.handle(JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .getAsJsonObject().getAsJsonObject("result");
        int chars = list.toString().length();
        assertTrue(chars <= 5400, "tools/list is " + chars + " characters; shorten descriptions or schemas before raising the budget");
        assertTrue(McpEndpoint.INSTRUCTIONS.length() <= 1300, "instructions are " + McpEndpoint.INSTRUCTIONS.length() + " characters");

        java.util.Set<String> names = new java.util.HashSet<>();
        list.getAsJsonArray("tools").forEach(t -> names.add(t.getAsJsonObject().get("name").getAsString()));
        assertEquals(java.util.Set.of("get_context", "block_info", "read_region", "check_region", "render_region", "build",
                "decorate", "save_blueprint", "undo", "job_status", "tell"), names);
        for (String hidden : java.util.List.of("batch", "fill", "set_blocks", "place_blueprint", "cylinder", "walls", "replace",
                "get_selection", "clone")) {
            assertTrue(registry.has(hidden), hidden + " should still be callable");
        }
        assertEquals(ToolStats.SESSION, real.stats().rows().get(0).tool());
        assertEquals(chars, real.stats().rows().get(0).resultChars());
    }

    /** One oversized answer would cost more than a whole build, so it is refused with a way to ask for less. */
    @Test
    void anOversizedResultIsRefused() {
        JsonObject big = new JsonObject();
        big.addProperty("rows", "x".repeat(McpEndpoint.MAX_RESULT_CHARS));
        ToolResult refused = endpoint.capped("read_region", ToolResult.of(big));
        assertTrue(refused.error());
        String text = refused.data().get("error").getAsString();
        assertTrue(text.startsWith("read_region would return") && text.length() < 300, text);

        ToolResult small = ToolResult.of(new JsonObject());
        assertSame(small, endpoint.capped("read_region", small));
    }

    @Test
    void statsCountCallsAndImages() {
        call("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"a\":1}}}");
        call("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\"}}");
        ToolStats.Row row = endpoint.stats().rows().get(0);
        assertEquals("echo", row.tool());
        assertEquals(2, row.calls());
        assertEquals("{\"a\":1}".length() + 2, row.argChars());
        assertTrue(row.resultChars() > 0); // the registry has no server behind it, so the result is an error text

        byte[] png = new byte[24];
        png[1] = 'P'; png[2] = 'N'; png[3] = 'G';
        png[18] = 3; png[19] = (byte) 0xC0; // width 960
        png[22] = 2; png[23] = (byte) 0xBC; // height 700
        assertEquals(960L * 700, ToolStats.pngPixels(png));
        assertEquals(0, ToolStats.pngPixels(null));
        ToolStats stats = new ToolStats();
        stats.record("render_region", 70, 35, 960L * 700);
        assertEquals(926, stats.rows().get(0).tokens()); // 105 chars / 3.5 + 672000 px / 750
        stats.reset();
        assertTrue(stats.rows().isEmpty());
    }
}
