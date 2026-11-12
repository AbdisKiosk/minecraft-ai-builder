package me.abdiskiosk.minecraftaibuilder.plugin;

import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import me.abdiskiosk.minecraftaibuilder.common.WorldEditBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A region export (promote, Save as asset) takes its turn with the other large reads, so their chunks never pile up. */
class ExportReadLockTest {

    /** 102,400 blocks: more than one read slab and within promote.max-volume below. */
    private static final Box BOX = Box.of(new Vec3(0, 0, 0), new Vec3(15, 399, 15));

    @TempDir Path dir;

    private BuildService service() {
        WorldEditBridge bridge = (WorldEditBridge) Proxy.newProxyInstance(WorldEditBridge.class.getClassLoader(),
                new Class<?>[]{WorldEditBridge.class}, (p, m, a) -> m.getName().equals("name") ? "fake" : null);
        return new BuildService(null, bridge, null, dir);
    }

    private static Drafts drafts(BuildService service) {
        return new Drafts(service, new Drafts.Settings(false, 1000, 2, 1_000_000),
                new History.Settings(true, 1000, 10, 1 << 20, 4096, 1000), Logger.getAnonymousLogger());
    }

    @Test
    void anExportIsRefusedWhileAnotherLargeReadHoldsTheLock() throws Exception {
        BuildService service = service();
        service.largeReadWaitMs = 200;
        service.largeReads.acquire();
        try {
            EditException e = assertThrows(EditException.class, () -> drafts(service).export("world", BOX));
            assertEquals("the server is busy with other large reads; try again in a moment", e.getMessage());
        } finally {
            service.largeReads.release();
        }
    }

    @Test
    void anExportWaitsForTheLargeReadBeforeItAndGivesTheLockBack() throws Exception {
        BuildService service = service();
        service.largeReadWaitMs = 10_000;
        service.largeReads.acquire();
        CompletableFuture<Throwable> done = CompletableFuture.supplyAsync(() -> {
            try {
                drafts(service).export("world", BOX);
                return null;
            } catch (Throwable t) {
                return t;
            }
        });
        for (int i = 0; i < 100 && !service.largeReads.hasQueuedThreads(); i++) Thread.sleep(20);
        assertTrue(service.largeReads.hasQueuedThreads(), "the export did not wait for the lock");
        assertFalse(done.isDone());
        service.largeReads.release();
        // Past the lock, this stand-in server has no world to read; what matters is that the export got there.
        Throwable after = done.get(10, TimeUnit.SECONDS);
        assertFalse(after != null && String.valueOf(after.getMessage()).contains("busy with other large reads"), String.valueOf(after));
        assertEquals(1, service.largeReads.availablePermits(), "the export gave the lock back");
    }

    @Test
    void aSmallExportDoesNotWait() throws Exception {
        BuildService service = service();
        service.largeReadWaitMs = 10_000;
        service.largeReads.acquire();
        try {
            long start = System.nanoTime();
            // One block: no lock, so it fails at once on the stand-in server instead of waiting 10 seconds.
            assertThrows(RuntimeException.class, () -> drafts(service).export("world", Box.of(new Vec3(0, 0, 0), new Vec3(0, 0, 0))));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
        } finally {
            service.largeReads.release();
        }
    }
}
