package me.abdiskiosk.minecraftaibuilder.plugin;

import me.abdiskiosk.minecraftaibuilder.common.EditException;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Refuses work the server's heap has no room for. A server that runs out of memory mid-edit does not recover: the
 * main thread dies and the world stops ticking, so a clear "not enough memory" before starting is the better answer.
 *
 * <p>The costs are rough figures measured on Paper 1.21 with WorldEdit 7.3. A loaded chunk stays in memory for about
 * ten seconds after the last call that touched it, which is what a sweep over far-away terrain pays for.
 */
public final class MemoryGuard {

    /** The states array and what a check or render builds from it. */
    public static final long BYTES_PER_READ_BLOCK = 40;
    /** WorldEdit's undo history and its buffers while the edit runs. */
    public static final long BYTES_PER_EDIT_BLOCK = 100;
    /** A chunk that has to be loaded, or generated, for the call. */
    public static final long BYTES_PER_CHUNK = 1_500_000;

    private static final long GC_INTERVAL_MS = 5_000;
    /** How long a call waits for memory before it is refused: chunks from earlier calls unload in about this time. */
    private static final long WAIT_MS = 15_000;
    private static final long POLL_MS = 500;

    private final long spare;
    private final LongSupplier free;
    private final Runnable collect;
    private final long waitMs;
    private final AtomicLong reserved = new AtomicLong();
    private volatile long lastCollect;

    /** @param spareBytes heap that stays free for the server itself, whatever is asked */
    public MemoryGuard(long spareBytes) {
        this(spareBytes, explicitGcWorks() ? MemoryGuard::freeNow : MemoryGuard::freeAfterLastGc,
                explicitGcWorks() ? System::gc : () -> { }, WAIT_MS);
    }

    MemoryGuard(long spareBytes, LongSupplier free, Runnable collect, long waitMs) {
        this.spare = spareBytes;
        this.free = free;
        this.collect = collect;
        this.waitMs = waitMs;
    }

    /** Memory held for work that is running; closing it gives the memory back. */
    public final class Reservation implements AutoCloseable {
        private long bytes;

        private Reservation(long bytes) {
            this.bytes = bytes;
        }

        @Override
        public synchronized void close() {
            reserved.addAndGet(-bytes);
            bytes = 0;
        }
    }

    /**
     * Sets {@code need} bytes aside for {@code what} ("read 1940000 blocks") until the reservation is closed. When
     * the heap is short it first waits a while: a server under load frees memory by itself as chunks unload.
     *
     * @param advice what the caller can do about a refusal, ending the message
     * @throws EditException when the heap cannot take it
     */
    public Reservation reserve(long need, String what, String advice) {
        await(need, what, advice, true);
        return new Reservation(need);
    }

    /** As {@link #reserve}, for work that is over before anything else could ask: nothing is set aside. */
    public void check(long need, String what, String advice) {
        await(need, what, advice, false);
    }

    private void await(long need, String what, String advice, boolean hold) {
        long deadline = System.currentTimeMillis() + waitMs;
        while (!take(need, hold)) {
            if (System.currentTimeMillis() - lastCollect > GC_INTERVAL_MS) {
                // The free figure counts garbage too, so ask for it to be cleared before waiting or saying no.
                collect.run();
                lastCollect = System.currentTimeMillis();
                continue;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new EditException("not enough free server memory to " + what + ": it needs about " + mb(need) + " MB and "
                        + mb(room()) + " MB can be used right now. " + advice);
            }
            try {
                Thread.sleep(Math.min(POLL_MS, Math.max(1, waitMs)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EditException("interrupted");
            }
        }
    }

    private synchronized boolean take(long need, boolean hold) {
        if (room() < need) return false;
        if (hold) reserved.addAndGet(need);
        return true;
    }

    /** Bytes new work may use: what is free, less the work already reserved and the spare heap. */
    public long room() {
        return Math.max(0, free.getAsLong() - reserved.get() - spare);
    }

    private static long mb(long bytes) {
        return (bytes + 524_288) / 1_048_576;
    }

    /** Many servers run with {@code -XX:+DisableExplicitGC}, where asking for a collection does nothing. */
    private static boolean explicitGcWorks() {
        return !ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:+DisableExplicitGC");
    }

    private static long freeNow() {
        Runtime r = Runtime.getRuntime();
        return r.maxMemory() - (r.totalMemory() - r.freeMemory());
    }

    /** What was in use after the last collection of each heap pool: the best figure when one cannot be asked for. */
    private static long freeAfterLastGc() {
        long live = 0;
        boolean known = false;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage after = pool.getType() == MemoryType.HEAP ? pool.getCollectionUsage() : null;
            if (after != null) {
                live += after.getUsed();
                known = true;
            }
        }
        return known ? Runtime.getRuntime().maxMemory() - live : freeNow();
    }
}
