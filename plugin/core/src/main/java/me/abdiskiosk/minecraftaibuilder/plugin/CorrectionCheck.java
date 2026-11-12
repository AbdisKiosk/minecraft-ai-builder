package me.abdiskiosk.minecraftaibuilder.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Looks, in the background, at whether people changed what Claude built ({@link History#corrections}), so that
 * {@code get_context} can tell Claude in a later session. It starts when an MCP session starts, at most once every
 * ten minutes, and never inside a tool call. A run checks at most {@link #MAX_PER_RUN} of Claude's entries, newest
 * first and one at a time, each through the memory-safe read; it stops as soon as the heap has no room
 * ({@link MemoryGuard.Refused}), and an entry whose check failed is left alone for a day.
 */
public final class CorrectionCheck {

    /** Claude's edits come in over MCP, which the relay marks with this source. */
    public static final String CLAUDE_SOURCE = "panel mcp";
    static final long MINUTE = 60_000L;
    static final long HOUR = 60 * MINUTE;
    /** Younger entries may still be being worked on. */
    static final long MIN_AGE = 10 * MINUTE;
    static final long MAX_AGE = 7 * 24 * HOUR;
    /** An entry stamped more recently than this is not checked again. */
    static final long RESTAMP = 24 * HOUR;
    static final long INTERVAL = 10 * MINUTE;
    static final int MAX_PER_RUN = 5;

    private final LongFunction<List<String>> candidates;
    private final Consumer<String> check;
    private final Executor executor;
    private final LongSupplier clock;
    private final Logger logger;
    private final AtomicLong lastStart = new AtomicLong(Long.MIN_VALUE / 2);
    private final AtomicBoolean running = new AtomicBoolean();
    /** Entries whose check failed (other than for memory), and when. */
    private final Map<String, Long> failed = new ConcurrentHashMap<>();

    /**
     * @param candidates the ids {@link #pick} chooses at a time, newest first
     * @param check      checks and stamps one entry; throws when it cannot
     */
    public CorrectionCheck(LongFunction<List<String>> candidates, Consumer<String> check, Executor executor,
                           LongSupplier clock, Logger logger) {
        this.candidates = candidates;
        this.check = check;
        this.executor = executor;
        this.clock = clock;
        this.logger = logger;
    }

    public static CorrectionCheck of(History history, Executor executor, Logger logger) {
        return new CorrectionCheck(history::correctionCandidates, history::corrections, executor, System::currentTimeMillis, logger);
    }

    /**
     * An MCP session started: starts a run unless one started less than ten minutes ago or is still going. Returns at
     * once.
     */
    public void sessionStarted() {
        long now = clock.getAsLong();
        long last = lastStart.get();
        if (now - last < INTERVAL || running.get() || !lastStart.compareAndSet(last, now)) return;
        running.set(true);
        try {
            executor.execute(() -> {
                try {
                    run();
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException e) { // a rejected task (shutting down): nothing to do
            running.set(false);
            logger.log(Level.FINE, "Correction check not started", e);
        }
    }

    /** Checks the chosen entries one at a time; returns how many were checked. */
    int run() {
        long now = clock.getAsLong();
        failed.values().removeIf(t -> now - t >= RESTAMP);
        int checked = 0;
        for (String id : candidates.apply(now)) {
            if (checked >= MAX_PER_RUN) break;
            if (failed.containsKey(id)) continue;
            try {
                checked++;
                check.accept(id);
            } catch (MemoryGuard.Refused e) {
                logger.fine("Correction check stopped: " + e.getMessage());
                return checked;
            } catch (RuntimeException e) {
                failed.put(id, now);
                logger.log(Level.FINE, "Could not check history " + id + " for corrections", e);
            } catch (Throwable e) { // an Error: stop the run, so nothing holds on to what the server is short of
                failed.put(id, now);
                logger.log(Level.WARNING, "Correction check stopped on history " + id, e);
                return checked;
            }
        }
        return checked;
    }

    /**
     * The entries a run checks, newest first: Claude's ({@link #CLAUDE_SOURCE}), tracked and with changed blocks,
     * between ten minutes and seven days old, never stamped or stamped more than a day ago. Up to twice
     * {@link #MAX_PER_RUN}, so that failed ones can be skipped.
     */
    static List<String> pick(Collection<JsonObject> entries, long now) {
        List<JsonObject> chosen = new ArrayList<>();
        for (JsonObject e : entries) {
            if (!CLAUDE_SOURCE.equals(str(e, "source")) || !bool(e, "tracked")) continue;
            if (num(e, "added") + num(e, "removed") + num(e, "changed") == 0) continue;
            long age = now - num(e, "time");
            if (age < MIN_AGE || age > MAX_AGE) continue;
            JsonElement stamp = e.get("corrected");
            if (stamp != null && stamp.isJsonObject() && now - num(stamp.getAsJsonObject(), "time") <= RESTAMP) continue;
            chosen.add(e);
        }
        chosen.sort(Comparator.comparingLong((JsonObject e) -> -num(e, "time")));
        List<String> out = new ArrayList<>();
        for (JsonObject e : chosen) {
            if (out.size() >= 2 * MAX_PER_RUN) break;
            out.add(str(e, "id"));
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    private static long num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsLong() : 0;
    }
}
