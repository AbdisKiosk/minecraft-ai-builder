package me.abdiskiosk.minecraftaibuilder.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.abdiskiosk.minecraftaibuilder.common.BlockDiff;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffImagesTest {

    private static String[] expand(JsonObject o, String key) {
        JsonArray palette = o.getAsJsonArray("palette");
        String[] out = new String[0];
        int n = 0;
        for (JsonElement run : o.getAsJsonArray(key)) {
            int count = run.getAsJsonArray().get(1).getAsInt();
            out = Arrays.copyOf(out, n + count);
            Arrays.fill(out, n, n + count, palette.get(run.getAsJsonArray().get(0).getAsInt()).getAsString());
            n += count;
        }
        return out;
    }

    @Test
    void blocksRoundTripAndChangesMatchTheHighlight() {
        Box box = Box.of(new Vec3(10, 60, -5), new Vec3(13, 62, -3));
        String[] before = new String[(int) box.volume()];
        Arrays.fill(before, "air");
        Arrays.fill(before, 0, 12, "stone");
        before[14] = "glass";
        String[] after = before.clone();
        after[3] = "oak_stairs[facing=east,half=bottom]";   // changed
        after[14] = "air";                                    // removed
        after[20] = "oak_planks";                             // added
        after[21] = "oak_planks";
        BlockDiff diff = BlockDiff.of(box, before, after);

        JsonObject o = DiffImages.encode(box, before, after, diff);

        assertEquals("[10,60,-5,13,62,-3]", o.get("box").toString());
        assertArrayEquals(before, expand(o, "before"));
        assertArrayEquals(after, expand(o, "after"));
        // Runs are merged: the twelve stone blocks of the bottom layer are one run.
        assertEquals("[0,12]", o.getAsJsonArray("before").get(0).toString());

        Map<Integer, String> changes = new HashMap<>();
        int last = -1;
        for (JsonElement c : o.getAsJsonArray("changes")) {
            int index = c.getAsJsonArray().get(0).getAsInt();
            assertTrue(index > last, "changes are in index order");
            last = index;
            changes.put(index, c.getAsJsonArray().get(1).getAsString());
        }
        Map<Integer, String> expected = new HashMap<>();
        diff.highlight(box).forEach((i, kind) -> expected.put(i, kind.name()));
        assertEquals(expected, changes);
        assertEquals(Map.of(3, "CHANGED", 14, "REMOVED", 20, "ADDED", 21, "ADDED"), changes);
    }

    @Test
    void paletteListsEachStateOnceInFirstSeenOrder() {
        Box box = Box.of(new Vec3(0, 0, 0), new Vec3(3, 0, 0));
        String[] before = {"air", "air", "air", "air"};
        String[] after = {"stone", "oak_slab[type=bottom]", "stone", "air"};
        JsonObject o = DiffImages.encode(box, before, after, BlockDiff.of(box, before, after));
        assertEquals("[\"air\",\"stone\",\"oak_slab[type=bottom]\"]", o.get("palette").toString());
        assertEquals("[[0,4]]", o.get("before").toString());
        assertEquals("[[1,1],[2,1],[1,1],[0,1]]", o.get("after").toString());
    }
}
