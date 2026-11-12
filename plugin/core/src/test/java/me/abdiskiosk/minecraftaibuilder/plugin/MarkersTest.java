package me.abdiskiosk.minecraftaibuilder.plugin;

import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MarkersTest {

    @TempDir
    Path dir;

    @Test
    void setMoveRemoveAndListPerWorld() {
        Markers m = new Markers(null);
        assertFalse(m.set("world", "Door", new Vec3(1, 64, 2)));
        assertTrue(m.set("world", "door", new Vec3(5, 64, 5)));
        m.set("world", "gate", new Vec3(0, 70, 0));
        m.set("nether", "door", new Vec3(9, 9, 9));
        assertEquals(Map.of("door", new Vec3(5, 64, 5), "gate", new Vec3(0, 70, 0)), m.list("world"));
        assertEquals(List.of("door", "gate"), List.copyOf(m.list("world").keySet()));
        assertEquals(Optional.of(new Vec3(9, 9, 9)), m.get("nether", "DOOR"));
        assertTrue(m.remove("world", "door"));
        assertFalse(m.remove("world", "door"));
        assertEquals(Optional.empty(), m.get("world", "door"));
        assertEquals(List.of("nether", "world"), m.worlds());
        assertEquals(Map.of(), m.list("end"));
    }

    @Test
    void markersSurviveARestart() {
        Markers first = new Markers(dir);
        first.set("world", "door", new Vec3(1, -5, 3));
        first.set("world", "well", new Vec3(4, 5, 6));
        first.remove("world", "well");
        Markers second = new Markers(dir);
        assertEquals(Map.of("door", new Vec3(1, -5, 3)), second.list("world"));
    }

    @Test
    void namesFollowTheRules() {
        assertEquals("tower_2-a", Markers.name("Tower_2-A"));
        for (String bad : new String[]{"", "a b", "door!", "x".repeat(25), "@door"}) {
            assertThrows(EditException.class, () -> Markers.name(bad), bad);
        }
        assertEquals(24, Markers.name("x".repeat(24)).length());
        assertThrows(EditException.class, () -> new Markers(null).set("world", "café", new Vec3(0, 0, 0)));
    }

    @Test
    void aWorldHoldsAtMostFiftyMarkers() {
        Markers m = new Markers(null);
        for (int i = 0; i < Markers.MAX_PER_WORLD; i++) m.set("world", "m" + i, new Vec3(i, 0, 0));
        assertTrue(assertThrows(EditException.class, () -> m.set("world", "one-more", new Vec3(0, 0, 0)))
                .getMessage().contains("/aibuilder unmark"));
        assertTrue(m.set("world", "m3", new Vec3(7, 7, 7))); // moving one is still fine
        m.set("other", "one-more", new Vec3(0, 0, 0));
    }

    @Test
    void aCorruptFileMeansNoMarkers() throws IOException {
        Files.writeString(dir.resolve("markers.json"), "{not json");
        Markers m = new Markers(dir);
        assertTrue(m.worlds().isEmpty());
        m.set("world", "door", new Vec3(1, 2, 3));
        assertEquals(Map.of("door", new Vec3(1, 2, 3)), new Markers(dir).list("world"));
    }

    @Test
    void notesAreCutCappedAndDeliveredOnce() {
        Markers m = new Markers(null);
        assertEquals(300, m.addNote("Ann", "x".repeat(400)).length());
        for (int i = 1; i <= 10; i++) m.addNote("Ann", "note " + i);
        m.addNote("Bob", "hi");
        List<String> notes = m.takeNotes("Ann");
        assertEquals(10, notes.size());
        assertEquals("note 1", notes.get(0));
        assertEquals("note 10", notes.get(9));
        assertEquals(List.of(), m.takeNotes("Ann"));
        assertEquals(Map.of("Bob", List.of("hi")), m.takeAllNotes());
        assertEquals(Map.of(), m.takeAllNotes());
        assertThrows(EditException.class, () -> m.addNote("Ann", "  "));
    }

    @Test
    void areasShareNamesWithMarkersAndSurviveARestart() {
        Markers m = new Markers(dir);
        Box plot = Box.of(new Vec3(10, 60, 10), new Vec3(0, 90, 20));
        assertFalse(m.setArea("world", "Plot", plot, " a market\nhere "));
        assertEquals(Optional.of(new Markers.Area(plot, "a market here")), m.area("world", "plot"));
        m.set("world", "door", new Vec3(1, 2, 3));
        assertThrows(EditException.class, () -> m.setArea("world", "door", plot, ""));
        assertThrows(EditException.class, () -> m.set("world", "plot", new Vec3(0, 0, 0)));
        m.setArea("nether", "door", plot, ""); // another world has its own names
        assertTrue(m.setArea("world", "plot", plot, ""));
        Markers second = new Markers(dir);
        assertEquals(Map.of("plot", new Markers.Area(plot, "")), second.areas("world"));
        assertEquals("{\"box\":[0,60,10,10,90,20]}", Markers.json(second.area("world", "plot").orElseThrow()).toString());
        assertTrue(second.removeArea("world", "plot"));
        assertFalse(second.removeArea("world", "plot"));
        assertEquals(Map.of(), new Markers(dir).areas("world"));
    }
}
