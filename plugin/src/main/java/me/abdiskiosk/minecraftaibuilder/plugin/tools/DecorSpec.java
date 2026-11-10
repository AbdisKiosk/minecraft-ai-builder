package me.abdiskiosk.minecraftaibuilder.plugin.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** The {@code items} of a decorate call: sign text, container contents, item frames and armor stands. */
public final class DecorSpec {

    public static final int MAX_ITEMS = 200;
    public static final int MAX_COUNT = 64 * 27;
    public static final Set<String> FACINGS = Set.of("north", "south", "east", "west", "up", "down");

    private static final Set<String> KINDS = Set.of("text", "contents", "frame", "stand");
    private static final Set<String> KEYS = new TreeSet<>(List.of("at", "text", "contents", "frame", "facing", "stand"));
    private static final Set<String> STAND_KEYS = new TreeSet<>(List.of("yaw", "head", "chest", "legs", "feet", "hand", "arms", "small", "base"));

    private DecorSpec() {
    }

    public sealed interface Item permits SignText, Contents, Frame, Stand {
        Vec3 at();
    }

    /** @param lines exactly four lines, top to bottom; missing ones are empty */
    public record SignText(Vec3 at, List<String> lines) implements Item {
    }

    /** @param items item name to count, in the order given */
    public record Contents(Vec3 at, Map<String, Integer> items) implements Item {
    }

    /**
     * @param item   "" for an empty frame
     * @param facing the way the frame faces, away from the block it hangs on
     */
    public record Frame(Vec3 at, String item, String facing) implements Item {
    }

    /** Equipment fields are item names or null. */
    public record Stand(Vec3 at, float yaw, String head, String chest, String legs, String feet, String hand,
                        boolean arms, boolean small, boolean base) implements Item {
    }

    /** Reads {@code items} (and {@code origin}, which offsets plain-number positions like batch does). */
    public static List<Item> parse(Args a) {
        Vec3 origin = a.has("origin") ? a.vec("origin") : null;
        JsonArray list = a.array("items");
        if (list.size() > MAX_ITEMS) throw new EditException(list.size() + " items; max is " + MAX_ITEMS + " per call");
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            try {
                if (!list.get(i).isJsonObject()) throw new EditException("must be an object");
                items.add(item(a.with(list.get(i).getAsJsonObject(), origin)));
            } catch (EditException e) {
                throw new EditException("items[" + i + "]: " + e.getMessage(), e);
            } catch (RuntimeException e) {
                throw new EditException("items[" + i + "]: bad arguments: " + e.getMessage(), e);
            }
        }
        return items;
    }

    private static Item item(Args a) {
        for (String key : a.json().keySet()) {
            if (!KEYS.contains(key)) throw new EditException("unknown key \"" + key + "\"; allowed: " + String.join(", ", KEYS));
        }
        List<String> kinds = a.json().keySet().stream().filter(KINDS::contains).toList();
        if (kinds.size() != 1) {
            throw new EditException("needs exactly one of text, contents, frame, stand" + (kinds.isEmpty() ? "" : "; got " + String.join(", ", kinds)));
        }
        if (a.has("facing") && !kinds.get(0).equals("frame")) throw new EditException("\"facing\" only goes with frame");
        Vec3 at = a.vec("at");
        return switch (kinds.get(0)) {
            case "text" -> {
                JsonArray text = a.array("text");
                if (text.isEmpty() || text.size() > 4) throw new EditException("text takes 1 to 4 lines, got " + text.size());
                List<String> lines = new ArrayList<>();
                for (int n = 0; n < 4; n++) lines.add(n < text.size() ? text.get(n).getAsString() : "");
                yield new SignText(at, lines);
            }
            case "contents" -> {
                Map<String, Integer> items = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> e : a.object("contents").entrySet()) {
                    JsonElement v = e.getValue();
                    double count = v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsDouble() : Double.NaN;
                    if (count != Math.floor(count) || count < 1 || count > MAX_COUNT) {
                        throw new EditException("count of '" + e.getKey() + "' must be a whole number from 1 to " + MAX_COUNT + ", got " + v);
                    }
                    items.put(e.getKey(), (int) count);
                }
                yield new Contents(at, items);
            }
            case "frame" -> {
                if (!a.has("facing")) throw new EditException("frame needs \"facing\": " + facings());
                String facing = a.str("facing");
                if (!FACINGS.contains(facing)) throw new EditException("facing must be " + facings() + ", got '" + facing + "'");
                yield new Frame(at, a.str("frame"), facing);
            }
            default -> {
                JsonObject stand = a.object("stand");
                for (String key : stand.keySet()) {
                    if (!STAND_KEYS.contains(key)) {
                        throw new EditException("unknown stand key \"" + key + "\"; allowed: " + String.join(", ", STAND_KEYS));
                    }
                }
                Args s = a.with(stand, null);
                yield new Stand(at, (float) s.dbl("yaw", 0), s.str("head", null), s.str("chest", null), s.str("legs", null),
                        s.str("feet", null), s.str("hand", null), s.bool("arms", false), s.bool("small", false), s.bool("base", true));
            }
        };
    }

    private static String facings() {
        return "north, south, east, west, up or down (the way the frame faces, away from its wall)";
    }
}
