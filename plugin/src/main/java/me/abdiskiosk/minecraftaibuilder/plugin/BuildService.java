package me.abdiskiosk.minecraftaibuilder.plugin;

import me.abdiskiosk.minecraftaibuilder.common.BlockCheck;
import me.abdiskiosk.minecraftaibuilder.common.BlockDiff;
import me.abdiskiosk.minecraftaibuilder.common.Draft;
import me.abdiskiosk.minecraftaibuilder.common.BlockState;
import me.abdiskiosk.minecraftaibuilder.common.BlockTraits;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.EditHandle;
import me.abdiskiosk.minecraftaibuilder.common.Lighting;
import me.abdiskiosk.minecraftaibuilder.common.Op;
import me.abdiskiosk.minecraftaibuilder.common.PreviewHandle;
import me.abdiskiosk.minecraftaibuilder.common.RegionSnapshot;
import me.abdiskiosk.minecraftaibuilder.common.RelayProtocol;
import me.abdiskiosk.minecraftaibuilder.common.SelectionInfo;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import me.abdiskiosk.minecraftaibuilder.common.WorldEditBridge;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Runs bridge calls on the right thread, enforces limits and tracks edit jobs. */
public final class BuildService {

    private static final int MAX_JOBS = 200;
    /** Blocks read per main-thread visit, so a large read costs each tick only a few milliseconds. */
    private static final long READ_SLAB = 100_000;
    private static final int MAX_TREE_WARNINGS = 5;
    private static final int LIGHT_HEADROOM = 24;

