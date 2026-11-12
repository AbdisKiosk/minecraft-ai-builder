package me.abdiskiosk.minecraftaibuilder.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative, explainable warning, not a verdict about prose: the lesson's named substitution recurred. */
final class LessonHealth {
    private LessonHealth() {}

    static JsonObject assess(Lessons.Lesson lesson, Collection<JsonObject> entries) {
        Set<String> signatures = new HashSet<>();
        String text = lesson.text().toLowerCase(Locale.ROOT);
        for (JsonObject e : entries) {
            if (!lesson.entries().contains(e.get("id").getAsString())) continue;
            for (JsonElement row : substitutions(e)) {
                JsonObject s = row.getAsJsonObject();
                String from = s.get("from").getAsString(), to = s.get("to").getAsString();
                // Do not infer a rule from unrelated free-text feedback on the same entry.
                if (!from.equals("air") && mentions(text, from) && mentions(text, to))
                    signatures.add(signature(e, s));
            }
        }
        JsonArray matches = new JsonArray();
        int count = 0;
        for (JsonObject e : entries) {
            if (!CorrectionCheck.CLAUDE_SOURCE.equals(e.get("source").getAsString())
                    || e.has("revertOf") || e.get("time").getAsLong() <= lesson.updated()
                    || lesson.entries().contains(e.get("id").getAsString())) continue;
            boolean match = false;
            for (JsonElement row : substitutions(e)) if (signatures.contains(signature(e, row.getAsJsonObject()))) match = true;
            if (!match) continue;
            count++;
            if (matches.size() < 5) matches.add(e.get("id").getAsString());
        }
        JsonObject out = new JsonObject();
        out.addProperty("status", signatures.isEmpty() ? "unavailable" : count >= 2 ? "recurring" : "watching");
        out.addProperty("count", count);
        out.add("entries", matches);
        return out;
    }

    private static boolean mentions(String text, String type) {
        String name = Pattern.quote(type).replace("_", "\\E[ _]\\Q");
        return Pattern.compile("(?<![a-z0-9_])" + name + "(?![a-z0-9_])").matcher(text).find();
    }

    private static JsonArray substitutions(JsonObject e) {
        if (!e.has("corrected")) return new JsonArray();
        JsonObject stamp = e.getAsJsonObject("corrected");
        return stamp.has("substitutions") ? stamp.getAsJsonArray("substitutions") : new JsonArray();
    }

    private static String signature(JsonObject e, JsonObject row) {
        return e.get("world").getAsString() + ":" + row.get("from").getAsString() + ">" + row.get("to").getAsString();
    }
}
