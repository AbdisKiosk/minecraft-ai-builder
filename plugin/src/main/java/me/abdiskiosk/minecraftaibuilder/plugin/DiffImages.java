package me.abdiskiosk.minecraftaibuilder.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.abdiskiosk.minecraftaibuilder.common.BlockDiff;
import me.abdiskiosk.minecraftaibuilder.common.BlockState;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.IsoRenderer;
import me.abdiskiosk.minecraftaibuilder.common.RegionCodec;
import me.abdiskiosk.minecraftaibuilder.common.RegionSnapshot;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/** Before, after and changes of a diff, as pictures or as blocks, on the world around it as it is now. For the panel. */
final class DiffImages {

    private DiffImages() {
    }

    /** The box a diff is shown in, with the world's blocks in it before and after the diff. */
    private record Region(Box box, String[] before, String[] after) {
    }

    /**
     * @param what      names the diff in errors, e.g. "entry h1" or "draft d1"
     * @param maxVolume largest region to read
     */
    private static Region region(BuildService service, String world, BlockDiff diff, String what, long maxVolume, long perTick) {
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
        return new Region(box, diff.overlay(box, now, false), diff.overlay(box, now, true));
    }

    /** Before, after and changes pictures as base64 PNGs. See {@link #region} for the parameters. */
    static JsonObject render(BuildService service, String world, BlockDiff diff, String what, String viewName,
                             String cornerName, long maxVolume, long perTick) {
        Region r = region(service, world, diff, what, maxVolume, perTick);
        Box box = r.box();
        String[] before = r.before();
        String[] after = r.after();
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

    /** The same region as {@link #render}, as blocks for the panel's 3D view. */
    static JsonObject blocks(BuildService service, String world, BlockDiff diff, String what, long maxVolume, long perTick) {
        Region r = region(service, world, diff, what, maxVolume, perTick);
        JsonObject out = encode(r.box(), r.before(), r.after(), diff);
        // Reads/history omit default properties; model variants require all of them. Resolve only the palette,
        // on the server thread, without expanding every cell or changing the compact labels used for inspection.
        out.add("models", service.sync(() -> modelStates(out.getAsJsonArray("palette"), service.bridge()::defaultState)));
        return out;
    }

    static JsonArray modelStates(JsonArray palette, UnaryOperator<String> defaults) {
        Map<String, BlockState> byType = new LinkedHashMap<>();
        JsonArray models = new JsonArray();
        for (var entry : palette) {
            BlockState state = BlockState.parse(entry.getAsString());
            BlockState base = byType.computeIfAbsent(state.type(), type -> BlockState.parse(defaults.apply(type)));
            Map<String, String> properties = new TreeMap<>(base.props());
            properties.putAll(state.props());
            models.add(new BlockState(state.type(), properties).toString());
        }
        return models;
    }

    /**
     * {@code palette} lists each distinct state, {@code before} and {@code after} are run-length lists of
     * {@code [paletteIndex, count]} in {@link RegionCodec#index} order (the order of a Sponge schematic's block data),
     * and {@code changes} lists {@code [index, kind]} in index order.
     */
    static JsonObject encode(Box box, String[] before, String[] after, BlockDiff diff) {
        Map<String, Integer> palette = new LinkedHashMap<>();
        JsonObject o = new JsonObject();
        o.add("box", box(box));
        JsonArray beforeRuns = runs(before, palette);
        JsonArray afterRuns = runs(after, palette);
        JsonArray entries = new JsonArray();
        for (String state : palette.keySet()) entries.add(state);
        o.add("palette", entries);
        o.add("before", beforeRuns);
        o.add("after", afterRuns);
        JsonArray changes = new JsonArray();
        for (Map.Entry<Integer, BlockDiff.Kind> c : new TreeMap<>(diff.highlight(box)).entrySet()) {
            JsonArray one = new JsonArray();
            one.add(c.getKey());
            one.add(c.getValue().name());
            changes.add(one);
        }
        o.add("changes", changes);
        return o;
    }

    private static JsonArray runs(String[] states, Map<String, Integer> palette) {
        JsonArray out = new JsonArray();
        int i = 0;
        while (i < states.length) {
            int start = i;
            String state = states[i];
            while (i < states.length && states[i].equals(state)) i++;
            Integer index = palette.get(state);
            if (index == null) {
                index = palette.size();
                palette.put(state, index);
            }
            JsonArray run = new JsonArray();
            run.add(index);
            run.add(i - start);
            out.add(run);
        }
        return out;
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
