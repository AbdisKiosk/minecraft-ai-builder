package me.abdiskiosk.minecraftaibuilder.plugin;

import me.abdiskiosk.minecraftaibuilder.common.EditException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryGuardTest {

    private static final long MB = 1_048_576;

    @Test
    void workThatFitsBesideTheSpareHeapIsAllowed() {
        MemoryGuard guard = new MemoryGuard(256 * MB, () -> 1000 * MB, () -> { }, 0);
        assertDoesNotThrow(() -> guard.check(700 * MB, "read 1 block", ""));
        String refused = assertThrows(EditException.class, () -> guard.check(800 * MB, "read 20000000 blocks", "Use less.")).getMessage();
        assertEquals("not enough free server memory to read 20000000 blocks: it needs about 800 MB and 744 MB can be used right now. Use less.", refused);
    }

    @Test
    void reservedMemoryCountsUntilItIsGivenBack() {
        MemoryGuard guard = new MemoryGuard(256 * MB, () -> 1000 * MB, () -> { }, 0);
        MemoryGuard.Reservation first = guard.reserve(500 * MB, "edit", "");
        assertThrows(EditException.class, () -> guard.reserve(500 * MB, "edit", ""));
        first.close();
        first.close(); // closing twice gives the memory back once
        assertDoesNotThrow(() -> guard.reserve(700 * MB, "edit", ""));
    }

    /** The free figure counts garbage, so a collection is asked for before anything is refused, but not every time. */
    @Test
    void aCollectionIsTriedOnceBeforeRefusing() {
        AtomicLong free = new AtomicLong(300 * MB);
        AtomicInteger collections = new AtomicInteger();
        MemoryGuard guard = new MemoryGuard(256 * MB, free::get, () -> {
            collections.incrementAndGet();
            free.set(900 * MB);
        }, 0);
        assertDoesNotThrow(() -> guard.check(400 * MB, "read", ""));
        assertEquals(1, collections.get());

        free.set(300 * MB);
        assertThrows(EditException.class, () -> guard.check(400 * MB, "read", ""));
        assertEquals(1, collections.get());
    }

    /** Chunks from earlier calls unload by themselves, so a call waits for room before it is turned away. */
    @Test
    void aCallWaitsForMemoryToComeFree() {
        AtomicLong free = new AtomicLong(300 * MB);
        MemoryGuard guard = new MemoryGuard(256 * MB, free::get, () -> { }, 5_000);
        Thread unload = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
            free.set(900 * MB);
        });
        unload.start();
        long start = System.currentTimeMillis();
        assertDoesNotThrow(() -> guard.reserve(400 * MB, "read", ""));
        assertTrue(System.currentTimeMillis() - start < 4_000);
    }
}
