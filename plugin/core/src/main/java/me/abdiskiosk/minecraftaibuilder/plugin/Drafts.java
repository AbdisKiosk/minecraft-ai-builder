package me.abdiskiosk.minecraftaibuilder.plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import me.abdiskiosk.minecraftaibuilder.common.BlockCheck;
import me.abdiskiosk.minecraftaibuilder.common.BlockDiff;
import me.abdiskiosk.minecraftaibuilder.common.Blueprint;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.Draft;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.Op;
import me.abdiskiosk.minecraftaibuilder.common.RegionCodec;
import me.abdiskiosk.minecraftaibuilder.common.RegionSnapshot;
import me.abdiskiosk.minecraftaibuilder.common.StateArrays;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pending change sets, one open {@link Draft} per world. With {@code review.enabled}, every edit tool goes into a
 * draft instead of the world; the panel's promote also lands here. Approving places the draft's blocks as one normal
 * (history-recorded) job; rejecting drops it. Drafts are saved in {@code drafts/} after every change.
 */
public final class Drafts {

    /**
     * @param review          Claude's edits go into drafts
     * @param maxVolume       most changed blocks one draft may hold
     * @param keep            closed drafts kept for the panel
     * @param promoteMaxVolume largest region the panel may copy out of or into this server
     */
    public record Settings(boolean review, long maxVolume, int keep, long promoteMaxVolume) {
    }

    private static final int LISTED_CHANGES = 200;
    private static final int LISTED_CONFLICTS = 50;

