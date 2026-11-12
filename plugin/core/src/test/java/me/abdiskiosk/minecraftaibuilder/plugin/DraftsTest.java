package me.abdiskiosk.minecraftaibuilder.plugin;

import com.google.gson.JsonObject;
import me.abdiskiosk.minecraftaibuilder.common.BlockDiff;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.Draft;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import me.abdiskiosk.minecraftaibuilder.common.WorldEditBridge;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftsTest {

    private static final Vec3 A = new Vec3(0, 64, 0), B = new Vec3(1, 64, 0);

    private static Drafts drafts(Path dir, long max) {
        WorldEditBridge bridge = (WorldEditBridge) Proxy.newProxyInstance(WorldEditBridge.class.getClassLoader(),
                new Class<?>[]{WorldEditBridge.class}, (p, m, a) -> m.getName().equals("name") ? "fake" : null);
        BuildService service = new BuildService(null, bridge, null, dir);
        return new Drafts(service, new Drafts.Settings(true, max, 2, 1000), new History.Settings(true, 1000, 10, 1 << 20, 4096, 1000),
                Logger.getAnonymousLogger());
    }

    private static Draft.Entry entry(Vec3 pos, String before, String after) {
        return new Draft.Entry("fill", "test", "Alex", 1, new BlockDiff.Builder().change(pos, before, after).build(), null);
    }

    @Test
    void editsCollectInOneOpenDraftPerWorld() throws Exception {
        Drafts d = drafts(Files.createTempDirectory("drafts"), 100);
        List<JsonObject> updates = new ArrayList<>();
        d.addListener(updates::add);
        String id = d.add("world", entry(A, "air", "stone"));
        assertEquals(id, d.add("world", entry(B, "dirt", "glass")));
        String other = d.add("nether", entry(A, "netherrack", "air"));
        assertFalse(id.equals(other));
        assertEquals(3, updates.size());
        assertEquals(2, d.open("world").size());

        String[] states = {"air", "dirt"};
        d.overlay("world", Box.of(A, B), states);
        assertArrayEquals(new String[]{"stone", "glass"}, states);
        assertEquals("stone", d.overlay("world").apply(A));
        assertNull(d.overlay("world").apply(new Vec3(9, 9, 9)));
    }

    @Test
    void emptyEditsOpenNoDraft() throws Exception {
        Drafts d = drafts(Files.createTempDirectory("drafts"), 100);
        assertNull(d.add("world", new Draft.Entry("fill", "t", "Alex", 1, BlockDiff.empty(), null)));
        assertNull(d.open("world"));
    }

    @Test
    void undoTakesTheNewestEditOut() throws Exception {
        Drafts d = drafts(Files.createTempDirectory("drafts"), 100);
        d.add("world", entry(A, "air", "stone"));
        d.add("world", entry(A, "stone", "gold_block"));
        assertEquals(1, d.undo("world", 1));
        assertEquals("stone", d.open("world").after(A));
        assertEquals(1, d.undo("world", 5));
        assertEquals(0, d.undo("nether", 1));
    }

    @Test
    void draftsHaveALimit() throws Exception {
        Drafts d = drafts(Files.createTempDirectory("drafts"), 1);
        d.add("world", entry(A, "air", "stone"));
        assertThrows(EditException.class, () -> d.add("world", entry(B, "air", "stone")));
    }

    @Test
    void draftsSurviveARestart() throws Exception {
        Path dir = Files.createTempDirectory("drafts");
        Drafts d = drafts(dir, 100);
        String id = d.add("world", entry(A, "air", "stone"));
        d.add("world", entry(B, "dirt", "glass"));
        d.add("world", entry(B, "glass", "glowstone"));
        d.undo("world", 1);
        String rejected = d.add("nether", entry(A, "netherrack", "air"));
        d.reject(rejected, "admin");
        d.flush();

        Drafts back = drafts(dir, 100);
        Draft open = back.open("world");
        assertEquals(id, open.id());
        assertEquals(2, open.entries().size());
        assertEquals("glass", open.after(B));
        assertNull(back.open("nether"));
        JsonObject list = back.list();
        assertEquals(2, list.getAsJsonArray("drafts").size());
        assertEquals("rejected", back.get(rejected).get("status").getAsString());
        assertEquals("admin", back.get(rejected).get("closedBy").getAsString());
    }

    @Test
    void rejectedDraftsAreClosedAndNewEditsStartAFreshOne() throws Exception {
        Drafts d = drafts(Files.createTempDirectory("drafts"), 100);
        String id = d.add("world", entry(A, "air", "stone"));
        d.reject(id, "admin");
        assertNull(d.open("world"));
        assertThrows(EditException.class, () -> d.reject(id, "admin"));
        assertThrows(EditException.class, () -> d.approve(id, false, "admin"));
        String next = d.add("world", entry(A, "air", "stone"));
        assertFalse(id.equals(next));
        JsonObject detail = d.get(next);
        assertEquals(1, detail.get("added").getAsInt());
        assertEquals(1, detail.getAsJsonArray("entries").size());
        assertTrue(detail.getAsJsonArray("changes").size() > 0);
    }
}
