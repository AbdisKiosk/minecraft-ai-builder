package me.abdiskiosk.minecraftaibuilder.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.abdiskiosk.minecraftaibuilder.common.EditException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lessons Claude reads at the start of every session on this server ({@code get_context}), and feedback people gave
 * on the panel after rejecting a draft or reverting a build, which reaches Claude once. Kept in {@code lessons.json}
 * beside {@code areas.json}. A lesson applies as soon as it is saved; the panel's Lessons page is where a wrong one is
 * edited or deleted, and {@code seen} marks the ones nobody has looked at there yet. Relay and tool threads both use
 * this, so every method is synchronized.
 */
public final class Lessons {

    public static final int MAX = 12;
    public static final int MAX_TEXT = 160;
    public static final int MAX_FEEDBACK = 300;
    /** Pending feedback kept for Claude; more drops the oldest. */
    public static final int MAX_PENDING = 10;
    /** Delivered feedback ids remembered for the lesson tool. */
    public static final int MAX_DELIVERED = 10;
    public static final String FEEDBACK = "feedback";
    public static final String CLAUDE = "claude";
    public static final String MANUAL = "manual";
    private static final Set<String> ORIGINS = Set.of(FEEDBACK, CLAUDE, MANUAL);
    private static final int MAX_ENTRIES = 5;
    private static final int MAX_BY = 80;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");

    /** @param entries the history or draft ids the lesson came from; may be empty */
    public record Lesson(String id, String text, String origin, List<String> entries, String by, long time, boolean seen, long updated) {
    }

    /** What a person said about a rejected draft or reverted entry, waiting to reach Claude. */
    public record Feedback(String entry, String text, String by, long time) {
    }

    /** A lesson that was saved, and the one it pushed out when the list was full (or null). */
    public record Saved(Lesson lesson, Lesson replaced) {
    }

    private final Path file;
    private final List<Lesson> lessons = new ArrayList<>();
    private final List<Feedback> pending = new ArrayList<>();
    /** Ids whose feedback reached Claude, oldest first: a lesson from Claude must come from one or a correction. */
    private final List<String> delivered = new ArrayList<>();
    private long lastId;

    /** @param dir where {@code lessons.json} lives; null keeps everything in memory only */
    public Lessons(Path dir) {
        this.file = dir == null ? null : dir.resolve("lessons.json");
        load();
    }

    /** Control characters become spaces, runs of spaces one, and the text is cut to {@code max} characters. */
    public static String clean(String raw, int max) {
        String s = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", " ").replaceAll(" {2,}", " ").trim();
        return s.length() > max ? s.substring(0, max).trim() : s;
    }

    /** A history or draft id; throws unless it is 1-40 of A-Z, a-z, 0-9, _ and -. */
    public static String entryId(String raw) {
        if (raw == null || !ID.matcher(raw).matches()) throw new EditException("'" + raw + "' is not a history or draft id");
        return raw;
    }

    /**
     * Saves a lesson. When the list is full, the oldest lesson Claude wrote makes room; with none, a lesson from Claude
     * is refused and one from a person replaces the oldest of any origin. The same text twice is saved once.
     */
    public synchronized Saved add(String rawText, String origin, List<String> entries, String by) {
        if (!ORIGINS.contains(origin)) throw new EditException("origin must be feedback, claude or manual");
        String text = clean(rawText, MAX_TEXT);
        if (text.isEmpty()) throw new EditException("the lesson is empty");
        List<String> from = new ArrayList<>();
        if (entries != null) {
            for (String e : entries) {
                if (from.size() < MAX_ENTRIES && !from.contains(entryId(e))) from.add(e);
            }
        }
        for (int i = 0; i < lessons.size(); i++) {
            Lesson l = lessons.get(i);
            if (!l.text().equalsIgnoreCase(text)) continue;
            List<String> merged = new ArrayList<>(l.entries());
            for (String e : from) if (merged.size() < MAX_ENTRIES && !merged.contains(e)) merged.add(e);
            Lesson same = new Lesson(l.id(), l.text(), l.origin(), List.copyOf(merged), l.by(), l.time(), l.seen(), l.updated());
            lessons.set(i, same);
            save();
            return new Saved(same, null);
        }
        Lesson replaced = null;
        if (lessons.size() >= MAX) {
            // The list is in the order lessons were saved, so the first match is the oldest.
            Optional<Lesson> oldestClaude = lessons.stream().filter(l -> l.origin().equals(CLAUDE)).findFirst();
            if (oldestClaude.isPresent()) {
                replaced = oldestClaude.get();
            } else if (origin.equals(CLAUDE)) {
                throw new EditException("there are already " + MAX + " lessons, all written by people; only a person can make room"
                        + " (on the panel's Lessons page)");
            } else {
                replaced = lessons.get(0);
            }
            lessons.remove(replaced);
        }
        long now = System.currentTimeMillis();
        Lesson lesson = new Lesson(nextId(now), text, origin, List.copyOf(from), clean(by, MAX_BY), now, false, now);
        lessons.add(lesson);
        save();
        return new Saved(lesson, replaced);
    }

