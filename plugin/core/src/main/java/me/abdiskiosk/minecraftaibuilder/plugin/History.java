package me.abdiskiosk.minecraftaibuilder.plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import me.abdiskiosk.minecraftaibuilder.common.BlockDiff;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.Corrections;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.Op;
import me.abdiskiosk.minecraftaibuilder.common.RegionSnapshot;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * A git-like log of edit jobs: each entry stores the block diff of one job in {@code history/<id>.diff.gz}, and
 * {@code history/index.jsonl} lists the entries. Entries can be rendered (before, after, changes) and reverted;
 * reverting puts back the old blocks where nothing has changed them since, so it does not depend on WorldEdit's
 * undo history and survives restarts.
 *
 * <p>When the server is connected to a panel that keeps history in object storage, each entry is also archived:
 * {@code <id>.blocks.json.gz} (the 3D view's blocks) and {@code <id>.render.json.gz} (the default pictures) are made
 * from the job's own snapshot when it is recorded, uploaded with the diff, and deleted once the panel has them. The
 * entry's {@code archive} field is {@code pending} until then and {@code done} after.
 */
public final class History implements BuildService.Recorder {

    /** Changes listed by {@link #get}; the rest are only in the stats. */
    private static final int LISTED_CHANGES = 200;
    private static final int LISTED_CONFLICTS = 50;
    /** Rows per list in {@link #corrections}. */
    private static final int LISTED_CORRECTIONS = 10;

    public record Settings(boolean enabled, long maxVolume, int keep, long maxBytes, long readsPerTick, long maxRenderVolume) {
    }

    private final Plugin plugin;
    private final BuildService service;
    private final Settings settings;
    private final Path dir;
    private final Path index;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    /** Oldest first. */
    private final Map<String, JsonObject> entries = new LinkedHashMap<>();
    private long lastId;
    private volatile boolean archiving;
    private volatile Runnable onPending = () -> {};

    public History(Plugin plugin, BuildService service, Settings settings) {
        this.plugin = plugin;
        this.service = service;
        this.settings = settings;
        this.dir = service.dataDir().resolve("history");
        this.index = dir.resolve("index.jsonl");
        load();
    }

    private synchronized void load() {
        if (!Files.exists(index)) return;
        try {
            for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    JsonObject e = JsonParser.parseString(line).getAsJsonObject();
                    entries.put(e.get("id").getAsString(), e);
                } catch (JsonParseException | IllegalStateException | NullPointerException e) {
                    plugin.getLogger().warning("Skipping a broken line in " + index);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read " + index, e);
        }
    }

    // ---- Recorder ----

    @Override
    public List<Box> boxes(BuildService.Caller caller, List<Op> ops) {
        if (!settings.enabled()) return null;
        List<Box> boxes = new ArrayList<>();
        for (Op op : ops) {
            Box b = RegionSnapshot.clipY(op.bounds(), caller.minY(), caller.maxY());
            if (b != null) boxes.add(b);
        }
        boxes = RegionSnapshot.merge(boxes);
        if (boxes.isEmpty() || RegionSnapshot.volume(boxes) > settings.maxVolume()) return null;
        return boxes;
    }

    @Override
    public long readsPerTick() {
        return settings.readsPerTick();
    }

    @Override
    public String record(BuildService.JobInfo job, BlockDiff diff, List<Box> boxes, RegionSnapshot after) {
        String id;
        synchronized (this) {
            id = nextId();
        }
        // Built outside the lock: the 3D blocks need the server thread, which may be waiting on this history.
        boolean archived = archiving && diff != null && after != null && writeArchive(id, diff, after);
        synchronized (this) {
            return store(id, job, diff, boxes, archived);
        }
    }

    private String store(String id, BuildService.JobInfo job, BlockDiff diff, List<Box> boxes, boolean archiveFiles) {
        JsonObject e = new JsonObject();
        e.addProperty("id", id);
        e.addProperty("job", job.id());
        e.addProperty("time", job.started());
        e.addProperty("ms", job.ms());
        e.addProperty("tool", job.tool());
        e.addProperty("source", job.source());
        e.addProperty("caller", job.caller());
        e.addProperty("world", job.world());
        e.addProperty("status", job.status());
        if (job.error() != null) e.addProperty("error", job.error());
        if (job.revertOf() != null) e.addProperty("revertOf", job.revertOf());
        if (job.draftOf() != null) e.addProperty("draftOf", job.draftOf());
        e.addProperty("tracked", diff != null);
        if (diff != null) {
            BlockDiff.Stats stats = diff.stats();
            e.addProperty("added", stats.added());
            e.addProperty("removed", stats.removed());
            e.addProperty("changed", stats.changed());
            Box bounds = diff.bounds();
            if (bounds != null) e.add("bounds", box(bounds));
            JsonArray list = new JsonArray();
            for (Box b : boxes) list.add(box(b));
            e.add("boxes", list);
            if (!diff.isEmpty()) {
                byte[] bytes = diff.encode();
                try {
                    Files.createDirectories(dir);
                    Files.write(dir.resolve(id + ".diff.gz"), bytes);
                    e.addProperty("bytes", bytes.length);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
        } else if (job.bounds() != null) {
            e.add("bounds", box(job.bounds()));
        }
        if (archiving) e.addProperty("archive", "pending");
        if (archiveFiles) e.addProperty("archiveFiles", true);
        entries.put(id, e);
        append(e);
        prune();
        if (archiving) onPending.run();
        return id;
    }

    /** Writes the blocks and default pictures of a new entry for the archive; false when there are none. */
    private boolean writeArchive(String id, BlockDiff diff, RegionSnapshot after) {
        DiffImages.Region region = DiffImages.recorded(diff, after, settings.maxRenderVolume());
        if (region == null) return false;
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(id + ".blocks.json.gz"), gzip(gson.toJson(DiffImages.blocks(service, region, diff))));
            Files.write(dir.resolve(id + ".render.json.gz"), gzip(gson.toJson(DiffImages.render(region, diff, null, null))));
            return true;
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not prepare history " + id + " for the panel", ex);
            deleteArchiveFiles(id);
            return false;
        }
    }

    private static byte[] gzip(String text) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private void deleteArchiveFiles(String id) {
        for (String suffix : new String[]{".blocks.json.gz", ".render.json.gz"}) {
            try {
                Files.deleteIfExists(dir.resolve(id + suffix));
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "Could not delete " + id + suffix, ex);
            }
        }
    }

    // ---- archive (called from the relay's upload thread) ----

    /**
     * Turns archiving on: new entries are prepared for the panel, and {@code onPending} runs after each one. Entries
     * recorded before are archived too, with their diff only.
     */
    public void archiveTo(Runnable onPending) {
        this.onPending = onPending;
        this.archiving = true;
    }

    /** Entries the panel does not have yet, oldest first. */
    public synchronized List<String> pendingArchives() {
        List<String> out = new ArrayList<>();
        for (JsonObject e : entries.values()) {
            if (!e.has("archive") || !"done".equals(e.get("archive").getAsString())) out.add(e.get("id").getAsString());
        }
        return out;
    }

    /** The files to upload for an entry, by name (diff, blocks, render); empty when it changed nothing. */
    public synchronized Map<String, Path> archiveFiles(String id) {
        entry(id);
        Map<String, Path> files = new LinkedHashMap<>();
        for (String[] f : new String[][]{{"diff", ".diff.gz"}, {"blocks", ".blocks.json.gz"}, {"render", ".render.json.gz"}}) {
            Path file = dir.resolve(id + f[1]);
            if (Files.isRegularFile(file)) files.put(f[0], file);
        }
        return files;
    }

    /** What the panel lists and shows for an entry without asking the server: the entry, stats, types and changes. */
    public JsonObject archiveDetail(String id) {
        JsonObject e;
        synchronized (this) {
            e = entry(id).deepCopy();
        }
        e.remove("archive");
        e.remove("archiveFiles");
        if (e.get("tracked").getAsBoolean()) DiffImages.describe(e, diff(id, e), LISTED_CHANGES);
        return e;
    }

    /** The panel has the entry: the files only it needs are dropped here. The diff stays, for revert. */
    public void markArchived(String id) {
        synchronized (this) {
            JsonObject e = entries.get(id);
            if (e == null) return;
            e.addProperty("archive", "done");
            e.remove("archiveFiles");
            rewriteIndex();
        }
        deleteArchiveFiles(id);
    }

    /** Time-ordered, unique even within one millisecond. */
    private String nextId() {
        long now = Math.max(System.currentTimeMillis(), lastId + 1);
        if (!entries.isEmpty()) {
            String newest = entries.keySet().stream().reduce((a, b) -> b).orElse("h0");
            try {
                now = Math.max(now, Long.parseLong(newest.substring(1), 36) + 1);
            } catch (NumberFormatException ignored) {
            }
        }
        lastId = now;
        return "h" + Long.toString(now, 36);
    }

    private void append(JsonObject e) {
        try {
            Files.createDirectories(dir);
            Files.writeString(index, gson.toJson(e) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void prune() {
        long bytes = 0;
        for (JsonObject e : entries.values()) bytes += e.has("bytes") ? e.get("bytes").getAsLong() : 0;
        boolean removed = false;
        while (!entries.isEmpty() && (entries.size() > settings.keep() || bytes > settings.maxBytes())) {
            String oldest = entries.keySet().iterator().next();
            JsonObject e = entries.remove(oldest);
            bytes -= e.has("bytes") ? e.get("bytes").getAsLong() : 0;
            try {
                Files.deleteIfExists(dir.resolve(oldest + ".diff.gz"));
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "Could not delete history " + oldest, ex);
            }
            deleteArchiveFiles(oldest);
            removed = true;
        }
        if (removed) rewriteIndex();
    }

    private void rewriteIndex() {
        try {
            Path tmp = dir.resolve("index.jsonl.tmp");
            StringBuilder sb = new StringBuilder();
            for (JsonObject e : entries.values()) sb.append(gson.toJson(e)).append('\n');
            Files.writeString(tmp, sb, StandardCharsets.UTF_8);
            Files.move(tmp, index, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not rewrite " + index, ex);
        }
    }

    // ---- queries (called from the relay's worker threads) ----

    /** Newest first; {@code before} is an id to page from. */
    public synchronized JsonObject list(int limit, String before) {
        List<JsonObject> all = new ArrayList<>(entries.values());
        JsonArray out = new JsonArray();
        boolean started = before == null;
        for (int i = all.size() - 1; i >= 0 && out.size() < limit; i--) {
            JsonObject e = all.get(i);
            if (!started) {
                started = e.get("id").getAsString().equals(before);
                continue;
            }
            out.add(e.deepCopy());
        }
        JsonObject o = new JsonObject();
        o.add("entries", out);
        o.addProperty("total", entries.size());
        return o;
    }

    public JsonObject get(String id) {
        JsonObject e = entry(id);
        BlockDiff diff = diff(id, e);
        JsonObject o = e.deepCopy();
        DiffImages.describe(o, diff, LISTED_CHANGES);
        return o;
    }

    /** Before, after and changes pictures of an entry, as base64 PNGs, using the blocks around it as they are now. */
    public JsonObject render(String id, String viewName, String cornerName) {
        JsonObject e = entry(id);
        return DiffImages.render(service, e.get("world").getAsString(), diff(id, e), "entry " + id, viewName, cornerName,
                settings.maxRenderVolume(), settings.readsPerTick());
    }

    /** The region {@link #render} draws, as blocks for the panel's 3D view. */
    public JsonObject blocks(String id) {
        JsonObject e = entry(id);
        return DiffImages.blocks(service, e.get("world").getAsString(), diff(id, e), "entry " + id,
                settings.maxRenderVolume(), settings.readsPerTick());
    }

    /**
     * Puts back the blocks an entry changed, as a new job. Cells changed again since are conflicts and are left
     * alone unless {@code force}.
     */
    public JsonObject revert(String id, boolean force) {
        JsonObject e = entry(id);
        return revert(id, e, diff(id, e), force);
    }

    /**
     * Reverts an entry this server no longer keeps but the panel archived: the panel sends the entry and its diff.
     */
    public JsonObject revertArchived(String id, JsonObject entry, byte[] diff, boolean force) {
        if (entries.containsKey(id)) return revert(id, force);
        return revert(id, entry, BlockDiff.decode(diff), force);
    }

    private JsonObject revert(String id, JsonObject e, BlockDiff diff, boolean force) {
        String world = e.get("world").getAsString();
        List<Box> boxes = boxes(e, diff);
        JsonObject o = new JsonObject();
        if (diff.isEmpty()) {
            o.addProperty("blocks", 0);
            o.addProperty("conflicts", 0);
            return o;
        }
        RegionSnapshot now = service.readStepped(world, boxes, settings.readsPerTick());
        BlockDiff.Revert revert = diff.revert(p -> {
            String s = now.get(p);
            return s == null ? "air" : s;
        }, force);
        o.addProperty("blocks", revert.blocks().size());
        o.addProperty("conflicts", revert.conflicts().size());
        o.add("conflictList", DiffImages.conflicts(revert.conflicts(), LISTED_CONFLICTS));
        if (!revert.blocks().isEmpty()) {
            BuildService.Caller caller = service.resolve(null, world);
            BuildService.Job job = CallContext.withTool("revert",
                    () -> service.submit(caller, List.of(new Op.Blocks(revert.blocks())),
                            new BuildService.Options(BuildService.Mode.DIRECT, id, null, null)));
            o.addProperty("job", job.id());
        }
        return o;
    }

    /**
     * What people changed in the blocks an entry placed, as counts and a few coordinates, and stamps the entry with
     * {@code corrected: {cells, share, time}} ({@code cells: 0} when nothing above the noise threshold changed).
     * Cells a later entry changed are measured against that entry's after-state.
     */
    public JsonObject corrections(String id) {
        JsonObject e;
        List<JsonObject> later = new ArrayList<>();
        synchronized (this) {
            JsonObject live = entry(id);
            e = live.deepCopy();
            boolean after = false;
            for (JsonObject other : entries.values()) {
                if (after) later.add(other.deepCopy());
                else after = other == live;
            }
        }
        if (!e.get("tracked").getAsBoolean()) {
            throw new EditException("entry " + id + " has no diff (the job was larger than history.max-volume or failed early)");
        }
        BlockDiff diff = storedDiff(id, e);
        if (diff == null) throw new EditException("the diff of entry " + id + " is gone from the history folder");
        if (diff.isEmpty()) throw new EditException("entry " + id + " changed no blocks, so nothing of it can have been corrected");
        String world = e.get("world").getAsString();
        Box bounds = diff.bounds();

        List<BlockDiff> laterDiffs = new ArrayList<>();
        List<Box> unknown = new ArrayList<>();
        for (JsonObject l : later) {
            if (!world.equals(l.get("world").getAsString()) || !l.has("bounds")) continue;
            Box b = box(l.getAsJsonArray("bounds"));
            if (!overlaps(b, bounds)) continue;
            BlockDiff d = null;
            if (l.get("tracked").getAsBoolean()) {
                try {
                    d = storedDiff(l.get("id").getAsString(), l);
                } catch (RuntimeException ex) {
                    // unreadable: treated like an untracked edit below
                }
            }
            if (d == null) {
                unknown.add(b); // a later edit whose cells are unknown: its whole box is left out
                continue;
            }
            // Only the part over this entry is kept, so one later diff at a time is held in full.
            BlockDiff.Builder part = new BlockDiff.Builder();
            for (BlockDiff.Change c : d.changes()) if (inside(bounds, c.pos())) part.change(c.pos(), c.before(), c.after());
            laterDiffs.add(part.build());
        }

        RegionSnapshot now = service.readStepped(world, boxes(e, diff), settings.readsPerTick());
        Corrections.Result r = Corrections.find(diff, laterDiffs, unknown, p -> {
            String s = now.get(p);
            return s == null ? "air" : s;
        }, Corrections.Threshold.DEFAULT);

        double share = Math.round(r.share() * 1000) / 1000.0;
        JsonObject stamp = new JsonObject();
        stamp.addProperty("cells", r.cells());
        stamp.addProperty("share", share);
        stamp.addProperty("time", System.currentTimeMillis());
        synchronized (this) {
            JsonObject live = entries.get(id);
            if (live != null) { // not pruned meanwhile
                live.add("corrected", stamp);
                rewriteIndex();
            }
        }

        JsonObject o = new JsonObject();
        o.addProperty("cells", r.cells());
        o.addProperty("placed", r.placed());
        o.addProperty("share", share);
        JsonArray subs = new JsonArray();
        for (Corrections.Substitution s : r.substitutions().subList(0, Math.min(LISTED_CORRECTIONS, r.substitutions().size()))) {
            JsonObject row = new JsonObject();
            row.addProperty("from", s.from());
            row.addProperty("to", s.to());
            row.addProperty("count", s.count());
            JsonArray at = new JsonArray();
            for (Vec3 p : s.at()) {
                JsonArray xyz = new JsonArray();
                xyz.add(p.x());
                xyz.add(p.y());
                xyz.add(p.z());
                at.add(xyz);
            }
            row.add("at", at);
            subs.add(row);
        }
        o.add("substitutions", subs);
        o.add("removed", typeCounts(r.removed()));
        o.add("states", typeCounts(r.states()));
        return o;
    }

    private static JsonArray typeCounts(List<Corrections.TypeCount> counts) {
        JsonArray out = new JsonArray();
        for (Corrections.TypeCount t : counts.subList(0, Math.min(LISTED_CORRECTIONS, counts.size()))) {
            JsonObject row = new JsonObject();
            row.addProperty("type", t.type());
            row.addProperty("count", t.count());
            out.add(row);
        }
        return out;
    }

    /** The boxes an entry was recorded in, to read them again. */
    private static List<Box> boxes(JsonObject e, BlockDiff diff) {
        List<Box> boxes = new ArrayList<>();
        if (e.has("boxes")) e.getAsJsonArray("boxes").forEach(b -> boxes.add(box(b.getAsJsonArray())));
        if (boxes.isEmpty() && diff.bounds() != null) boxes.add(diff.bounds());
        return boxes;
    }

    private static boolean overlaps(Box a, Box b) {
        return a.min().x() <= b.max().x() && b.min().x() <= a.max().x() && a.min().y() <= b.max().y()
                && b.min().y() <= a.max().y() && a.min().z() <= b.max().z() && b.min().z() <= a.max().z();
    }

    private static boolean inside(Box b, Vec3 p) {
        return p.x() >= b.min().x() && p.x() <= b.max().x() && p.y() >= b.min().y() && p.y() <= b.max().y()
                && p.z() >= b.min().z() && p.z() <= b.max().z();
    }

    /** A tracked entry's diff; null when it changed blocks but its file is gone. */
    private BlockDiff storedDiff(String id, JsonObject e) {
        Path file = dir.resolve(id + ".diff.gz");
        if (!Files.exists(file)) {
            long changed = 0;
            for (String k : new String[]{"added", "removed", "changed"}) changed += e.has(k) ? e.get(k).getAsLong() : 0;
            return changed == 0 ? BlockDiff.empty() : null;
        }
        try {
            return BlockDiff.decode(Files.readAllBytes(file));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private synchronized JsonObject entry(String id) {
        JsonObject e = entries.get(id);
        if (e == null) throw new EditException("no history entry '" + id + "'");
        return e;
    }

    private BlockDiff diff(String id, JsonObject e) {
        if (!e.get("tracked").getAsBoolean()) {
            throw new EditException("entry " + id + " has no diff (the job was larger than history.max-volume or failed early)");
        }
        Path file = dir.resolve(id + ".diff.gz");
        if (!Files.exists(file)) return BlockDiff.empty();
        try {
            return BlockDiff.decode(Files.readAllBytes(file));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static JsonArray box(Box b) {
        JsonArray a = new JsonArray();
        a.add(b.min().x());
        a.add(b.min().y());
        a.add(b.min().z());
        a.add(b.max().x());
        a.add(b.max().y());
        a.add(b.max().z());
        return a;
    }

    private static Box box(JsonArray a) {
        return Box.of(new Vec3(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()),
                new Vec3(a.get(3).getAsInt(), a.get(4).getAsInt(), a.get(5).getAsInt()));
    }
}
