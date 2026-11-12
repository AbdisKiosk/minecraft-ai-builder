package me.abdiskiosk.minecraftaibuilder.plugin.tools;

import com.google.gson.JsonParser;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import me.abdiskiosk.minecraftaibuilder.plugin.BuildService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DecorSpecTest {

    private final BuildService.Caller caller = new BuildService.Caller(null, "console", "world", null, 0, List.of(), 0, 255);

    private List<DecorSpec.Item> parse(String json) {
        return DecorSpec.parse(new Args(JsonParser.parseString(json).getAsJsonObject(), caller, null));
    }

    private String error(String items) {
        return assertThrows(EditException.class, () -> parse("{\"items\":" + items + "}")).getMessage();
    }

    @Test
    void parsesEveryKind() {
        List<DecorSpec.Item> items = parse("{\"items\":["
                + "{\"at\":[1,64,2],\"text\":[\"Inn\",\"Rooms\"]},"
                + "{\"at\":[1,64,3],\"contents\":{\"bread\":3,\"torch\":100}},"
                + "{\"at\":[1,65,3],\"frame\":\"compass\",\"facing\":\"north\"},"
                + "{\"at\":[2,64,3],\"stand\":{\"yaw\":90,\"head\":\"iron_helmet\",\"arms\":true}}]}");
        assertEquals(new DecorSpec.SignText(new Vec3(1, 64, 2), List.of("Inn", "Rooms", "", "")), items.get(0));
        DecorSpec.Contents contents = (DecorSpec.Contents) items.get(1);
        assertEquals(Map.of("bread", 3, "torch", 100), contents.items());
        assertEquals(new DecorSpec.Frame(new Vec3(1, 65, 3), "compass", "north"), items.get(2));
        assertEquals(new DecorSpec.Stand(new Vec3(2, 64, 3), 90, "iron_helmet", null, null, null, null, true, false, true), items.get(3));
        assertEquals("", ((DecorSpec.Frame) parse("{\"items\":[{\"at\":[0,0,0],\"frame\":\"\",\"facing\":\"up\"}]}").get(0)).item());
    }

    @Test
    void originShiftsPositions() {
        List<DecorSpec.Item> items = parse("{\"origin\":[100,64,-20],\"items\":[{\"at\":[1,2,3],\"text\":[\"x\"]}]}");
        assertEquals(new Vec3(101, 66, -17), items.get(0).at());
    }

    @Test
    void badItemsNameTheirIndex() {
        String sign = "{\"at\":[0,0,0],\"text\":[\"a\"]}";
        assertTrue(error("[" + sign + ",{\"at\":[0,0,0],\"text\":[\"a\"],\"frame\":\"x\",\"facing\":\"up\"}]")
                .startsWith("items[1]: needs exactly one of"));
        assertTrue(error("[{\"at\":[0,0,0]}]").startsWith("items[0]: needs exactly one of"));
        assertTrue(error("[" + sign + "," + sign + ",{\"at\":[0,0,0],\"text\":[\"1\",\"2\",\"3\",\"4\",\"5\"]}]")
                .startsWith("items[2]: text takes 1 to 4 lines"));
        assertTrue(error("[{\"at\":[0,0,0],\"frame\":\"map\"}]").startsWith("items[0]: frame needs \"facing\""));
        assertTrue(error("[{\"at\":[0,0,0],\"frame\":\"map\",\"facing\":\"sideways\"}]").startsWith("items[0]: facing must be"));
        assertTrue(error("[{\"at\":[0,0,0],\"contents\":{\"bread\":0}}]").startsWith("items[0]: count of 'bread'"));
        assertTrue(error("[{\"at\":[0,0,0],\"contents\":{\"bread\":-3}}]").startsWith("items[0]: count of 'bread'"));
        assertTrue(error("[{\"at\":[0,0,0],\"contents\":{\"bread\":2000}}]").startsWith("items[0]: count of 'bread'"));
        assertTrue(error("[" + sign + ",{\"at\":[0,0,0],\"text\":[\"a\"],\"colour\":\"red\"}]").startsWith("items[1]: unknown key \"colour\""));
        assertTrue(error("[{\"at\":[0,0,0],\"stand\":{\"helmet\":\"x\"}}]").startsWith("items[0]: unknown stand key \"helmet\""));
        assertTrue(error("[{\"at\":[0,0,0],\"text\":[\"a\"],\"facing\":\"up\"}]").startsWith("items[0]: \"facing\" only goes with frame"));
    }

    @Test
    void atMost200Items() {
        StringBuilder items = new StringBuilder("[");
        for (int i = 0; i < 201; i++) items.append(i == 0 ? "" : ",").append("{\"at\":[0,0,0],\"text\":[\"a\"]}");
        assertTrue(error(items + "]").contains("max is 200"));
    }
}