    /** Changes a lesson's text; someone edited it on the panel, so it counts as seen. */
    public synchronized Lesson edit(String id, String rawText) {
        String text = clean(rawText, MAX_TEXT);
        if (text.isEmpty()) throw new EditException("the lesson is empty");
        for (int i = 0; i < lessons.size(); i++) {
            Lesson l = lessons.get(i);
            if (!l.id().equals(id)) continue;
            Lesson edited = new Lesson(l.id(), text, l.origin(), l.entries(), l.by(), l.time(), true, System.currentTimeMillis());
            lessons.set(i, edited);
            save();
            return edited;
        }
        throw new EditException("no lesson '" + id + "'");
    }

    public synchronized boolean remove(String id) {
        boolean removed = lessons.removeIf(l -> l.id().equals(id));
        if (removed) save();
        return removed;
    }

    /** Marks every lesson seen; returns how many were new. */
    public synchronized int markSeen() {
        int n = 0;
        for (int i = 0; i < lessons.size(); i++) {
            Lesson l = lessons.get(i);
            if (l.seen()) continue;
            lessons.set(i, new Lesson(l.id(), l.text(), l.origin(), l.entries(), l.by(), l.time(), true, l.updated()));
            n++;
        }
        if (n > 0) save();
        return n;
    }

    /** The lessons, oldest first. */
    public synchronized List<Lesson> list() {
        return List.copyOf(lessons);
    }

    /**
     * Feedback on a rejected draft or reverted entry: queued for Claude (a newer one about the same entry replaces the
     * older), and with {@code remember} also saved as a lesson. Returns what was stored.
     */
    public synchronized JsonObject feedback(String entry, String rawText, boolean remember, String by) {
        entryId(entry);
        String text = clean(rawText, MAX_FEEDBACK);
        if (text.isEmpty()) throw new EditException("the feedback is empty");
        String who = clean(by, MAX_BY);
        pending.removeIf(f -> f.entry().equals(entry));
        if (pending.size() >= MAX_PENDING) pending.remove(0);
        pending.add(new Feedback(entry, text, who, System.currentTimeMillis()));
        JsonObject out = new JsonObject();
        out.addProperty("feedback", text);
        if (remember) {
            Saved saved = add(text, FEEDBACK, List.of(entry), who);
            out.add("lesson", json(saved.lesson()));
            if (saved.replaced() != null) out.add("replaced", json(saved.replaced()));
        } else {
            save();
        }
        return out;
    }

    /** Pending feedback, oldest first; it is delivered once. */
    public synchronized List<Feedback> takeFeedback() {
        if (pending.isEmpty()) return List.of();
        List<Feedback> out = List.copyOf(pending);
        pending.clear();
        for (Feedback f : out) {
            delivered.remove(f.entry());
            delivered.add(f.entry());
        }
        while (delivered.size() > MAX_DELIVERED) delivered.remove(0);
        save();
        return out;
    }

    /** True when feedback on this draft or history id reached Claude (one of the last {@value #MAX_DELIVERED}). */
    public synchronized boolean feedbackDelivered(String entry) {
        return delivered.contains(entry);
    }

    /** Said once when get_context carries {@code corrections} or {@code feedback}. */
    static final String HINT = "If one of these is a rule for later builds, save it: lesson {\"text\",\"entry\"}.";

    /**
     * Adds what Claude reads in {@code get_context}: {@code lessons} (the texts) when there are any, and
     * {@code feedback} ("<id>: <text>") when some is waiting, which this takes. When the context then has
     * {@code corrections} or {@code feedback}, a {@code hint} says how to save a lesson.
     */
    public void addTo(JsonObject context) {
        List<Lesson> all = list();
        if (!all.isEmpty()) {
            JsonArray texts = new JsonArray();
            all.forEach(l -> texts.add(l.text()));
            context.add("lessons", texts);
        }
        List<Feedback> feedback = takeFeedback();
        if (!feedback.isEmpty()) {
            JsonArray f = new JsonArray();
            feedback.forEach(x -> f.add(x.entry() + ": " + x.text()));
            context.add("feedback", f);
        }
        if (context.has("corrections") || context.has("feedback")) context.addProperty("hint", HINT);
    }

