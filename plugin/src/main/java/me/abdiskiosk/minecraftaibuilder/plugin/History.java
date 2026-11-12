package me.abdiskiosk.minecraftaibuilder.plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import me.abdiskiosk.minecraftaibuilder.common.BlockDiff;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.IsoRenderer;
import me.abdiskiosk.minecraftaibuilder.common.Op;
import me.abdiskiosk.minecraftaibuilder.common.RegionCodec;
import me.abdiskiosk.minecraftaibuilder.common.RegionSnapshot;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/**
 * A git-like log of edit jobs: each entry stores the block diff of one job in {@code history/<id>.diff.gz}, and
 * {@code history/index.jsonl} lists the entries. Entries can be rendered (before, after, changes) and reverted;
 * reverting puts back the old blocks where nothing has changed them since, so it does not depend on WorldEdit's
 * undo history and survives restarts.
 */
public final class History implements BuildService.Recorder {

    /** Changes listed by {@link #get}; the rest are only in the stats. */
    private static final int LISTED_CHANGES = 200;
    private static final int LISTED_CONFLICTS = 50;

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
    public synchronized String record(BuildService.JobInfo job, BlockDiff diff, List<Box> boxes) {
        String id = nextId();
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
        entries.put(id, e);
        append(e);
        prune();
        return id;
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
            removed = true;
        }
        if (!removed) return;
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
        BlockDiff diff = diff(id, e);
        String world = e.get("world").getAsString();
        List<Box> boxes = new ArrayList<>();
        if (e.has("boxes")) e.getAsJsonArray("boxes").forEach(b -> boxes.add(box(b.getAsJsonArray())));
        if (boxes.isEmpty() && diff.bounds() != null) boxes.add(diff.bounds());
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
