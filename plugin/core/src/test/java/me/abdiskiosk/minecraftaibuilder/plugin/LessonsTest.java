package me.abdiskiosk.minecraftaibuilder.plugin;

import com.google.gson.JsonObject;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LessonsTest {

    @TempDir
    Path dir;

    @Test
    void textIsCleanedAndCut() {
        Lessons lessons = new Lessons(null);
        Lessons.Lesson l = lessons.add("  Use\tspruce\n\nfloors  ", Lessons.MANUAL, List.of(), "Ann").lesson();
        assertEquals("Use spruce floors", l.text());
        assertFalse(l.seen());
        assertEquals(Lessons.MAX_TEXT, lessons.add("x".repeat(500), Lessons.MANUAL, List.of(), "Ann").lesson().text().length());
        assertThrows(EditException.class, () -> lessons.add(" \n ", Lessons.MANUAL, List.of(), "Ann"));
        assertThrows(EditException.class, () -> lessons.add("ok", "boss", List.of(), "Ann"));
        assertThrows(EditException.class, () -> lessons.add("ok", Lessons.CLAUDE, List.of("../etc"), "Claude"));
    }

    @Test
    void theSameTextIsSavedOnce() {
        Lessons lessons = new Lessons(null);
        String id = lessons.add("No oak floors", Lessons.CLAUDE, List.of("h1"), "Claude").lesson().id();
        Lessons.Saved again = lessons.add("no oak floors", Lessons.FEEDBACK, List.of("d2"), "Ann");
        assertEquals(id, again.lesson().id());
        assertEquals(List.of("h1", "d2"), again.lesson().entries());
        assertEquals(1, lessons.list().size());
    }

    @Test
    void aFullListMakesRoomWithClaudesOldestLesson() {
        Lessons lessons = new Lessons(null);
        for (int i = 0; i < Lessons.MAX - 2; i++) lessons.add("person " + i, Lessons.MANUAL, List.of(), "Ann");
        Lessons.Lesson firstClaude = lessons.add("claude 0", Lessons.CLAUDE, List.of(), "Claude").lesson();
        lessons.add("claude 1", Lessons.CLAUDE, List.of(), "Claude");
        assertEquals(Lessons.MAX, lessons.list().size());

        // Even a person's lesson replaces Claude's oldest, not the oldest of all.
        Lessons.Saved saved = lessons.add("person new", Lessons.FEEDBACK, List.of("d1"), "Bob");
        assertEquals(firstClaude.id(), saved.replaced().id());
        assertEquals(Lessons.MAX, lessons.list().size());
        assertEquals("claude 1", lessons.add("claude 2", Lessons.CLAUDE, List.of(), "Claude").replaced().text());

        // Only people's lessons are left once claude 2 goes: Claude is refused, a person replaces the oldest.
        assertEquals("claude 2", lessons.add("person newer", Lessons.MANUAL, List.of(), "Bob").replaced().text());
        EditException refused = assertThrows(EditException.class, () -> lessons.add("claude 3", Lessons.CLAUDE, List.of(), "Claude"));
        assertTrue(refused.getMessage().contains("only a person can make room"), refused.getMessage());
        assertEquals("person 0", lessons.add("person newest", Lessons.MANUAL, List.of(), "Bob").replaced().text());
        assertEquals(Lessons.MAX, lessons.list().size());
    }

    @Test
    void editRemoveAndSeen() {
        Lessons lessons = new Lessons(null);
        String a = lessons.add("first", Lessons.CLAUDE, List.of(), "Claude").lesson().id();
        String b = lessons.add("second", Lessons.CLAUDE, List.of(), "Claude").lesson().id();
        assertNotEquals(a, b);
        Lessons.Lesson edited = lessons.edit(a, "first, edited");
        assertEquals("first, edited", edited.text());
        assertTrue(edited.seen());
        assertEquals(1, lessons.markSeen());
        assertEquals(0, lessons.markSeen());
        assertTrue(lessons.remove(b));
        assertFalse(lessons.remove(b));
        assertThrows(EditException.class, () -> lessons.edit(b, "x"));
    }

    @Test
    void feedbackReachesClaudeOnceAndCanBeRemembered() {
        Lessons lessons = new Lessons(null);
        lessons.feedback("d1", "too dark", false, "panel: Ann");
        lessons.feedback("d1", "too dark inside; add windows", false, "panel: Ann"); // replaces the first about d1
        JsonObject stored = lessons.feedback("h2", "x".repeat(400), true, "panel: Bob");
        assertEquals(Lessons.MAX_FEEDBACK, stored.get("feedback").getAsString().length());
        assertEquals(Lessons.MAX_TEXT, stored.getAsJsonObject("lesson").get("text").getAsString().length());
        assertEquals("feedback", stored.getAsJsonObject("lesson").get("origin").getAsString());
        assertEquals("h2", stored.getAsJsonObject("lesson").getAsJsonArray("entries").get(0).getAsString());

        JsonObject context = new JsonObject();
        lessons.addTo(context);
        assertEquals(1, context.getAsJsonArray("lessons").size());
        assertEquals(2, context.getAsJsonArray("feedback").size());
        assertEquals("d1: too dark inside; add windows", context.getAsJsonArray("feedback").get(0).getAsString());

        JsonObject next = new JsonObject();
        lessons.addTo(next);
        assertTrue(next.has("lessons"));
        assertFalse(next.has("feedback"));
        assertThrows(EditException.class, () -> lessons.feedback("d1", "  ", true, "Ann"));
    }

    /** Lessons are read in every session: a full list of full-length lessons must stay near 550 tokens. */
    @Test
    void aFullListStaysWithinItsTokenBudget() {
        Lessons lessons = new Lessons(null);
        for (int i = 0; i < Lessons.MAX; i++) lessons.add(i + " " + "x".repeat(200), Lessons.MANUAL, List.of(), "Ann");
        JsonObject context = new JsonObject();
        lessons.addTo(context);
        int chars = context.toString().length() - "{}".length();
        assertTrue(chars <= 1970, "\"lessons\":[...] is " + chars + " characters"); // 1967: about 560 tokens at 3.5 a token
    }

    @Test
    void anEmptyStoreAddsNothingToTheContext() {
        JsonObject context = new JsonObject();
        new Lessons(null).addTo(context);
        assertEquals(0, context.size());
    }

    @Test
    void lessonsAndPendingFeedbackSurviveARestart() throws Exception {
        Lessons lessons = new Lessons(dir);
        lessons.add("Roofs are dark_oak", Lessons.MANUAL, List.of(), "Ann");
        String id = lessons.add("Paths are gravel", Lessons.CLAUDE, List.of("h9"), "Claude").lesson().id();
        lessons.markSeen();
        lessons.feedback("d3", "wrong place", false, "panel: Ann");
        assertTrue(Files.isRegularFile(dir.resolve("lessons.json")));

        Lessons again = new Lessons(dir);
        assertEquals(lessons.list(), again.list());
        assertTrue(again.list().get(1).seen());
        assertEquals(List.of("h9"), again.list().get(1).entries());
        assertEquals("wrong place", again.takeFeedback().get(0).text());
        assertNotEquals(id, again.add("New one", Lessons.MANUAL, List.of(), "Ann").lesson().id());

        Files.writeString(dir.resolve("lessons.json"), "{not json");
        assertTrue(new Lessons(dir).list().isEmpty());
    }
}
