package me.abdiskiosk.minecraftaibuilder.plugin;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandLogFilterTest {
    @Test
    void dropsALinkCommandFromTheLogAndLogsItMasked() {
        List<String> relogged = new CopyOnWriteArrayList<>();
        CommandLogFilter.install(relogged::add);
        // Log4j's default configuration enables ERROR only; servers log commands at INFO, which they enable.
        LogManager.getLogger("net.minecraft.server").error("Steve issued server command: /aibuilder link ABCD-EFGH");
        LogManager.getLogger("net.minecraft.server").error("Steve issued server command: /aibuilder marks");
        assertEquals(List.of("Steve issued server command: /aibuilder link ********"), relogged);

        LinkCodeMask.active = false; // as after onDisable: every line goes through
        LogManager.getLogger("net.minecraft.server").error("Steve issued server command: /aibuilder link ABCD-EFGH");
        assertEquals(1, relogged.size());
    }
}
