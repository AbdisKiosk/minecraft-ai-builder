package me.abdiskiosk.minecraftaibuilder.plugin.tools;

import com.google.gson.JsonParser;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.IsoRenderer;
import me.abdiskiosk.minecraftaibuilder.plugin.BuildService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** render_region rejects bad arguments before it reads the world, so no server is needed. */
class RenderRegionArgsTest {

    private final ToolRegistry registry = new ToolRegistry(null, Logger.getAnonymousLogger());
    private final BuildService.Caller caller = new BuildService.Caller(null, "console", "world", null, 0, List.of(), 0, 255);

    RenderRegionArgsTest() {
        Tools.registerAll(registry, null);
    }

    private String error(String args) {
        Tool t = registry.all().stream().filter(x -> x.name().equals("render_region")).findFirst().orElseThrow();
        String json = "{\"region\":[0,64,0,4,68,4]," + args + "}";
        return assertThrows(EditException.class,
                () -> t.handler().call(new Args(JsonParser.parseString(json).getAsJsonObject(), caller, null))).getMessage();
    }

    @Test
    void badViewAndCornerAreErrors() {
        assertEquals("view must be both, top, iso, north, south, east or west", error("\"view\":\"side\""));
        assertEquals("corner must be se, sw, ne or nw", error("\"view\":\"iso\",\"corner\":\"south\""));
        assertTrue(error("\"view\":\"north\",\"corner\":\"nw\"").contains("corner only applies to view iso or both"));
        assertTrue(error("\"view\":\"top\",\"corner\":\"se\"").contains("leave it out for view top"));
    }

    @Test
    void parsesViewsAndCorners() {
        assertEquals(IsoRenderer.View.WEST, Tools.renderView("west"));
        assertEquals(IsoRenderer.Corner.SE, Tools.renderCorner(null, IsoRenderer.View.NORTH));
        assertEquals(IsoRenderer.Corner.NW, Tools.renderCorner("nw", IsoRenderer.View.BOTH));
    }
}
