package me.abdiskiosk.minecraftaibuilder.plugin;

import me.abdiskiosk.minecraftaibuilder.common.BlockCheck;
import me.abdiskiosk.minecraftaibuilder.common.BlockDiff;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.EditHandle;
import me.abdiskiosk.minecraftaibuilder.common.Op;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs bridge calls on the right thread, enforces limits and tracks edit jobs. */
public final class BuildService {

    private static final int MAX_JOBS = 200;

    private final Plugin plugin;
    private final WorldEditBridge bridge;
    private final PluginConfig config;
    private final Path dataDir;
    private final Markers markers;
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

    public record JobResult(String job, String status, long changed, long ms, String error, List<String> warnings) {
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

        Job(String id, List<String> warnings, Caller caller, Box bounds, long total) {
            this.id = id;
            this.warnings = warnings;
            this.caller = caller;
            this.bounds = bounds;
            this.total = total;
        }

        public String id() {
            return id;
        }

        JobResult snapshot() {
            JobResult done = future.getNow(null);
            return done != null ? done : new JobResult(id, "running", changed, System.currentTimeMillis() - started, null, warnings);
        }

        public JobInfo info() {
            JobResult r = snapshot();
            return new JobInfo(id, r.status(), phase, tool, source, caller.name(), caller.world(), bounds, total, processed,
                    r.changed(), started, r.ms(), r.error(), historyId, revertOf);
        }
    }

    /** What the panel shows about a job; {@code processed} out of {@code total} is the progress. */
    public record JobInfo(String id, String status, String phase, String tool, String source, String caller, String world,
                          Box bounds, long total, long processed, long changed, long started, long ms, String error,
                          String history, String revertOf) {
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

    /** The ops of a call and the pieces they were split into, so a failure can name the op it came from. */
    private record Plan(List<Op> ops, List<Op> pieces, int[] owner) {
        RuntimeException explain(Throwable failure, int piece) {
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
                    + ". Read a smaller box or use mode=summary on parts.");
        }
        return sync(() -> bridge.read(world, box));
    }

    public int undo(Caller caller, int steps) {
        return sync(() -> bridge.undo(caller.id(), steps));
    }

    public Optional<JobResult> job(String id) {
        synchronized (jobs) {
            Job job = jobs.get(id);
            return job == null ? Optional.empty() : Optional.of(job.snapshot());
        }
    }

    /** Validates and starts an edit job; every op in the list becomes one undo step together. */
    public Job submit(Caller caller, List<Op> ops) {
        return submit(caller, ops, null);
    }

    /** @param revertOf the history entry this job reverts, or null */
    public Job submit(Caller caller, List<Op> ops, String revertOf) {
        if (ops.isEmpty()) throw new EditException("no operations");
        List<String> warnings = validate(caller, ops);
        List<Op> pieces = new ArrayList<>();
        List<Integer> owners = new ArrayList<>();
        long total = 0;
        Box bounds = null;
        for (int i = 0; i < ops.size(); i++) {
            Box b = ops.get(i).bounds();
            bounds = bounds == null ? b : Box.of(Vec3.min(bounds.min(), b.min()), Vec3.max(bounds.max(), b.max()));
            for (Op piece : ops.get(i).split(config.blocksPerTick())) {
                pieces.add(piece);
                owners.add(i);
                total += piece.volume();
            }
        }
        Plan plan = new Plan(ops, pieces, owners.stream().mapToInt(Integer::intValue).toArray());
        Recorder rec = recorder;
        List<Box> boxes = rec == null ? null : rec.boxes(caller, ops);
        RegionSnapshot before = boxes == null ? null : new RegionSnapshot(boxes);

        Job job = new Job("j" + nextJob.getAndIncrement(), warnings, caller, bounds, total);
        job.revertOf = revertOf;
        if (before != null) job.phase = "snapshot";
        synchronized (jobs) {
            jobs.put(job.id, job);
        }
        fire(RelayProtocol.JOB_START, job);

        if (bridge.async()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> runAsync(caller, plan, job, before));
        } else {
            new TickRunner(caller, plan, job, before).runTaskTimer(plugin, 0L, 1L);
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
    private void runAsync(Caller caller, Plan plan, Job job, RegionSnapshot before) {
        Throwable failure = null;
        int at = -1;
        EditHandle handle = null;
        try {
            handle = bridge.begin(caller.id(), caller.world());
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
                job.changed += handle.apply(piece);
                progress(job, piece.volume());
            }
            at = -1;
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
        complete(job, error, before, after);
    }

    /** Records the job in the history (off the main thread), then reports it finished. */
    private void complete(Job job, RuntimeException error, RegionSnapshot before, RegionSnapshot after) {
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
                message, null, i.revertOf());
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
            return new JobResult(job.id, "failed", job.changed, 0, String.valueOf(e.getCause()), job.warnings);
        }
    }

    private void finish(Job job, RuntimeException error) {
        long ms = System.currentTimeMillis() - job.started;
        if (error == null) {
            job.future.complete(new JobResult(job.id, "done", job.changed, ms, null, job.warnings));
        } else {
            Throwable root = error instanceof EditException && error.getCause() != null ? error.getCause() : error;
            if (!(root instanceof EditException)) plugin.getLogger().log(java.util.logging.Level.WARNING, "job " + job.id + " failed", root);
            String message = error.getMessage() == null ? error.toString() : error.getMessage();
            job.future.complete(new JobResult(job.id, "failed", job.changed, ms, message, job.warnings));
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
        private RegionSnapshot after;
        private EditHandle handle;
        private int next;
        private boolean closed;
        private RuntimeException failure;

        TickRunner(Caller caller, Plan plan, Job job, RegionSnapshot before) {
            this.caller = caller;
            this.plan = plan;
            this.job = job;
            this.before = before;
        }

        @Override
        public void run() {
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
            complete(job, failure, before, after);
        }

        private void build() {
            int at = -1;
            List<Op> pieces = plan.pieces();
            try {
                if (handle == null) {
                    handle = bridge.begin(caller.id(), caller.world());
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
                    job.changed += handle.apply(piece);
                    budget -= piece.volume();
                    applied += piece.volume();
                }
                at = -1;
                handle.flush();
                progress(job, applied);
                if (next >= pieces.size()) {
                    handle.close();
                    closeDone(null);
                }
            } catch (RuntimeException e) {
                // Keep what was placed so it can still be undone as one step.
                if (handle != null) {
                    try {
                        handle.close();
                    } catch (RuntimeException ignored) {
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
