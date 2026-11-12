package me.abdiskiosk.minecraftaibuilder.plugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LessonHealthTest {
    private static Lessons.Lesson lesson(String text, long updated) {
        return new Lessons.Lesson("l1", text, Lessons.MANUAL, List.of("h1"), "Ann", 10, true, updated);
    }

    private static JsonObject entry(String id, long time, String world, String source, String from, String to) {
        JsonObject e = new JsonObject();
        e.addProperty("id", id); e.addProperty("time", time); e.addProperty("world", world); e.addProperty("source", source);
        e.add("corrected", JsonParser.parseString("{\"cells\":4,\"substitutions\":[{\"from\":\"" + from
                + "\",\"to\":\"" + to + "\",\"count\":4,\"at\":[]}]}"));
        return e;
    }

    @Test
    void needsTwoDistinctLaterBuildsInTheSameWorld() {
        JsonObject source = entry("h1", 1, "world", "panel mcp", "oak_planks", "spruce_planks");
        JsonObject a = entry("h2", 20, "world", "panel mcp", "oak_planks", "spruce_planks");
        JsonObject b = entry("h3", 30, "world", "panel mcp", "oak_planks", "spruce_planks");
        Lessons.Lesson lesson = lesson("Use spruce_planks, not oak_planks.", 10);
        assertEquals("watching", LessonHealth.assess(lesson, List.of(source, a)).get("status").getAsString());
        JsonObject health = LessonHealth.assess(lesson, List.of(source, a, b));
        assertEquals("recurring", health.get("status").getAsString());
        assertEquals(2, health.get("count").getAsInt());
        assertEquals("[\"h2\",\"h3\"]", health.get("entries").toString());
        assertEquals("watching", LessonHealth.assess(lesson(lesson.text(), 25), List.of(source, a, b)).get("status").getAsString(),
                "editing resets the observation period; marking seen does not");
    }

    @Test
    void doesNotInventEvidenceFromProseUnknownSourcesRevertsOrOtherWorlds() {
        JsonObject source = entry("h1", 1, "world", "panel mcp", "oak_planks", "spruce_planks");
        JsonObject otherWorld = entry("h2", 20, "elsewhere", "panel mcp", "oak_planks", "spruce_planks");
        JsonObject script = entry("h3", 20, "world", "panel rest", "oak_planks", "spruce_planks");
        JsonObject revert = entry("h4", 20, "world", "panel mcp", "oak_planks", "spruce_planks");
        revert.addProperty("revertOf", "h1");
        JsonObject old = entry("h5", 5, "world", "panel mcp", "oak_planks", "spruce_planks");
        JsonObject reverse = entry("h6", 20, "world", "panel mcp", "spruce_planks", "oak_planks");
        Lessons.Lesson lesson = lesson("Use spruce_planks, not oak_planks.", 10);
        assertEquals(0, LessonHealth.assess(lesson, List.of(source, otherWorld, script, revert, old, reverse)).get("count").getAsInt());
        assertEquals("unavailable", LessonHealth.assess(lesson("Add windows", 10), List.of(source)).get("status").getAsString());
        assertEquals("unavailable", LessonHealth.assess(lesson("Use spruce_planks, not dark_oak_planks", 10), List.of(source)).get("status").getAsString());
        assertEquals("watching", LessonHealth.assess(lesson("Use spruce planks, not oak planks", 10), List.of(source)).get("status").getAsString());
        assertEquals("unavailable", LessonHealth.assess(lesson, List.of(otherWorld)).get("status").getAsString());
    }
}
