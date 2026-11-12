package me.abdiskiosk.minecraftaibuilder.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.abdiskiosk.minecraftaibuilder.common.BlockDiff;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import me.abdiskiosk.minecraftaibuilder.common.WorldEditBridge;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link History#corrections} against a fake world that reads off the main thread. */
class HistoryCorrectionsTest {

    private final Map<Vec3, String> world = new HashMap<>();

    private BuildService service(Path dir) {
        WorldEditBridge bridge = (WorldEditBridge) Proxy.newProxyInstance(WorldEditBridge.class.getClassLoader(),
                new Class<?>[]{WorldEditBridge.class}, (p, m, a) -> switch (m.getName()) {
                    case "name" -> "fake";
                    case "async" -> true;
                    case "read" -> read((Box) a[1]);
                    default -> null;
                });
        PluginConfig config = new PluginConfig(4000, 2_000_000L, 262_144L, 0, 30,
                List.of(), "", null, List.of(), true, "", "", null, null, null);
        return new BuildService(null, bridge, config, dir);
    }

    private String[] read(Box box) {
        String[] out = new String[(int) box.volume()];
        int i = 0;
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) out[i++] = world.getOrDefault(new Vec3(x, y, z), "air");
            }
        }
        return out;
    }

    private static History history(BuildService service) {
        return new History(null, service, new History.Settings(true, 1000, 100, 1 << 20, 4096, 1000));
    }

    private static String record(History h, BlockDiff diff, Box box) {
        return record(h, diff, box, "panel mcp");
    }

    private static String record(History h, BlockDiff diff, Box box, String source) {
        return h.record(new BuildService.JobInfo("j", "done", "done", "build", source, "Claude", "world", box,
                0, 0, 0, System.currentTimeMillis(), 1, null, null, null, null, null), diff, diff == null ? null : List.of(box), null);
    }

    /** Places {@code n} blocks of {@code placed} in a row at z, and has a person change them all to {@code now}. */
    private String corrected(History h, int z, int n, String placed, String now, String source) {
        BlockDiff.Builder d = new BlockDiff.Builder();
        for (int x = 0; x < n; x++) {
            d.change(new Vec3(x, 64, z), "air", placed);
            world.put(new Vec3(x, 64, z), now);
        }
        return record(h, d.build(), Box.of(new Vec3(0, 64, z), new Vec3(n - 1, 64, z)), source);
    }

    private static Vec3 at(int x) {
        return new Vec3(x, 64, 0);
    }

    @Test
    void summarisesWhatPeopleChangedAndStampsTheEntry() throws Exception {
        Path dir = Files.createTempDirectory("corrections");
        BuildService service = service(dir);
        History h = history(service);
        Box box = Box.of(at(0), at(5));
        BlockDiff.Builder placed = new BlockDiff.Builder();
        for (int x = 0; x < 6; x++) {
            placed.change(at(x), "air", "oak_planks");
            world.put(at(x), "oak_planks");
        }
        String id = record(h, placed.build(), box);
        // A later Claude edit put glass at x=5; that is not a correction.
        record(h, new BlockDiff.Builder().change(at(5), "oak_planks", "glass").build(), Box.of(at(5), at(5)));
        world.put(at(5), "glass");
        for (int x = 0; x < 4; x++) world.put(at(x), "spruce_planks");

        JsonObject o = h.corrections(id);
        assertEquals(4, o.get("cells").getAsInt());
        assertEquals(6, o.get("placed").getAsInt());
        assertEquals(0.667, o.get("share").getAsDouble(), 1e-9);
        JsonObject sub = o.getAsJsonArray("substitutions").get(0).getAsJsonObject();
        assertEquals("oak_planks", sub.get("from").getAsString());
        assertEquals("spruce_planks", sub.get("to").getAsString());
        assertEquals(4, sub.get("count").getAsInt());
        JsonArray first = sub.getAsJsonArray("at").get(0).getAsJsonArray();
        assertEquals(List.of(0, 64, 0), List.of(first.get(0).getAsInt(), first.get(1).getAsInt(), first.get(2).getAsInt()));
        assertEquals(0, o.getAsJsonArray("removed").size());
        assertEquals(0, o.getAsJsonArray("states").size());

        // The stamp survives a restart.
        JsonObject entry = history(service).get(id);
        assertEquals(4, entry.getAsJsonObject("corrected").get("cells").getAsInt());
        assertEquals(0.667, entry.getAsJsonObject("corrected").get("share").getAsDouble(), 1e-9);
        assertTrue(entry.getAsJsonObject("corrected").has("time"));
    }

    @Test
    void anUncorrectedEntryIsStampedWithZero() throws Exception {
        Path dir = Files.createTempDirectory("corrections");
        BuildService service = service(dir);
        History h = history(service);
        world.put(at(0), "stone");
        String id = record(h, new BlockDiff.Builder().change(at(0), "air", "stone").build(), Box.of(at(0), at(0)));
        JsonObject o = h.corrections(id);
        assertEquals(0, o.get("cells").getAsInt());
        assertEquals(0, h.get(id).getAsJsonObject("corrected").get("cells").getAsInt());
    }

    @Test
    void entriesWithoutADiffAreRefused() throws Exception {
        Path dir = Files.createTempDirectory("corrections");
        History h = history(service(dir));
        Box box = Box.of(at(0), at(0));
        String untracked = record(h, null, box);
        String empty = record(h, BlockDiff.empty(), box);
        String gone = record(h, new BlockDiff.Builder().change(at(0), "air", "stone").build(), box);
        Files.delete(dir.resolve("history").resolve(gone + ".diff.gz"));

        assertTrue(assertThrows(EditException.class, () -> h.corrections(untracked)).getMessage().contains("has no diff"));
        assertTrue(assertThrows(EditException.class, () -> h.corrections(empty)).getMessage().contains("changed no blocks"));
        assertTrue(assertThrows(EditException.class, () -> h.corrections(gone)).getMessage().contains("is gone"));
        assertTrue(assertThrows(EditException.class, () -> h.corrections("hnope")).getMessage().contains("no history entry"));
    }

    @Test
    void theStampKeepsTheTopRowsAndClaudeIsToldOnce() throws Exception {
        Path dir = Files.createTempDirectory("corrections");
        History h = history(service(dir));
        String stairs = corrected(h, 0, 6, "oak_stairs[facing=east,half=bottom]", "oak_stairs[facing=west,half=bottom]", "panel mcp");
        String floor = corrected(h, 1, 8, "oak_planks", "spruce_planks", "panel mcp");
        String script = corrected(h, 2, 8, "oak_planks", "birch_planks", "panel rest");
        String clean = corrected(h, 3, 8, "stone", "stone", "panel mcp");
        for (String id : List.of(stairs, floor, script, clean)) h.corrections(id);
        assertTrue(h.corrected(floor));
        assertTrue(!h.corrected(clean) && !h.corrected("hnope"));

        JsonObject states = h.get(stairs).getAsJsonObject("corrected").getAsJsonArray("states").get(0).getAsJsonObject();
        assertEquals("[\"facing\"]", states.get("props").toString());
        JsonObject sub = h.get(floor).getAsJsonObject("corrected").getAsJsonArray("substitutions").get(0).getAsJsonObject();
        assertEquals(2, sub.getAsJsonArray("at").size(), "two sample positions are kept");

        JsonObject context = new JsonObject();
        h.addCorrectionsTo(context);
        // Newest first; the script's entry is not Claude's, and the clean one has nothing to tell.
        assertEquals("[{\"entry\":\"" + floor + "\",\"share\":1.0,\"substitutions\":[\"oak_planks -> spruce_planks x8 at [0,64,1] [1,64,1]\"]},"
                + "{\"entry\":\"" + stairs + "\",\"share\":1.0,\"states\":[\"oak_stairs facing x6\"]}]",
                context.get("corrections").toString());

        JsonObject again = new JsonObject();
        h.addCorrectionsTo(again);
        assertTrue(!again.has("corrections"), again.toString());
        // Checked again later, and after a restart: still told.
        h.corrections(floor);
        History reloaded = history(service(dir));
        reloaded.addCorrectionsTo(again);
        assertTrue(!again.has("corrections"), again.toString());
    }

    /** The largest corrections block get_context can carry: long names, big counts, far coordinates. */
    @Test
    void whatClaudeIsToldStaysWithinTheCap() throws Exception {
        Path dir = Files.createTempDirectory("corrections");
        History h = history(service(dir));
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(record(h, new BlockDiff.Builder().change(at(0), "air", "stone").build(), Box.of(at(0), at(0))));
        }
        String longest = "waxed_weathered_cut_copper_stairs";
        int worst = 0;
        for (String id : ids) {
            JsonObject stamp = new JsonObject();
            stamp.addProperty("cells", 2_000_000);
            stamp.addProperty("share", 0.333);
            stamp.addProperty("time", System.currentTimeMillis());
            JsonArray subs = new JsonArray(), removed = new JsonArray(), states = new JsonArray();
            for (int r = 0; r < 3; r++) {
                JsonObject s = new JsonObject();
                s.addProperty("from", longest);
                s.addProperty("to", longest.replace("weathered", "oxidized"));
                s.addProperty("count", 1_999_999);
                s.add("at", com.google.gson.JsonParser.parseString("[[-29999999,-2032,-29999999],[-29999999,-2032,-29999999]]"));
                subs.add(s);
                JsonObject t = new JsonObject();
                t.addProperty("type", longest);
                t.addProperty("count", 1_999_999);
                removed.add(t.deepCopy());
                t.add("props", com.google.gson.JsonParser.parseString("[\"waterlogged\",\"facing\",\"shape\"]"));
                states.add(t);
            }
            stamp.add("substitutions", subs);
            stamp.add("removed", removed);
            stamp.add("states", states);
            worst = Math.max(worst, History.toldJson(id, stamp).toString().length());
            stamp(h, id, stamp);
        }
        JsonObject context = new JsonObject();
        h.addCorrectionsTo(context);
        int chars = context.get("corrections").toString().length();
        System.out.println("corrections in get_context, worst case: " + chars + " characters (one entry: " + worst + ")");
        assertTrue(chars <= History.TOLD_CHARS, chars + " characters");
        assertTrue(context.getAsJsonArray("corrections").size() >= 1);
        // What did not fit is told in the next session.
        JsonObject next = new JsonObject();
        h.addCorrectionsTo(next);
        assertTrue(next.has("corrections"));
    }

    /** Puts a stamp on an entry as a check would, without reading the world. */
    private static void stamp(History h, String id, JsonObject stamp) throws Exception {
        java.lang.reflect.Field f = History.class.getDeclaredField("entries");
        f.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, JsonObject> entries = (Map<String, JsonObject>) f.get(h);
        synchronized (h) {
            entries.get(id).add("corrected", stamp);
        }
    }
}