    private final BuildService service;
    private final Settings settings;
    private final History.Settings history;
    private final Logger logger;
    private final Path dir;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    /** Every known draft by id, oldest first. */
    private final Map<String, Draft> drafts = new LinkedHashMap<>();
    /** The open draft of each world. */
    private final Map<String, Draft> open = new HashMap<>();
    private final List<Consumer<JsonObject>> listeners = new CopyOnWriteArrayList<>();
    /** File writes, in order, off the main thread. */
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AIBuilder-Drafts");
        t.setDaemon(true);
        return t;
    });
    private long lastId;

    /** @param history for read slices and render limits */
    public Drafts(BuildService service, Settings settings, History.Settings history, Logger logger) {
        this.service = service;
        this.settings = settings;
        this.history = history;
        this.logger = logger;
        this.dir = service.dataDir() == null ? null : service.dataDir().resolve("drafts");
        load();
    }

    public boolean reviewOn() {
        return settings.review();
    }

    public long maxVolume() {
        return settings.maxVolume();
    }

    /** Hears every change to a draft, as its summary. */
    public void addListener(Consumer<JsonObject> listener) {
        listeners.add(listener);
    }

    /** The open draft's state at a position in {@code world}, or null; for dry-run edit sessions. */
    public Function<Vec3, String> overlay(String world) {
        return p -> {
            synchronized (this) {
                Draft d = open.get(world);
                return d == null ? null : d.after(p);
            }
        };
    }

    public synchronized void overlay(String world, Box box, String[] states) {
        Draft d = open.get(world);
        if (d != null) d.overlay(box, states);
    }

    /** @return the draft the entry went into; null when it changed nothing and no draft is open */
    public String add(String world, Draft.Entry entry) {
        Draft d;
        synchronized (this) {
            d = open.get(world);
            if (entry.diff().isEmpty()) return d == null ? null : d.id();
            if (d == null) {
                d = new Draft(nextId(), world, System.currentTimeMillis());
                drafts.put(d.id(), d);
                open.put(world, d);
            }
            if ((long) d.size() + entry.diff().size() > settings.maxVolume()) {
                throw new EditException("draft " + d.id() + " would hold more than " + settings.maxVolume()
                        + " changed blocks (review.max-volume); approve or reject it first");
            }
            d.add(entry);
            saveEntry(d, d.entries().size(), entry);
        }
        changed(d);
        return d.id();
    }

    /** Takes the newest {@code steps} edits out of the world's open draft. */
    public int undo(String world, int steps) {
        Draft d;
        int done = 0;
        synchronized (this) {
            d = open.get(world);
            if (d == null) return 0;
            while (done < steps && d.undoLast() != null) {
                deleteEntry(d, d.entries().size() + 1);
                done++;
            }
            saveMeta(d);
        }
        if (done > 0) changed(d);
        return done;
    }

    /** The open draft of a world, or null. */
    public synchronized Draft open(String world) {
        return open.get(world);
    }

    public synchronized List<JsonObject> openSummaries() {
        List<JsonObject> out = new ArrayList<>();
        for (Draft d : open.values()) out.add(summary(d));
        return out;
    }

    /** Open drafts first, then closed ones, newest first. */
    public synchronized JsonObject list() {
        JsonArray out = new JsonArray();
        List<Draft> all = new ArrayList<>(drafts.values());
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).open()) out.add(summary(all.get(i)));
        for (int i = all.size() - 1; i >= 0; i--) if (!all.get(i).open()) out.add(summary(all.get(i)));
        JsonObject o = new JsonObject();
        o.add("drafts", out);
        o.addProperty("review", settings.review());
        return o;
    }

    public JsonObject get(String id) {
        Draft d;
        BlockDiff diff;
        JsonObject o;
        synchronized (this) {
            d = draft(id);
            diff = d.diff();
            o = summary(d);
        }
        JsonArray entries = new JsonArray();
        for (Draft.Entry e : d.entries()) {
            JsonObject row = new JsonObject();
            row.addProperty("tool", e.tool());
            row.addProperty("source", e.source());
            row.addProperty("caller", e.caller());
            row.addProperty("time", e.time());
            if (e.label() != null) row.addProperty("label", e.label());
            BlockDiff.Stats s = e.diff().stats();
            row.addProperty("added", s.added());
            row.addProperty("removed", s.removed());
            row.addProperty("changed", s.changed());
            entries.add(row);
        }
        o.add("entries", entries);
        DiffImages.describe(o, diff, LISTED_CHANGES);
        return o;
    }

    public JsonObject render(String id, String view, String corner) {
        Draft d;
        BlockDiff diff;
        synchronized (this) {
            d = draft(id);
            diff = d.diff();
        }
        return DiffImages.render(service, d.world(), diff, "draft " + id, view, corner, history.maxRenderVolume(), history.readsPerTick());
    }

    public JsonObject blocks(String id) {
        Draft d;
        BlockDiff diff;
        synchronized (this) {
            d = draft(id);
            diff = d.diff();
        }
        return DiffImages.blocks(service, d.world(), diff, "draft " + id, history.maxRenderVolume(), history.readsPerTick());
    }

    /**
     * Places the draft: cells the world still holds as the draft found them get the draft's blocks, as one job that
     * the history records (so it can be reverted). Cells changed in the world since are conflicts, left alone
     * unless {@code force}.
     *
     * @param by who approved, for the panel
     */
    public JsonObject approve(String id, boolean force, String by) {
        Draft d;
        BlockDiff diff;
        List<Box> boxes = new ArrayList<>();
        synchronized (this) {
            d = draft(id);
            if (!d.open()) throw new EditException("draft " + id + " is already " + d.status().name().toLowerCase());
            diff = d.diff();
            for (Draft.Entry e : d.entries()) {
                Box b = e.diff().bounds();
                if (b != null) boxes.add(b);
            }
            // New edits from now on start a new draft, so nothing lands in one that is being placed.
            open.remove(d.world(), d);
        }
        JsonObject o = new JsonObject();
        try {
            BlockDiff.Revert apply = null;
            if (!diff.isEmpty()) {
                RegionSnapshot now = service.readStepped(d.world(), RegionSnapshot.merge(boxes), history.readsPerTick());
                apply = diff.apply(p -> {
                    String s = now.get(p);
                    return s == null ? "air" : s;
                }, force);
            }
            String job = null;
            if (apply != null && !apply.blocks().isEmpty()) {
                BuildService.Caller caller = service.resolve(null, d.world());
                List<Op.BlockAt> blocks = apply.blocks();
                job = CallContext.withTool("approve", () -> service.submit(caller, List.of(new Op.Blocks(blocks)),
                        new BuildService.Options(BuildService.Mode.DIRECT, null, id, null))).id();
            }
            synchronized (this) {
                d.close(Draft.Status.APPROVED, by, System.currentTimeMillis(), job);
                saveMeta(d);
                prune();
            }
            o.addProperty("blocks", apply == null ? 0 : apply.blocks().size());
            o.addProperty("conflicts", apply == null ? 0 : apply.conflicts().size());
            o.add("conflictList", DiffImages.conflicts(apply == null ? List.of() : apply.conflicts(), LISTED_CONFLICTS));
            if (job != null) o.addProperty("job", job);
        } catch (RuntimeException e) {
            synchronized (this) {
                if (d.open()) open.putIfAbsent(d.world(), d); // nothing was placed: give it back
            }
            throw e;
        }
        changed(d);
        return o;
    }

    public JsonObject reject(String id, String by) {
        Draft d;
        synchronized (this) {
            d = draft(id);
            d.close(Draft.Status.REJECTED, by, System.currentTimeMillis(), null);
            open.remove(d.world(), d);
            saveMeta(d);
            prune();
        }
        changed(d);
        return summary(d);
    }

    // ---- promote: copy a region from one server into another server's draft ----

    /** The blocks of a region as it is in the world (not the draft), for the panel to send to another server. */
    public JsonObject export(String world, Box box) {
        if (box.volume() > settings.promoteMaxVolume()) {
            throw new EditException("region is " + box.volume() + " blocks; promote is limited to " + settings.promoteMaxVolume()
                    + " (promote.max-volume)");
        }
        // With the other large reads, one at a time and with its memory reserved, so two exports (or an export beside
        // a large check) never hold their chunks and arrays at once.
        byte[] data = service.readingLarge(world, box,
                () -> StateArrays.encode(service.readStepped(world, List.of(box), history.readsPerTick()).array(0)));
        JsonObject o = new JsonObject();
        o.addProperty("world", world);
        o.add("box", DiffImages.box(box));
        o.addProperty("data", Base64.getEncoder().encodeToString(data));
        o.addProperty("bytes", data.length);
        return o;
    }

    /**
     * Puts exported blocks into this server's draft with its minimum corner at {@code origin}. Blocks this server
     * does not have are refused, or left out with {@code skipUnknown}.
     */
    public JsonObject importRegion(String world, Vec3 origin, int sx, int sy, int sz, String data, boolean air,
                                   boolean skipUnknown, String label) {
        long volume = (long) sx * sy * sz;
        if (sx <= 0 || sy <= 0 || sz <= 0 || volume > settings.promoteMaxVolume()) {
            throw new EditException("region of " + volume + " blocks is outside promote.max-volume (" + settings.promoteMaxVolume() + ")");
        }
        String[] states = StateArrays.decode(Base64.getDecoder().decode(data), volume);
        if (states.length != volume) throw new EditException("region data has " + states.length + " blocks, expected " + volume);
        Set<String> distinct = new LinkedHashSet<>();
        for (String s : states) if (!RegionCodec.isAir(s)) distinct.add(s);
        Set<String> unknown = new LinkedHashSet<>();
        for (String s : distinct) {
            Set<String> errors = new HashSet<>();
            BlockCheck.checkBlock(service.bridge().catalog(), s, errors, new HashSet<>());
            if (!errors.isEmpty()) unknown.add(s);
        }
        if (!unknown.isEmpty() && !skipUnknown) {
            // Shown by the panel's Promote and Place dialogs alike; both name their checkbox "leave out blocks ...".
            throw new EditException((unknown.size() == 1 ? "1 block state does" : unknown.size() + " block states do")
                    + " not exist on this server: " + String.join(", ", unknown.stream().limit(20).toList())
                    + (unknown.size() > 20 ? ", ..." : "") + ". Tick \"leave out blocks\" to place the rest without them.");
        }
        Blueprint bp = Blueprint.ofStates(sx, sy, sz, states, !air, unknown::contains);
        if (bp.cellCount() == 0) throw new EditException("nothing to place");
        BuildService.Caller caller = service.resolve(null, world);
        BuildService.Job job = CallContext.withTool("promote", () -> service.submit(caller, List.of(new Op.Paste(bp, origin, 0)),
                new BuildService.Options(BuildService.Mode.DRAFT, null, null, label)));
        BuildService.JobResult r = service.await(job);
        JsonObject o = new JsonObject();
        o.addProperty("job", r.job());
        o.addProperty("status", r.status());
        if (r.draft() != null) o.addProperty("draft", r.draft());
        if (r.error() != null) o.addProperty("error", r.error());
        JsonArray skipped = new JsonArray();
        unknown.forEach(skipped::add);
        o.add("skipped", skipped);
        return o;
    }

    // ---- internals ----

    private Draft draft(String id) {
        Draft d = drafts.get(id);
        if (d == null) throw new EditException("no draft '" + id + "'");
        return d;
    }

    private String nextId() {
        long now = Math.max(System.currentTimeMillis(), lastId + 1);
        lastId = now;
        return "d" + Long.toString(now, 36);
    }

    private JsonObject summary(Draft d) {
        JsonObject o = new JsonObject();
        o.addProperty("id", d.id());
        o.addProperty("world", d.world());
        o.addProperty("status", d.status().name().toLowerCase());
        o.addProperty("created", d.created());
        o.addProperty("edits", d.entries().size());
        BlockDiff diff = d.diff();
        BlockDiff.Stats s = diff.stats();
        o.addProperty("added", s.added());
        o.addProperty("removed", s.removed());
        o.addProperty("changed", s.changed());
        if (diff.bounds() != null) o.add("bounds", DiffImages.box(diff.bounds()));
        if (d.closedBy() != null) {
            o.addProperty("closedBy", d.closedBy());
            o.addProperty("closedAt", d.closedAt());
        }
        if (d.job() != null) o.addProperty("job", d.job());
        return o;
    }

    private void changed(Draft d) {
        if (listeners.isEmpty()) return;
        JsonObject s;
        synchronized (this) {
            s = summary(d);
        }
        for (Consumer<JsonObject> l : listeners) {
            try {
                l.accept(s);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "draft listener failed", e);
            }
        }
    }

    /** Drops the oldest closed drafts beyond {@code keep}. */
    private void prune() {
        List<Draft> closed = drafts.values().stream().filter(x -> !x.open()).toList();
        for (int i = 0; i < closed.size() - settings.keep(); i++) {
            Draft d = closed.get(i);
            drafts.remove(d.id());
            int entries = d.entries().size();
            String id = d.id();
            io.submit(() -> {
                try {
                    Files.deleteIfExists(dir.resolve(id + ".json"));
                    for (int n = 1; n <= entries; n++) Files.deleteIfExists(dir.resolve(id + "." + n + ".diff.gz"));
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Could not delete draft " + id, e);
                }
            });
        }
    }

    private void saveEntry(Draft d, int n, Draft.Entry entry) {
        if (dir == null) return;
        byte[] bytes = entry.diff().encode();
        String id = d.id();
        io.submit(() -> {
            try {
                Files.createDirectories(dir);
                Files.write(dir.resolve(id + "." + n + ".diff.gz"), bytes);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not save draft " + id, e);
            }
        });
        saveMeta(d);
    }

    private void deleteEntry(Draft d, int n) {
        if (dir == null) return;
        String id = d.id();
        io.submit(() -> {
            try {
                Files.deleteIfExists(dir.resolve(id + "." + n + ".diff.gz"));
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not update draft " + id, e);
            }
        });
    }

    private void saveMeta(Draft d) {
        if (dir == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("id", d.id());
        o.addProperty("world", d.world());
        o.addProperty("created", d.created());
        o.addProperty("status", d.status().name());
        if (d.closedBy() != null) o.addProperty("closedBy", d.closedBy());
        if (d.closedAt() != 0) o.addProperty("closedAt", d.closedAt());
        if (d.job() != null) o.addProperty("job", d.job());
        JsonArray entries = new JsonArray();
        for (Draft.Entry e : d.entries()) {
            JsonObject row = new JsonObject();
            row.addProperty("tool", e.tool());
            row.addProperty("source", e.source());
            row.addProperty("caller", e.caller());
            row.addProperty("time", e.time());
            if (e.label() != null) row.addProperty("label", e.label());
            entries.add(row);
        }
        o.add("entries", entries);
        String json = gson.toJson(o);
        String id = d.id();
        io.submit(() -> {
            try {
                Files.createDirectories(dir);
                Path tmp = dir.resolve(id + ".json.tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, dir.resolve(id + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not save draft " + id, e);
            }
        });
    }

    private synchronized void load() {
        if (dir == null || !Files.isDirectory(dir)) return;
        List<Draft> loaded = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                try {
                    loaded.add(read(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject()));
                } catch (IOException | JsonParseException | IllegalStateException | NullPointerException | EditException e) {
                    logger.warning("Skipping draft " + file.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not read " + dir, e);
        }
        loaded.sort((a, b) -> Long.compare(a.created(), b.created()));
        for (Draft d : loaded) {
            drafts.put(d.id(), d);
            if (d.open()) {
                Draft other = open.putIfAbsent(d.world(), d);
                if (other != null) logger.warning("Two open drafts for world " + d.world() + "; using " + other.id());
            }
        }
        if (!open.isEmpty()) logger.info(open.size() + " draft(s) waiting for approval; see the panel or /aibuilder draft");
    }

    private Draft read(JsonObject o) throws IOException {
        Draft d = new Draft(o.get("id").getAsString(), o.get("world").getAsString(), o.get("created").getAsLong());
        lastId = Math.max(lastId, d.created());
        JsonArray entries = o.getAsJsonArray("entries");
        for (int i = 0; i < entries.size(); i++) {
            JsonObject e = entries.get(i).getAsJsonObject();
            BlockDiff diff = BlockDiff.decode(Files.readAllBytes(dir.resolve(d.id() + "." + (i + 1) + ".diff.gz")));
            d.add(new Draft.Entry(str(e, "tool"), str(e, "source"), str(e, "caller"), e.get("time").getAsLong(), diff, str(e, "label")));
        }
        Draft.Status status = Draft.Status.valueOf(o.get("status").getAsString());
        if (status != Draft.Status.OPEN) {
            d.close(status, str(o, "closedBy"), o.has("closedAt") ? o.get("closedAt").getAsLong() : 0, str(o, "job"));
        }
        return d;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    /** Waits for pending file writes; for shutdown and tests. */
    public void flush() {
        try {
            io.submit(() -> { }).get();
        } catch (Exception ignored) {
            // shutting down
        }
    }

    public void close() {
        flush();
        io.shutdown();
    }
}
