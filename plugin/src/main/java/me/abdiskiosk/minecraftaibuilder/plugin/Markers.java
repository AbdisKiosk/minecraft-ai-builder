package me.abdiskiosk.minecraftaibuilder.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Named positions players set with {@code /aibuilder mark}, kept per world in {@code markers.json}, and notes players
 * leave for Claude with {@code /aibuilder note} (in memory only). Commands run on the server thread and tools on HTTP
 * threads, so every method is synchronized.
 */
public final class Markers {

    public static final int MAX_PER_WORLD = 50;
    public static final int MAX_NOTES = 10;
    public static final int MAX_NOTE_LENGTH = 300;
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,24}");

    private final Path file;
    private final Map<String, Map<String, Vec3>> markers = new TreeMap<>();
    private final Map<String, Deque<String>> notes = new LinkedHashMap<>();

    /** @param dir where {@code markers.json} lives; null keeps markers in memory only */
    public Markers(Path dir) {
        this.file = dir == null ? null : dir.resolve("markers.json");
        load();
    }

    /** The marker name a player typed, lower-cased; throws unless it is 1-24 of a-z, 0-9, _ and -. */
    public static String name(String raw) {
        String name = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches()) throw new EditException("marker names are 1-24 of a-z, 0-9, _ and -");
        return name;
    }

    /** Sets or moves a marker; returns true when it already existed. */
    public synchronized boolean set(String world, String rawName, Vec3 pos) {
        String name = name(rawName);
        Map<String, Vec3> inWorld = markers.computeIfAbsent(world, w -> new TreeMap<>());
        if (!inWorld.containsKey(name) && inWorld.size() >= MAX_PER_WORLD) {
            throw new EditException("world '" + world + "' already has " + MAX_PER_WORLD + " markers; remove one with /aibuilder unmark <name>");
        }
        boolean moved = inWorld.put(name, pos) != null;
        save();
        return moved;
    }

    public synchronized boolean remove(String world, String rawName) {
        Map<String, Vec3> inWorld = markers.get(world);
        if (inWorld == null || inWorld.remove(name(rawName)) == null) return false;
        if (inWorld.isEmpty()) markers.remove(world);
        save();
        return true;
    }

    public synchronized Optional<Vec3> get(String world, String name) {
        Map<String, Vec3> inWorld = markers.get(world);
        return inWorld == null ? Optional.empty() : Optional.ofNullable(inWorld.get(name.toLowerCase(Locale.ROOT)));
    }

    /** The markers of one world, sorted by name. */
    public synchronized Map<String, Vec3> list(String world) {
        Map<String, Vec3> inWorld = markers.get(world);
        return inWorld == null ? Map.of() : new TreeMap<>(inWorld);
    }

    /** Worlds that have markers, sorted. */
    public synchronized List<String> worlds() {
        return new ArrayList<>(markers.keySet());
    }

    /** Queues a note; text past {@value #MAX_NOTE_LENGTH} characters is cut and an eleventh note drops the oldest. */
    public synchronized String addNote(String player, String text) {
        String note = text.replaceAll("\\p{Cntrl}", " ").trim();
        if (note.isEmpty()) throw new EditException("the note is empty");
        if (note.length() > MAX_NOTE_LENGTH) note = note.substring(0, MAX_NOTE_LENGTH);
        Deque<String> queue = notes.computeIfAbsent(player, p -> new ArrayDeque<>());
        if (queue.size() >= MAX_NOTES) queue.removeFirst();
        queue.addLast(note);
        return note;
    }

    /** The player's pending notes, oldest first; they are delivered once. */
    public synchronized List<String> takeNotes(String player) {
        Deque<String> queue = notes.remove(player);
        return queue == null ? List.of() : new ArrayList<>(queue);
    }

    /** Every player's pending notes, as player name to notes; all are delivered once. */
    public synchronized Map<String, List<String>> takeAllNotes() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        notes.forEach((player, queue) -> out.put(player, new ArrayList<>(queue)));
        notes.clear();
        return out;
    }

    /** A missing or unreadable file means no markers; entries that do not fit the rules are skipped. */
    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> world : root.entrySet()) {
                if (!world.getValue().isJsonObject()) continue;
                Map<String, Vec3> inWorld = new TreeMap<>();
                for (Map.Entry<String, JsonElement> m : world.getValue().getAsJsonObject().entrySet()) {
                    if (!NAME.matcher(m.getKey()).matches() || inWorld.size() >= MAX_PER_WORLD) continue;
                    JsonElement v = m.getValue();
                    if (!v.isJsonArray() || v.getAsJsonArray().size() != 3) continue;
                    JsonArray a = v.getAsJsonArray();
                    inWorld.put(m.getKey(), new Vec3(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()));
                }
                if (!inWorld.isEmpty()) markers.put(world.getKey(), inWorld);
            }
        } catch (IOException | RuntimeException e) {
            markers.clear();
        }
    }

    private void save() {
        if (file == null) return;
        JsonObject root = new JsonObject();
        markers.forEach((world, inWorld) -> {
            JsonObject o = new JsonObject();
            inWorld.forEach((name, v) -> {
                JsonArray a = new JsonArray();
                a.add(v.x());
                a.add(v.y());
                a.add(v.z());
                o.add(name, a);
            });
            root.add(world, o);
        });
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("markers.json.tmp");
            Files.writeString(tmp, root.toString());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new EditException("markers changed until the next restart; markers.json could not be written: " + e.getClass().getSimpleName());
        }
    }
}