    /** The list for the panel: {"lessons":[...],"max":12}. */
    public JsonObject listJson() {
        JsonArray a = new JsonArray();
        list().forEach(l -> a.add(json(l)));
        JsonObject out = new JsonObject();
        out.add("lessons", a);
        out.addProperty("max", MAX);
        return out;
    }

    public static JsonObject json(Lesson l) {
        JsonObject o = new JsonObject();
        o.addProperty("id", l.id());
        o.addProperty("text", l.text());
        o.addProperty("origin", l.origin());
        JsonArray e = new JsonArray();
        l.entries().forEach(e::add);
        o.add("entries", e);
        o.addProperty("by", l.by());
        o.addProperty("time", l.time());
        o.addProperty("seen", l.seen());
        o.addProperty("updated", l.updated());
        return o;
    }

    private String nextId(long now) {
        long id = Math.max(now, lastId + 1);
        lastId = id;
        return "l" + Long.toString(id, 36);
    }

    /** A missing or unreadable file means no lessons; entries that do not fit the rules are skipped. */
    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.has("lessons") && root.get("lessons").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("lessons")) {
                    if (lessons.size() >= MAX || !el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    String id = str(o, "id");
                    String text = clean(str(o, "text"), MAX_TEXT);
                    String origin = str(o, "origin");
                    if (id.isEmpty() || text.isEmpty() || !ORIGINS.contains(origin)) continue;
                    List<String> entries = new ArrayList<>();
                    if (o.has("entries") && o.get("entries").isJsonArray()) {
                        for (JsonElement e : o.getAsJsonArray("entries")) {
                            String s = e.isJsonPrimitive() ? e.getAsString() : "";
                            if (ID.matcher(s).matches() && entries.size() < MAX_ENTRIES) entries.add(s);
                        }
                    }
                    long time = o.has("time") ? o.get("time").getAsLong() : 0;
                    lastId = Math.max(lastId, time);
                    // Two lessons added in one millisecond get time+1 as an id, so the time alone can repeat an id.
                    if (id.startsWith("l")) {
                        try { lastId = Math.max(lastId, Long.parseLong(id.substring(1), 36)); } catch (NumberFormatException ignored) { }
                    }
                    lessons.add(new Lesson(id, text, origin, List.copyOf(entries), clean(str(o, "by"), MAX_BY), time,
                            o.has("seen") && o.get("seen").getAsBoolean(), o.has("updated") ? o.get("updated").getAsLong() : time));
                }
            }
            if (root.has("feedback") && root.get("feedback").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("feedback")) {
                    if (pending.size() >= MAX_PENDING || !el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    String entry = str(o, "entry");
                    String text = clean(str(o, "text"), MAX_FEEDBACK);
                    if (!ID.matcher(entry).matches() || text.isEmpty()) continue;
                    pending.add(new Feedback(entry, text, clean(str(o, "by"), MAX_BY), o.has("time") ? o.get("time").getAsLong() : 0));
                }
            }
            if (root.has("delivered") && root.get("delivered").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("delivered")) {
                    String id = el.isJsonPrimitive() ? el.getAsString() : "";
                    if (ID.matcher(id).matches() && !delivered.contains(id)) delivered.add(id);
                }
                while (delivered.size() > MAX_DELIVERED) delivered.remove(0);
            }
        } catch (IOException | RuntimeException e) {
            lessons.clear();
            pending.clear();
            delivered.clear();
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private void save() {
        if (file == null) return;
        JsonObject root = new JsonObject();
        JsonArray a = new JsonArray();
        lessons.forEach(l -> a.add(json(l)));
        root.add("lessons", a);
        JsonArray f = new JsonArray();
        for (Feedback x : pending) {
            JsonObject o = new JsonObject();
            o.addProperty("entry", x.entry());
            o.addProperty("text", x.text());
            o.addProperty("by", x.by());
            o.addProperty("time", x.time());
            f.add(o);
        }
        root.add("feedback", f);
        JsonArray d = new JsonArray();
        delivered.forEach(d::add);
        root.add("delivered", d);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, root.toString());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new EditException("lessons changed until the next restart; " + file.getFileName() + " could not be written: "
                    + e.getClass().getSimpleName());
        }
    }
}
