package me.abdiskiosk.minecraftaibuilder.plugin;

import com.google.gson.JsonObject;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static me.abdiskiosk.minecraftaibuilder.plugin.CorrectionCheck.HOUR;
import static me.abdiskiosk.minecraftaibuilder.plugin.CorrectionCheck.MINUTE;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CorrectionCheckTest {

    private static final long NOW = 100L * 24 * HOUR;

    private static JsonObject entry(String id, long age, String source) {
        JsonObject e = new JsonObject();
        e.addProperty("id", id);
        e.addProperty("time", NOW - age);
        e.addProperty("source", source);
        e.addProperty("tracked", true);
        e.addProperty("added", 10);
        e.addProperty("removed", 0);
        e.addProperty("changed", 0);
        return e;
    }

    private static JsonObject claude(String id, long age) {
        return entry(id, age, CorrectionCheck.CLAUDE_SOURCE);
    }

    private static JsonObject stamped(JsonObject e, long stampAge) {
        JsonObject stamp = new JsonObject();
        stamp.addProperty("cells", 0);
        stamp.addProperty("time", NOW - stampAge);
        e.add("corrected", stamp);
        return e;
    }

    @Test
    void picksClaudesTrackedEntriesBetweenTenMinutesAndSevenDaysOld() {
        JsonObject untracked = claude("untracked", HOUR);
        untracked.addProperty("tracked", false);
        JsonObject nothing = claude("nothing", HOUR);
        nothing.addProperty("added", 0);
        List<JsonObject> entries = List.of(
                claude("young", 9 * MINUTE),
                claude("old", 7 * 24 * HOUR + 1),
                claude("week", 7 * 24 * HOUR),
                entry("rest", HOUR, "panel rest"),
                entry("panel", HOUR, "panel"),
                entry("console", HOUR, "plugin"),
                untracked,
                nothing,
                claude("ten", 10 * MINUTE));
        assertEquals(List.of("ten", "week"), CorrectionCheck.pick(entries, NOW));
    }

    @Test
    void anEntryStampedInTheLastDayIsLeftAlone() {
        List<JsonObject> entries = List.of(
                stamped(claude("recent", 3 * 24 * HOUR), 23 * HOUR),
                stamped(claude("stale", 3 * 24 * HOUR + 1), 25 * HOUR),
                claude("never", 3 * 24 * HOUR + 2));
        assertEquals(List.of("stale", "never"), CorrectionCheck.pick(entries, NOW));
    }

    @Test
    void newestFirstAndAtMostFivePerRun() {
        List<JsonObject> entries = new ArrayList<>();
        for (int i = 0; i < 20; i++) entries.add(claude("h" + i, (20 - i) * HOUR)); // h19 is the newest
        List<String> picked = CorrectionCheck.pick(entries, NOW);
        assertEquals(List.of("h19", "h18", "h17", "h16", "h15", "h14", "h13", "h12", "h11", "h10"), picked);

        List<String> checked = new ArrayList<>();
        CorrectionCheck check = new CorrectionCheck(now -> picked, checked::add, Runnable::run, () -> NOW, Logger.getAnonymousLogger());
        assertEquals(5, check.run());
        assertEquals(List.of("h19", "h18", "h17", "h16", "h15"), checked);
    }

    @Test
    void aRefusalForMemoryStopsTheRun() {
        List<String> checked = new ArrayList<>();
        CorrectionCheck check = new CorrectionCheck(now -> List.of("a", "b", "c"), id -> {
            checked.add(id);
            if (id.equals("b")) throw new MemoryGuard.Refused("not enough free server memory");
        }, Runnable::run, () -> NOW, Logger.getAnonymousLogger());
        assertEquals(2, check.run());
        assertEquals(List.of("a", "b"), checked);
    }

    @Test
    void anEntryThatFailedIsSkippedForADay() {
        AtomicLong clock = new AtomicLong(NOW);
        List<String> checked = new ArrayList<>();
        CorrectionCheck check = new CorrectionCheck(now -> List.of("broken", "fine"), id -> {
            checked.add(id);
            if (id.equals("broken")) throw new EditException("the diff of entry broken is gone");
        }, Runnable::run, clock::get, Logger.getAnonymousLogger());
        check.run();
        assertEquals(List.of("broken", "fine"), checked);
        checked.clear();
        check.run();
        assertEquals(List.of("fine"), checked);
        clock.addAndGet(24 * HOUR);
        checked.clear();
        check.run();
        assertEquals(List.of("broken", "fine"), checked);
    }

    @Test
    void sessionsStartARunAtMostEveryTenMinutes() {
        AtomicLong clock = new AtomicLong(NOW);
        List<Runnable> started = new ArrayList<>();
        CorrectionCheck check = new CorrectionCheck(now -> List.of(), id -> { }, started::add, clock::get,
                Logger.getAnonymousLogger());
        check.sessionStarted();
        assertEquals(1, started.size());
        check.sessionStarted();
        assertEquals(1, started.size(), "a run is still going");
        started.get(0).run();
        clock.addAndGet(9 * MINUTE);
        check.sessionStarted();
        assertEquals(1, started.size(), "less than ten minutes since the last");
        clock.addAndGet(MINUTE);
        check.sessionStarted();
        assertEquals(2, started.size());
    }

    /** initialize starts it; tool calls, get_context among them, never do. */
    @Test
    void onlyInitializeStartsIt() {
        AtomicLong sessions = new AtomicLong();
        me.abdiskiosk.minecraftaibuilder.plugin.mcp.McpEndpoint mcp = new me.abdiskiosk.minecraftaibuilder.plugin.mcp.McpEndpoint(
                new me.abdiskiosk.minecraftaibuilder.plugin.tools.ToolRegistry(null, Logger.getAnonymousLogger()), "test");
        mcp.onSession(sessions::incrementAndGet);
        mcp.handle(com.google.gson.JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));
        mcp.handle(com.google.gson.JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"get_context\"}}"));
        assertEquals(0, sessions.get());
        mcp.handle(com.google.gson.JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"initialize\",\"params\":{}}"));
        assertEquals(1, sessions.get());
    }
}
