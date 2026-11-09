package me.abdiskiosk.minecraftaibuilder.plugin;

import me.abdiskiosk.minecraftaibuilder.common.BlockCheck;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.EditHandle;
import me.abdiskiosk.minecraftaibuilder.common.Op;
import me.abdiskiosk.minecraftaibuilder.common.SelectionInfo;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import me.abdiskiosk.minecraftaibuilder.common.WorldEditBridge;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
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
    private final AtomicInteger nextJob = new AtomicInteger(1);
    private final Map<String, Job> jobs = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Job> eldest) {
            return size() > MAX_JOBS;
        }
    };

    public BuildService(Plugin plugin, WorldEditBridge bridge, PluginConfig config) {
        this.plugin = plugin;
        this.bridge = bridge;
        this.config = config;
    }

    public WorldEditBridge bridge() {
        return bridge;
    }

    public PluginConfig config() {
        return config;
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
        volatile long changed;

        Job(String id, List<String> warnings) {
            this.id = id;
            this.warnings = warnings;
        }

        JobResult snapshot() {
            JobResult done = future.getNow(null);
            return done != null ? done : new JobResult(id, "running", changed, System.currentTimeMillis() - started, null, warnings);
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
        if (ops.isEmpty()) throw new EditException("no operations");
        List<String> warnings = validate(caller, ops);
        Job job = new Job("j" + nextJob.getAndIncrement(), warnings);
        synchronized (jobs) {
            jobs.put(job.id, job);
        }
        List<Op> pieces = new ArrayList<>();
        List<Integer> owners = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            for (Op piece : ops.get(i).split(config.blocksPerTick())) {
                pieces.add(piece);
                owners.add(i);
            }
        }
        Plan plan = new Plan(ops, pieces, owners.stream().mapToInt(Integer::intValue).toArray());

        if (bridge.async()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> runAsync(caller, plan, job));
        } else {
            new TickRunner(caller, plan, job).runTaskTimer(plugin, 0L, 1L);
        }
        return job;
    }

    /**
     * Everything is parsed before the first block is placed, and the job only reports done after the handle
     * is closed (flushed and remembered for undo), so "done" means the blocks are in the world.
     */
    private void runAsync(Caller caller, Plan plan, Job job) {
        Throwable failure = null;
        int at = -1;
        EditHandle handle = null;
        try {
            handle = bridge.begin(caller.id(), caller.world());
            for (int i = 0; i < plan.pieces().size(); i++) {
                at = i;
                handle.prepare(plan.pieces().get(i));
            }
            for (int i = 0; i < plan.pieces().size(); i++) {
                at = i;
                job.changed += handle.apply(plan.pieces().get(i));
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
        finish(job, failure == null ? null : plan.explain(failure, at));
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
    }

    /**
     * Applies op pieces on the main thread, at most {@code blocks-per-tick} blocks per tick, in one EditSession.
     * The first tick parses every piece before any block is placed.
     */
    private final class TickRunner extends BukkitRunnable {
        private final Caller caller;
        private final Plan plan;
        private final Job job;
        private EditHandle handle;
        private int next;

        TickRunner(Caller caller, Plan plan, Job job) {
            this.caller = caller;
            this.plan = plan;
            this.job = job;
        }

        @Override
        public void run() {
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
                long budget = config.blocksPerTick();
                while (next < pieces.size() && budget > 0) {
                    at = next;
                    Op piece = pieces.get(next++);
                    job.changed += handle.apply(piece);
                    budget -= piece.volume();
                }
                at = -1;
                handle.flush();
                if (next >= pieces.size()) {
                    cancel();
                    handle.close();
                    finish(job, null);
                }
            } catch (RuntimeException e) {
                cancel();
                // Keep what was placed so it can still be undone as one step.
                if (handle != null) {
                    try {
                        handle.close();
                    } catch (RuntimeException ignored) {
                    }
                }
                finish(job, plan.explain(e, at));
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
            if (config.zone() != null) {
                if (!config.zoneWorld().equals(world) || !config.zone().contains(bounds)) {
                    throw new EditException("edit " + bounds + " is outside the build zone "
                            + config.zoneWorld() + " " + config.zone());
                }
            }
            if (bounds.min().y() < caller.minY() || bounds.max().y() > caller.maxY()) {
                String what = op.kind() + " covers y " + bounds.min().y() + ".." + bounds.max().y() + " but world '" + world
                        + "' only allows y " + caller.minY() + ".." + caller.maxY() + " (Minecraft " + minecraftVersion() + ")";
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
