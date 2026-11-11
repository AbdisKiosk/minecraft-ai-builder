package me.abdiskiosk.minecraftaibuilder.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mirrors block states for a flipped copy. Rotation is left to WorldEdit's paste transform, but WorldEdit 6 has
 * no mirror for block states, so mirroring is done here on the state strings and works the same on both bridges.
 *
 * <p>Reads leave out properties at their default value ({@code oak_stairs} is {@code facing=north}), so for common
 * blocks a default that the mirror changes is written out: otherwise a north-facing stair would stay north.
 */
public final class StateFlip {

    /** Type suffixes of blocks whose {@code facing} defaults to north. */
    private static final List<String> FACING_NORTH = List.of("_stairs", "_door", "_trapdoor", "_fence_gate", "_bed",
            "chest", "furnace", "smoker", "ladder", "wall_torch", "_wall_sign", "_wall_hanging_sign", "_wall_banner",
            "_wall_head", "_wall_skull", "_button", "lever", "repeater", "comparator", "_glazed_terracotta", "anvil",
            "lectern", "loom", "stonecutter", "grindstone", "bell", "campfire", "carved_pumpkin", "jack_o_lantern",
            "beehive", "bee_nest", "piston", "dispenser", "dropper", "barrel", "tripwire_hook", "cocoa", "end_portal_frame");
    /** Standing signs, banners and heads: {@code rotation} defaults to 0. */
    private static final List<String> ROTATION_ZERO = List.of("_sign", "_banner", "_head", "_skull");
    private static final List<String> NOT_ROTATION = List.of("_wall_sign", "_wall_hanging_sign", "_wall_banner", "_wall_head",
            "_wall_skull", "piston_head");

    private StateFlip() {
    }

    /** Every plain block in a pattern mirrored; weights such as {@code 70%} and non-block tokens are kept. */
    public static String flipPattern(String pattern, char axis) {
        List<String> out = new ArrayList<>();
        for (BlockSyntax.Token t : BlockSyntax.tokens(pattern)) {
            out.add(t.prefix() + (t.plainBlock() ? flip(t.body(), axis) : t.body()));
        }
        return String.join(",", out);
    }

    /**
     * {@code state} mirrored along {@code axis}: {@code 'x'} swaps east and west, {@code 'z'} north and south.
     * Property order is preserved and other properties are left alone.
     */
    public static String flip(String state, char axis) {
        if (axis != 'x' && axis != 'z') throw new IllegalArgumentException("axis must be x or z");
        int open = state.indexOf('[');
        int close = state.lastIndexOf(']');
        String name = open < 0 ? state : state.substring(0, open);
        String type = name.trim().toLowerCase(Locale.ROOT);
        if (type.startsWith("minecraft:")) type = type.substring("minecraft:".length());

        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();
        if (open >= 0) {
            if (close < open) return state; // malformed: left for the block check to report
            for (String pair : BlockSyntax.splitTopLevel(state.substring(open + 1, close), ',')) {
                if (pair.isBlank()) continue;
                int eq = pair.indexOf('=');
                if (eq < 0) return state;
                keys.add(pair.substring(0, eq).trim().toLowerCase(Locale.ROOT));
                values.add(pair.substring(eq + 1).trim().toLowerCase(Locale.ROOT));
            }
        }
        int given = keys.size();
        if (!keys.contains("facing") && endsWithAny(type, FACING_NORTH)) {
            keys.add("facing");
            values.add("north");
        }
        if (!keys.contains("rotation") && endsWithAny(type, ROTATION_ZERO) && !endsWithAny(type, NOT_ROTATION)) {
            keys.add("rotation");
            values.add("0");
        }
        if (!keys.contains("hinge") && type.endsWith("_door")) {
            keys.add("hinge");
            values.add("left");
        }

        String a = axis == 'x' ? "east" : "north";
        String b = axis == 'x' ? "west" : "south";
        List<String> out = new ArrayList<>(values);
        for (int i = 0; i < keys.size(); i++) {
            String v = values.get(i);
            switch (keys.get(i)) {
                case "facing" -> out.set(i, swap(v, a, b));
                case "hinge" -> out.set(i, swap(v, "left", "right"));
                case "type" -> {
                    if (type.endsWith("chest")) out.set(i, swap(v, "left", "right"));
                }
                case "shape" -> {
                    if (type.endsWith("_stairs")) out.set(i, handed(v));
                    else if (type.endsWith("rail")) out.set(i, swapWords(v, a, b));
                }
                case "rotation" -> {
                    if (v.matches("\\d{1,2}")) out.set(i, String.valueOf(Math.floorMod((axis == 'x' ? 16 : 24) - Integer.parseInt(v), 16)));
                }
                default -> {
                    // Sides (fences, panes, walls, vines, redstone) trade values; a side left at its default is absent.
                    String key = keys.get(i);
                    if (key.equals(a) || key.equals(b)) {
                        int other = keys.indexOf(key.equals(a) ? b : a);
                        if (other >= 0) out.set(i, values.get(other));
                        else keys.set(i, key.equals(a) ? b : a);
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i >= given && out.get(i).equals(values.get(i))) continue; // a default the mirror kept
            sb.append(sb.length() == 0 ? "" : ",").append(keys.get(i)).append('=').append(out.get(i));
        }
        if (sb.length() == 0) return open < 0 ? state : name;
        return name + "[" + sb + "]" + (open < 0 ? "" : state.substring(close + 1));
    }

    private static boolean endsWithAny(String type, Iterable<String> suffixes) {
        for (String s : suffixes) {
            if (type.endsWith(s)) return true;
        }
        return false;
    }

    private static String swap(String v, String a, String b) {
        return v.equals(a) ? b : v.equals(b) ? a : v;
    }

    /** Stair corners: mirroring turns a left-hand corner into a right-hand one. */
    private static String handed(String v) {
        if (v.endsWith("_left")) return v.substring(0, v.length() - 5) + "_right";
        if (v.endsWith("_right")) return v.substring(0, v.length() - 6) + "_left";
        return v;
    }

    /** Rail shapes name directions as words: {@code ascending_east}, {@code north_west}. */
    private static String swapWords(String v, String a, String b) {
        String[] parts = v.split("_");
        for (int i = 0; i < parts.length; i++) parts[i] = swap(parts[i], a, b);
        return String.join("_", parts);
    }
}
