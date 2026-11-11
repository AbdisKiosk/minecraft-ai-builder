package me.abdiskiosk.minecraftaibuilder.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.abdiskiosk.minecraftaibuilder.common.BlockDiff;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.IsoRenderer;
import me.abdiskiosk.minecraftaibuilder.common.RegionCodec;
import me.abdiskiosk.minecraftaibuilder.common.RegionSnapshot;

import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** Before, after and changes pictures of a diff, drawn on the world around it as it is now. For the panel. */
final class DiffImages {

    private DiffImages() {
    }

    /**
     * @param what      names the diff in errors, e.g. "entry h1" or "draft d1"
     * @param maxVolume largest region to read and draw
     */
    static JsonObject render(BuildService service, String world, BlockDiff diff, String what, String viewName,
                             String cornerName, long maxVolume, long perTick) {
        if (diff.isEmpty()) throw new EditException(what + " changes no blocks");
        Box bounds = diff.bounds();
        BuildService.Caller caller = service.resolve(null, world);
        // A little of the surroundings, for context.
        Box box = RegionSnapshot.clipY(Box.of(bounds.min().add(-2, -1, -2), bounds.max().add(2, 1, 2)), caller.minY(), caller.maxY());
        if (box == null || box.volume() > maxVolume) box = bounds;
        if (box.volume() > maxVolume) {
            throw new EditException(what + " spans " + box.volume() + " blocks; renders are limited to " + maxVolume
                    + " (history.max-render-volume)");
        }
        String[] now = service.readStepped(world, List.of(box), perTick).array(0);
        String[] before = diff.overlay(box, now, false);
        String[] after = diff.overlay(box, now, true);
        // Removed blocks are drawn where they were, tinted red.
        String[] marked = after.clone();
        for (BlockDiff.Change c : diff.changes()) {
            if (c.kind() == BlockDiff.Kind.REMOVED && box.contains(Box.of(c.pos(), c.pos()))) {
                marked[RegionCodec.index(box, c.pos().x(), c.pos().y(), c.pos().z())] = c.before();
            }
        }
        IsoRenderer.View view = viewName == null ? IsoRenderer.View.ISO : IsoRenderer.View.valueOf(viewName.toUpperCase(Locale.ROOT));
        IsoRenderer.Corner corner = cornerName == null ? IsoRenderer.Corner.SE : IsoRenderer.Corner.valueOf(cornerName.toUpperCase(Locale.ROOT));
        IsoRenderer.Size size = IsoRenderer.Size.LARGE;
        JsonObject o = new JsonObject();
        o.add("box", box(box));
        o.addProperty("before", png(IsoRenderer.render(box, before, view, corner, size).png()));
        o.addProperty("after", png(IsoRenderer.render(box, after, view, corner, size).png()));
        o.addProperty("changes", png(IsoRenderer.render(box, marked, view, corner, size, diff.highlight(box)).png()));
        return o;
    }

    /** Stats, block types and the first {@code listed} changes of a diff, for the panel's detail pages. */
    static void describe(JsonObject o, BlockDiff diff, int listed) {
        BlockDiff.Stats stats = diff.stats();
        o.addProperty("added", stats.added());
        o.addProperty("removed", stats.removed());
        o.addProperty("changed", stats.changed());
        Box bounds = diff.bounds();
        if (bounds != null) o.add("bounds", box(bounds));
        JsonArray types = new JsonArray();
        for (BlockDiff.TypeCount t : stats.types()) {
            JsonObject row = new JsonObject();
            row.addProperty("type", t.type());
            row.addProperty("added", t.added());
            row.addProperty("removed", t.removed());
            types.add(row);
        }
        o.add("types", types);
        JsonArray changes = new JsonArray();
        for (BlockDiff.Change c : diff.changes().subList(0, Math.min(listed, diff.size()))) changes.add(change(c));
        o.add("changes", changes);
        o.addProperty("listed", changes.size());
    }

    static JsonObject change(BlockDiff.Change c) {
        JsonObject row = new JsonObject();
        JsonArray pos = new JsonArray();
        pos.add(c.pos().x());
        pos.add(c.pos().y());
        pos.add(c.pos().z());
        row.add("pos", pos);
        row.addProperty("before", c.before());
        row.addProperty("after", c.after());
        return row;
    }

    /** Conflicts from {@link BlockDiff#apply} or {@link BlockDiff#revert}, as expected/now rows. */
    static JsonArray conflicts(List<BlockDiff.Change> conflicts, int max) {
        JsonArray out = new JsonArray();
        for (BlockDiff.Change c : conflicts.subList(0, Math.min(max, conflicts.size()))) {
            JsonObject row = change(c);
            row.add("expected", row.remove("before"));
            row.add("now", row.remove("after"));
            out.add(row);
        }
        return out;
    }

    static JsonArray box(Box b) {
        JsonArray a = new JsonArray();
        a.add(b.min().x());
        a.add(b.min().y());
        a.add(b.min().z());
        a.add(b.max().x());
        a.add(b.max().y());
        a.add(b.max().z());
        return a;
    }

    private static String png(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