    private final Plugin plugin;
    private final WorldEditBridge bridge;
    private final PluginConfig config;
    private final Path dataDir;
    private final Markers markers;
    private final MemoryGuard memory;
    /** Large reads run one at a time, so the arrays of several checks never sit in the heap together. */
    private final Semaphore largeReads = new Semaphore(1, true);
    /** Set while a thread is inside {@link #reading}, where the memory for its reads is already set aside. */
    private static final ThreadLocal<Boolean> RESERVED = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final AtomicInteger nextJob = new AtomicInteger(1);
    private final Map<String, Job> jobs = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Job> eldest) {
            return size() > MAX_JOBS;
        }
    };

    /** @param dataDir the plugin's data folder; saved blueprints live in its {@code blueprints} folder */
    public BuildService(Plugin plugin, WorldEditBridge bridge, PluginConfig config, Path dataDir) {
        this.plugin = plugin;
        this.bridge = bridge;
        this.config = config;
        this.dataDir = dataDir;
        this.markers = new Markers(dataDir);
        this.memory = new MemoryGuard(config == null ? 0 : config.minFreeMemoryMb() * 1_048_576L);
    }

    public WorldEditBridge bridge() {
        return bridge;
    }

    public PluginConfig config() {
        return config;
    }

    public Path dataDir() {
        return dataDir;
    }

    /** Markers and notes set with /aibuilder mark and /aibuilder note. */
    public Markers markers() {
        return markers;
    }

    /**
     * The player (or console) a call acts as. {@code feet} and {@code yaw} are null/0 for the console.
     *
     * @param online names of all online players, for messages when the caller is ambiguous
     * @param minY   lowest and highest block y the targeted world allows
     */
    public record Caller(UUID id, String name, String world, Vec3 feet, float yaw, List<String> online, int minY, int maxY) {
        public boolean isPlayer() {
            return id != null;
        }

        /** Throws unless a player is resolved, and says how to fix it. */
        public void requirePlayer(String what) {
            if (isPlayer()) return;
            throw new EditException(what + " needs a player. " + (online.isEmpty() ? "No player is online."
                    : online.size() + " players are online (" + String.join(", ", online) + "); pass \"player\" to say who acts."));
        }
    }

    /** @param draft the draft an unplaced (review) edit went into, or null */
    public record JobResult(String job, String status, long changed, long ms, String error, List<String> warnings, String draft) {
    }

    public static final class Job {
        final String id;
        final List<String> warnings;
        final long started = System.currentTimeMillis();
        final CompletableFuture<JobResult> future = new CompletableFuture<>();
        final String tool = CallContext.tool();
        final String source = CallContext.source();
        final Caller caller;
        final Box bounds;
        final long total;
        volatile long changed;
        /** Volume of the pieces applied so far, out of {@link #total}. */
        volatile long processed;
        volatile String phase = "build";
        volatile String historyId;
        /** Set for jobs started by reverting a history entry. */
        volatile String revertOf;
        /** Set for jobs that place an approved draft. */
        volatile String draftOf;
        /** Dry run: the result goes into a draft instead of the world. */
        volatile boolean preview;
        /** The draft a preview job's changes went into. */
        volatile String draft;
        volatile String label;
        /** Heap set aside for the edit while it runs. */
        volatile MemoryGuard.Reservation memory;

        Job(String id, List<String> warnings, Caller caller, Box bounds, long total) {
            this.id = id;
            this.warnings = new CopyOnWriteArrayList<>(warnings);
            this.caller = caller;
            this.bounds = bounds;
            this.total = total;
        }

        public String id() {
            return id;
        }

        JobResult snapshot() {
            JobResult done = future.getNow(null);
            return done != null ? done : new JobResult(id, "running", changed, System.currentTimeMillis() - started, null, warnings, draft);
        }

        public JobInfo info() {
            JobResult r = snapshot();
            return new JobInfo(id, r.status(), phase, tool, source, caller.name(), caller.world(), bounds, total, processed,
                    r.changed(), started, r.ms(), r.error(), historyId, revertOf, draft, draftOf);
        }
    }

    /** What the panel shows about a job; {@code processed} out of {@code total} is the progress. */
    public record JobInfo(String id, String status, String phase, String tool, String source, String caller, String world,
                          Box bounds, long total, long processed, long changed, long started, long ms, String error,
                          String history, String revertOf, String draft, String draftOf) {
    }

    /** Hears about every job; called from the main thread or a FastAsyncWorldEdit thread, so it must be quick. */
    public interface JobListener {
        void onJob(String event, JobInfo job);
    }

    /** Snapshots edits for the history; see {@link History}. */
    public interface Recorder {
        /** The boxes to read before and after the job, or null to not track it (too large, or turned off). */
        List<Box> boxes(Caller caller, List<Op> ops);

        /** @param diff null when the job was not tracked */
        String record(JobInfo job, BlockDiff diff, List<Box> boxes);

        long readsPerTick();
    }

    private final List<JobListener> listeners = new CopyOnWriteArrayList<>();
    private volatile Recorder recorder;

    public void addListener(JobListener listener) {
        listeners.add(listener);
    }

    public void setRecorder(Recorder recorder) {
        this.recorder = recorder;
    }

    private void fire(String event, Job job) {
        if (listeners.isEmpty()) return;
        JobInfo info = job.info();
        for (JobListener l : listeners) {
            try {
                l.onJob(event, info);
            } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "job listener failed", e);
            }
        }
    }

    /** Jobs that have not finished yet. */
    public List<JobInfo> running() {
        synchronized (jobs) {
            return jobs.values().stream().filter(j -> !j.future.isDone()).map(Job::info).toList();
        }
    }

    /**
     * The ops of a call and the pieces they were split into, so a failure can name the op it came from.
     *
     * @param lights light ops, which run after every piece because they read what the pieces built
     */
    private record Plan(List<Op> ops, List<Op> pieces, int[] owner, List<Op.Light> lights) {
        RuntimeException explain(Throwable failure, int piece) {
            if (failure instanceof OutOfMemoryError) {
                return new EditException("the server ran out of memory during this edit and it was stopped; what was"
                        + " placed can be undone. Use smaller edits, or give the server more memory.");
            }
            RuntimeException e = failure instanceof RuntimeException re ? re : new EditException(String.valueOf(failure), failure);
            if (ops.size() < 2 || piece < 0) return e;
            Op op = ops.get(owner[piece]);
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return new EditException("ops[" + owner[piece] + "] (" + op.kind() + "): " + message, e);
        }
    }

    /** Server version without the build suffix, e.g. "1.8.8". */
    public static String minecraftVersion() {
        return Bukkit.getBukkitVersion().split("-")[0];
    }

    /** Runs {@code task} on the main thread and waits for it. */
    public <T> T sync(Callable<T> task) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return task.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new EditException(e.getMessage(), e);
            }
        }
        Future<T> future = Bukkit.getScheduler().callSyncMethod(plugin, task);
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new EditException(String.valueOf(cause), cause);
        } catch (TimeoutException e) {
            future.cancel(false); // otherwise a retry would run the same task a second time
            throw new EditException("server main thread did not respond in 30s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EditException("interrupted");
        }
    }

    /**
     * Resolves who a call acts as. Without a player name, the only online player is used;
     * with zero or several players online the console acts (calls that need a player say so).
     */
    public Caller resolve(String playerName, String worldName) {
        return sync(() -> {
            Player player = null;
            if (playerName != null && !playerName.isEmpty()) {
                player = Bukkit.getPlayerExact(playerName);
                if (player == null) throw new EditException("player '" + playerName + "' is not online");
            } else {
                Collection<? extends Player> online = Bukkit.getOnlinePlayers();
                if (online.size() == 1) player = online.iterator().next();
            }
            String world = worldName != null && !worldName.isEmpty() ? worldName
                    : player != null ? player.getWorld().getName() : Bukkit.getWorlds().get(0).getName();
            World target = Bukkit.getWorld(world);
            if (target == null) throw new EditException("unknown world '" + world + "'");
            List<String> online = Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().toList();
            int minY = minHeight(target);
            int maxY = target.getMaxHeight() - 1;
            if (player == null) return new Caller(null, "console", world, null, 0, online, minY, maxY);
            Location l = player.getLocation();
            return new Caller(player.getUniqueId(), player.getName(), world,
                    new Vec3(l.getBlockX(), l.getBlockY(), l.getBlockZ()), l.getYaw(), online, minY, maxY);
        });
    }

    /** World#getMinHeight exists from 1.17; older worlds start at 0. */
    private static int minHeight(World world) {
        try {
            return (int) World.class.getMethod("getMinHeight").invoke(world);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    public Optional<SelectionInfo> selection(Caller caller) {
        caller.requirePlayer("selections");
        return sync(() -> bridge.selection(caller.id()));
    }

    public String[] read(String world, Box box) {
        checkWorld(world);
        if (box.volume() > config.maxReadVolume()) {
            throw new EditException("region is " + box.volume() + " blocks; max read is " + config.maxReadVolume()
                    + ". Use a smaller region.");
        }
        if (box.volume() > READ_SLAB && !RESERVED.get() && !Bukkit.isPrimaryThread()) {
            memory.check(readCost(world, List.of(box)), "read " + box.volume() + " blocks", readAdvice(box));
        }
        return readNow(world, box);
    }

    /** {@link #read} for a caller that has its memory set aside already. */
    private String[] readNow(String world, Box box) {
        String[] states = readWorld(world, box);
        // With review on, the caller sees the world as the open draft would leave it.
        if (reviewOn()) drafts.overlay(world, box, states);
        return states;
    }

    /**
     * Runs work that reads {@code box} and builds something from it (a check, a render, a summary). A large region
     * waits for its turn and for room in the heap; a small one runs at once.
     */
    public <T> T reading(String world, Box box, Supplier<T> work) {
        if (box.volume() <= READ_SLAB || box.volume() > config.maxReadVolume()) return work.get();
        try {
            if (!largeReads.tryAcquire(60, TimeUnit.SECONDS)) {
                throw new EditException("the server is busy with other large reads; try again in a moment");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EditException("interrupted");
        }
        try (MemoryGuard.Reservation ignored = memory.reserve(readCost(world, List.of(box)), "read " + box.volume() + " blocks", readAdvice(box))) {
            RESERVED.set(Boolean.TRUE);
            return work.get();
        } finally {
            RESERVED.set(Boolean.FALSE);
            largeReads.release();
        }
    }

    /** Says how large a region of the same height fits, so the caller gets it right on the next call. */
    private String readAdvice(Box box) {
        long perChunk = MemoryGuard.BYTES_PER_CHUNK + 256L * box.sizeY() * MemoryGuard.BYTES_PER_READ_BLOCK;
        long side = (long) Math.sqrt((double) (memory.room() / perChunk)) * 16;
        return (side >= 16 ? "A region of about " + side + "x" + side + " blocks at this height fits; " : "Nothing of this height fits; ")
                + "chunks loaded by earlier calls are freed after about 15 seconds, and more server memory lifts the limit.";
    }

    private long readCost(String world, List<Box> boxes) {
        List<Long> volumes = boxes.stream().map(Box::volume).toList();
        return volumes.stream().mapToLong(Long::longValue).sum() * MemoryGuard.BYTES_PER_READ_BLOCK
                + unloadedChunks(world, boxes, volumes) * MemoryGuard.BYTES_PER_CHUNK;
    }

    /**
     * Chunks the boxes would make the server load. A box with more chunks than blocks to touch in it (a long line,
     * a few scattered blocks) counts one chunk per block instead.
     *
     * @param touched for each box, the most blocks the work touches inside it
     */
    private long unloadedChunks(String worldName, List<Box> boxes, List<Long> touched) {
        return sync(() -> {
            World world = Bukkit.getWorld(worldName);
            if (world == null) return 0L;
            java.util.Set<Long> chunks = new java.util.HashSet<>();
            long sparse = 0;
            for (int i = 0; i < boxes.size(); i++) {
                Box box = boxes.get(i);
                int x1 = box.min().x() >> 4, x2 = box.max().x() >> 4, z1 = box.min().z() >> 4, z2 = box.max().z() >> 4;
                if ((long) (x2 - x1 + 1) * (z2 - z1 + 1) > touched.get(i)) {
                    sparse += touched.get(i);
                    continue;
                }
                for (int x = x1; x <= x2; x++) {
                    for (int z = z1; z <= z2; z++) {
                        if (!world.isChunkLoaded(x, z)) chunks.add(((long) x << 32) | (z & 0xFFFFFFFFL));
                    }
                }
            }
            return chunks.size() + sparse;
        });
    }

    /** The world as it is, in slabs off the main thread so a large read never holds a tick for long. */
    private String[] readWorld(String world, Box box) {
        if (box.volume() <= READ_SLAB || Bukkit.isPrimaryThread()) return sync(() -> bridge.read(world, box));
        String[] out = new String[(int) box.volume()];
        int at = 0;
        for (Box slab : Op.slabs(box, READ_SLAB)) {
            String[] part = sync(() -> bridge.read(world, slab));
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    /** With review on, takes the newest edits out of the caller's world's draft instead of using WorldEdit's undo. */
    public int undo(Caller caller, int steps) {
        if (reviewOn()) return drafts.undo(caller.world(), steps);
        return sync(() -> bridge.undo(caller.id(), steps));
    }

    private volatile Drafts drafts;

    public void setDrafts(Drafts drafts) {
        this.drafts = drafts;
    }

    public Drafts drafts() {
        return drafts;
    }

    /** True when Claude's edits go into drafts (review.enabled). */
    public boolean reviewOn() {
        Drafts d = drafts;
        return d != null && d.reviewOn();
    }

    /**
     * Reads boxes a slice per tick (or right away with FastAsyncWorldEdit) without the draft overlay, and waits.
     * For history, drafts and region exports; not for the main thread.
     */
    public RegionSnapshot readStepped(String world, List<Box> boxes, long perTick) {
        checkWorld(world);
        long volume = boxes.stream().mapToLong(Box::volume).sum();
        if (volume > READ_SLAB) {
            memory.check(readCost(world, boxes), "read " + volume + " blocks", "Use a smaller region, or wait 15 seconds for"
                    + " chunks loaded by earlier calls to be freed; more server memory lifts the limit.");
        }
        RegionSnapshot s = new RegionSnapshot(boxes);
        if (bridge.async()) {
            s.readAll(bridge, world, perTick);
            return s;
        }
        CompletableFuture<RegionSnapshot> done = new CompletableFuture<>();
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    if (s.step(bridge, world, perTick)) {
                        cancel();
                        done.complete(s);
                    }
                } catch (Throwable ex) {
                    // An Error too: a task that fails again every tick would never let go of what it holds.
                    cancel();
                    done.completeExceptionally(ex instanceof OutOfMemoryError
                            ? new EditException("the server ran out of memory reading the region; use a smaller one") : ex);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        try {
            return done.get(5, TimeUnit.MINUTES);
        } catch (ExecutionException ex) {
            throw ex.getCause() instanceof RuntimeException re ? re : new EditException(String.valueOf(ex.getCause()));
        } catch (TimeoutException ex) {
            throw new EditException("reading the region took too long");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new EditException("interrupted");
        }
    }

    public Optional<JobResult> job(String id) {
        synchronized (jobs) {
            Job job = jobs.get(id);
            return job == null ? Optional.empty() : Optional.of(job.snapshot());
        }
    }

    /** Where an edit goes: AUTO follows review.enabled, DIRECT always places, DRAFT always goes into a draft. */
    public enum Mode { AUTO, DIRECT, DRAFT }

    /**
     * @param revertOf the history entry the job reverts, or null
     * @param draftOf  the draft the job places, or null
     * @param label    shown on the draft entry, e.g. "promote from staging"
     */
    public record Options(Mode mode, String revertOf, String draftOf, String label) {
        public static final Options AUTO = new Options(Mode.AUTO, null, null, null);
    }

    /** Validates and starts an edit job; every op in the list becomes one undo step together. */
    public Job submit(Caller caller, List<Op> ops) {
        return submit(caller, ops, Options.AUTO);
    }

    public Job submit(Caller caller, List<Op> ops, Options options) {
        if (ops.isEmpty()) throw new EditException("no operations");
        boolean preview = options.mode() == Mode.DRAFT || (options.mode() == Mode.AUTO && reviewOn());
        Drafts draftStore = drafts;
        if (preview && draftStore == null) throw new EditException("drafts are off on this server");
        List<String> warnings = validate(caller, ops);
        List<Op> pieces = new ArrayList<>();
        List<Integer> owners = new ArrayList<>();
        List<Op.Light> lights = new ArrayList<>();
        long total = 0;
        Box bounds = null;
        for (int i = 0; i < ops.size(); i++) {
            Box b = ops.get(i).bounds();
            bounds = bounds == null ? b : Box.of(Vec3.min(bounds.min(), b.min()), Vec3.max(bounds.max(), b.max()));
            if (ops.get(i) instanceof Op.Light light) {
                lights.add(light);
                continue;
            }
            for (Op piece : ops.get(i).split(config.blocksPerTick())) {
                pieces.add(piece);
                owners.add(i);
                total += piece.volume();
            }
        }
        Plan plan = new Plan(ops, pieces, owners.stream().mapToInt(Integer::intValue).toArray(), lights);
        // A dry run changes nothing in the world, so there is nothing to snapshot for the history.
        Recorder rec = preview ? null : recorder;
        List<Box> boxes = rec == null ? null : rec.boxes(caller, ops);
        RegionSnapshot before = boxes == null ? null : new RegionSnapshot(boxes);

        // What the edit holds while it runs: its blocks in WorldEdit's history (or the draft), the chunks it has
        // to load, and the before and after snapshots when it is tracked.
        long blocks = ops.stream().mapToLong(Op::volume).sum();
        long need = blocks * MemoryGuard.BYTES_PER_EDIT_BLOCK
                + unloadedChunks(caller.world(), ops.stream().map(Op::bounds).toList(), ops.stream().map(Op::volume).toList()) * MemoryGuard.BYTES_PER_CHUNK
                + (boxes == null ? 0 : boxes.stream().mapToLong(Box::volume).sum() * MemoryGuard.BYTES_PER_READ_BLOCK);
        MemoryGuard.Reservation held = memory.reserve(need, "edit up to " + blocks + " blocks", "Split the edit into smaller calls,"
                + " or wait 15 seconds for chunks to unload. Large edits also stay in WorldEdit's undo history (15 per player);"
                + " //clearhistory frees them, and more server memory lifts the limit.");

        Job job = new Job("j" + nextJob.getAndIncrement(), warnings, caller, bounds, total);
        job.memory = held;
        job.revertOf = options.revertOf();
        job.draftOf = options.draftOf();
        job.label = options.label();
        job.preview = preview;
        if (before != null) job.phase = "snapshot";
        if (preview) job.phase = "preview";
        Supplier<EditHandle> open = preview
                ? () -> bridge.preview(caller.id(), caller.world(), draftStore.overlay(caller.world()), draftStore.maxVolume())
                : () -> bridge.begin(caller.id(), caller.world());
        synchronized (jobs) {
            jobs.put(job.id, job);
        }
        fire(RelayProtocol.JOB_START, job);

        if (bridge.async()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> runAsync(caller, plan, job, before, open));
        } else {
            new TickRunner(caller, plan, job, before, open).runTaskTimer(plugin, 0L, 1L);
        }
        return job;
    }

    private void progress(Job job, long volume) {
        job.processed += volume;
        fire(RelayProtocol.JOB_PROGRESS, job);
    }

    /**
     * Everything is parsed before the first block is placed, and the job only reports done after the handle
     * is closed (flushed and remembered for undo), so "done" means the blocks are in the world.
     */
    private void runAsync(Caller caller, Plan plan, Job job, RegionSnapshot before, Supplier<EditHandle> open) {
        Throwable failure = null;
        int at = -1;
        EditHandle handle = null;
        try {
            handle = open.get();
            for (int i = 0; i < plan.pieces().size(); i++) {
                at = i;
                handle.prepare(plan.pieces().get(i));
            }
            at = -1;
            if (before != null) {
                before.readAll(bridge, caller.world(), recorder.readsPerTick());
                job.phase = "build";
                fire(RelayProtocol.JOB_PROGRESS, job);
            }
            for (int i = 0; i < plan.pieces().size(); i++) {
                at = i;
                Op piece = plan.pieces().get(i);
                apply(handle, piece, job);
                progress(job, piece.volume());
            }
            at = -1;
            light(caller, handle, plan.lights(), job);
        } catch (Throwable t) {
            failure = t;
        }
        if (handle != null) {
            try {
                handle.close();
            } catch (Throwable t) {
                if (failure == null) {
                    failure = t;
                    at = -1;
                }
            }
        }
        RuntimeException error = failure == null ? null : plan.explain(failure, at);
        RegionSnapshot after = null;
        if (before != null && before.done()) {
            job.phase = "diff";
            try {
                after = before.fresh();
                after.readAll(bridge, caller.world(), recorder.readsPerTick());
            } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "job " + job.id + ": could not read the result for history", e);
                after = null;
            }
        }
        complete(job, error, before, after, handle);
    }

    /**
     * A preview job's changes go into the world's draft; any other job is recorded in the history (off the main
     * thread). Then the job is reported finished.
     */
    private void complete(Job job, RuntimeException error, RegionSnapshot before, RegionSnapshot after, EditHandle handle) {
        if (job.preview) {
            RuntimeException failure = error;
            if (failure == null) {
                try {
                    BlockDiff diff = ((PreviewHandle) handle).changes();
                    job.changed = diff.size();
                    job.draft = drafts.add(job.caller.world(), new Draft.Entry(job.tool, job.source, job.caller.name(),
                            job.started, diff, job.label));
                } catch (RuntimeException e) {
                    failure = e;
                }
            }
            finish(job, failure);
            return;
        }
        Recorder rec = recorder;
        if (rec == null) {
            finish(job, error);
            return;
        }
        Runnable record = () -> {
            try {
                BlockDiff diff = before != null && after != null && after.done() ? RegionSnapshot.diff(before, after) : null;
                job.phase = "done";
                job.historyId = rec.record(provisional(job, error), diff, before == null ? null : before.boxes());
            } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "job " + job.id + ": could not save history", e);
            }
            finish(job, error);
        };
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, record);
        } else {
            record.run();
        }
    }

    /** The job as it will be reported once finished, for the history entry. */
    private static JobInfo provisional(Job job, RuntimeException error) {
        JobInfo i = job.info();
        String message = error == null ? null : error.getMessage() == null ? error.toString() : error.getMessage();
        return new JobInfo(i.id(), error == null ? "done" : "failed", "done", i.tool(), i.source(), i.caller(), i.world(),
                i.bounds(), i.total(), i.processed(), i.changed(), i.started(), System.currentTimeMillis() - i.started(),
                message, null, i.revertOf(), null, i.draftOf());
    }

    private void apply(EditHandle handle, Op piece, Job job) {
        int changed = handle.apply(piece);
        job.changed += changed;
        if (changed == 0 && piece instanceof Op.Tree tree && job.warnings.size() < MAX_TREE_WARNINGS) {
            job.warnings.add("no tree grew at " + tree.at() + ": it needs ground below and free space around");
        }
    }

    /** Lights what the pieces built: reads each region as it now is and places the op's block on its dark floors. */
    private void light(Caller caller, EditHandle handle, List<Op.Light> lights, Job job) {
        for (Op.Light light : lights) {
            handle.flush();
            // A room is dark because of the floor under it and the roof over it, and the region given often
            // stops short of both, so the layer below and some headroom are read with it.
            Box box = light.box();
            Box seen = box.withY(Math.max(caller.minY(), box.min().y() - 1), Math.min(caller.maxY(), box.max().y() + LIGHT_HEADROOM));
            if (seen.volume() > config.maxReadVolume()) seen = box;
            List<Vec3> spots = Lighting.place(seen, lightRead(caller.world(), seen, handle), emitted(light.block()), box.max().y());
            if (spots.isEmpty()) continue;
            job.changed += handle.apply(new Op.Blocks(spots.stream().map(p -> new Op.BlockAt(p, light.block())).toList()));
        }
    }

    /**
     * What the light op sees: the world after the pieces. A preview placed nothing, so that is the world with the
     * open draft and this job's own dry-run writes laid over it.
     */
    private String[] lightRead(String world, Box box, EditHandle handle) {
        if (!(handle instanceof PreviewHandle preview)) return readNow(world, box);
        String[] states = readWorld(world, box);
        drafts.overlay(world, box, states);
        return preview.changes().overlay(box, states, true);
    }

    /** Block light a block gives off; the invisible {@code light} block carries it in its level. */
    private static int emitted(String block) {
        BlockState state = BlockState.parse(block);
        if (state.type().equals("light")) return Integer.parseInt(state.props().getOrDefault("level", "15"));
        return BlockTraits.light(block);
    }

    /** Waits up to the configured time; long jobs report "running" and keep going. */
    public JobResult await(Job job) {
        try {
            return job.future.get(config.jobWaitSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return job.snapshot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return job.snapshot();
        } catch (ExecutionException e) {
            return new JobResult(job.id, "failed", job.changed, 0, String.valueOf(e.getCause()), job.warnings, job.draft);
        }
    }

    private void finish(Job job, RuntimeException error) {
        if (job.memory != null) job.memory.close();
        long ms = System.currentTimeMillis() - job.started;
        if (error == null) {
            job.future.complete(new JobResult(job.id, "done", job.changed, ms, null, job.warnings, job.draft));
        } else {
            Throwable root = error instanceof EditException && error.getCause() != null ? error.getCause() : error;
            if (!(root instanceof EditException)) plugin.getLogger().log(java.util.logging.Level.WARNING, "job " + job.id + " failed", root);
            String message = error.getMessage() == null ? error.toString() : error.getMessage();
            job.future.complete(new JobResult(job.id, "failed", job.changed, ms, message, job.warnings, job.draft));
        }
        job.phase = "done";
        fire(RelayProtocol.JOB_END, job);
    }

    /**
     * Applies op pieces on the main thread, at most {@code blocks-per-tick} blocks per tick, in one EditSession.
     * The first tick parses every piece before any block is placed.
     */
    private final class TickRunner extends BukkitRunnable {
        private final Caller caller;
        private final Plan plan;
        private final Job job;
        /** Read across ticks before the first block is placed; null when the job is not tracked. */
        private final RegionSnapshot before;
        private final Supplier<EditHandle> open;
        private RegionSnapshot after;
        private EditHandle handle;
        private int next;
        private boolean closed;
        private RuntimeException failure;

        TickRunner(Caller caller, Plan plan, Job job, RegionSnapshot before, Supplier<EditHandle> open) {
            this.caller = caller;
            this.plan = plan;
            this.job = job;
            this.before = before;
            this.open = open;
        }

        private boolean completed;

        @Override
        public void run() {
            try {
                tick();
            } catch (Throwable t) {
                // Out of memory outside build(), such as reading the result: stop here. A task that fails again
                // every tick would hold on to its handle and snapshots, the memory the server is short of.
                cancel();
                if (completed) return;
                completed = true;
                if (!closed && handle != null) {
                    try {
                        handle.close();
                    } catch (Throwable ignored) {
                    }
                }
                complete(job, failure != null ? failure : plan.explain(t, -1), before, null, handle);
            }
        }

        private void tick() {
            if (!closed) build();
            if (!closed) return;
            // Read the result for the history, also after a failure: whatever was placed is in the world.
            if (after != null && !after.done()) {
                try {
                    if (!after.step(bridge, caller.world(), recorder.readsPerTick())) return;
                } catch (RuntimeException e) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "job " + job.id + ": could not read the result for history", e);
                    after = null;
                }
            }
            cancel();
            completed = true;
            complete(job, failure, before, after, handle);
        }

        private void build() {
            int at = -1;
            List<Op> pieces = plan.pieces();
            try {
                if (handle == null) {
                    handle = open.get();
                    for (int i = 0; i < pieces.size(); i++) {
                        at = i;
                        handle.prepare(pieces.get(i));
                    }
                    at = -1;
                }
                if (before != null && !before.done()) {
                    if (!before.step(bridge, caller.world(), recorder.readsPerTick())) {
                        fire(RelayProtocol.JOB_PROGRESS, job);
                        return;
                    }
                    job.phase = "build";
                    fire(RelayProtocol.JOB_PROGRESS, job);
                    return; // start placing next tick
                }
                long budget = config.blocksPerTick();
                long applied = 0;
                while (next < pieces.size() && budget > 0) {
                    at = next;
                    Op piece = pieces.get(next++);
                    apply(handle, piece, job);
                    budget -= piece.volume();
                    applied += piece.volume();
                }
                at = -1;
                handle.flush();
                progress(job, applied);
                if (next >= pieces.size()) {
                    light(caller, handle, plan.lights(), job);
                    handle.close();
                    closeDone(null);
                }
            } catch (Throwable e) {
                // An Error too, above all OutOfMemoryError. Keep what was placed so it can still be undone as one step.
                if (handle != null) {
                    try {
                        handle.close();
                    } catch (Throwable ignored) {
                    }
                }
                closeDone(plan.explain(e, at));
            }
        }

        private void closeDone(RuntimeException error) {
            closed = true;
            failure = error;
            if (before != null && before.done()) {
                after = before.fresh();
                job.phase = "diff";
                fire(RelayProtocol.JOB_PROGRESS, job);
            }
        }
    }

    /** @return warnings for the result; throws for anything that must not run */
    private List<String> validate(Caller caller, List<Op> ops) {
        String world = caller.world();
        checkWorld(world);
        List<String> warnings = new ArrayList<>();
        for (Op op : ops) {
            long volume = op.volume();
            if (volume > config.maxVolumePerOp()) {
                throw new EditException(op.kind() + " touches ~" + volume
                        + " blocks; max per op is " + config.maxVolumePerOp());
            }
            if (op instanceof Op.Light light) {
                if (volume > config.maxReadVolume()) {
                    throw new EditException("light region is " + volume + " blocks; max is " + config.maxReadVolume());
                }
                int emit;
                try {
                    emit = emitted(light.block());
                } catch (NumberFormatException e) {
                    emit = 0;
                }
                if (emit < 1 || emit > 15) {
                    throw new EditException("'" + light.block() + "' gives no light; use torch, lantern, glowstone, sea_lantern, end_rod or light");
                }
            }
            Box bounds = op.bounds();
            checkZone(world, bounds);
            String what = outsideHeight(caller, bounds, op.kind());
            if (what != null) {
                // A sphere or pyramid near the limit is clipped by design; anything else is a mistake worth stopping.
                if (op instanceof Op.Sphere || op instanceof Op.Pyramid) {
                    warnings.add(what + "; the part outside was cut off");
                } else {
                    throw new EditException(what);
                }
            }
        }
        warnings.addAll(BlockCheck.check(bridge.catalog(), ops));
        return warnings;
    }

    /** Throws unless {@code bounds} may be changed: allowed world, build zone and the world's y limits. */
    public void checkEditable(Caller caller, Box bounds, String what) {
        checkWorld(caller.world());
        checkZone(caller.world(), bounds);
        String problem = outsideHeight(caller, bounds, what);
        if (problem != null) throw new EditException(problem);
    }

    private void checkZone(String world, Box bounds) {
        if (config.zone() != null) {
            if (!config.zoneWorld().equals(world) || !config.zone().contains(bounds)) {
                throw new EditException("edit " + bounds + " is outside the build zone "
                        + config.zoneWorld() + " " + config.zone());
            }
        }
    }

    private static String outsideHeight(Caller caller, Box bounds, String what) {
        if (bounds.min().y() >= caller.minY() && bounds.max().y() <= caller.maxY()) return null;
        return what + " covers y " + bounds.min().y() + ".." + bounds.max().y() + " but world '" + caller.world()
                + "' only allows y " + caller.minY() + ".." + caller.maxY() + " (Minecraft " + minecraftVersion() + ")";
    }

    private void checkWorld(String world) {
        if (!config.allowedWorlds().isEmpty() && !config.allowedWorlds().contains(world)) {
            throw new EditException("world '" + world + "' is not in safety.allowed-worlds");
        }
    }

    /** Names of loaded worlds, for error messages and get_context. */
    public List<String> worlds() {
        return sync(() -> Bukkit.getWorlds().stream().map(World::getName).toList());
    }
}
