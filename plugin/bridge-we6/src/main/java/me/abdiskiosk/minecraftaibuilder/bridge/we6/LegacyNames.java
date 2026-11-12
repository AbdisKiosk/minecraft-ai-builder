package me.abdiskiosk.minecraftaibuilder.bridge.we6;

import me.abdiskiosk.minecraftaibuilder.common.BlockState;
import me.abdiskiosk.minecraftaibuilder.common.BlockSyntax;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import org.bukkit.Material;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Translates modern block names and states ({@code stone_bricks}, {@code oak_stairs[facing=east,half=top]}) into
 * the numeric {@code id:data} syntax WorldEdit 6 understands, and legacy ids back into modern states.
 * Always emits numbers, so WorldEdit 6's own (fuzzy) name lookup is never involved.
 */
final class LegacyNames {

    private static LegacyBlockTable table;

    private LegacyNames() {
    }

    static synchronized LegacyBlockTable table() {
        if (table == null) table = load(LegacyNames::available);
        return table;
    }

    static LegacyBlockTable load(IntPredicate available) {
        try (InputStream in = LegacyNames.class.getResourceAsStream("legacy-blocks.txt")) {
            if (in == null) throw new IllegalStateException("legacy-blocks.txt is missing from the plugin jar");
            return LegacyBlockTable.load(in, available);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether the running server has a block with this id; 1.9+ blocks do not exist on 1.8. */
    @SuppressWarnings("deprecation")
    private static boolean available(int id) {
        Material material = Material.getMaterial(id);
        return material != null && material.isBlock();
    }

    /** Translates a WorldEdit pattern or mask such as {@code 70%stone_bricks,30%cracked_stone_bricks}. */
    static String translate(String input, boolean mask) {
        return mask ? translateMask(input) : translatePattern(input);
    }

    private static String translatePattern(String input) {
        StringBuilder out = new StringBuilder();
        for (BlockSyntax.Token token : BlockSyntax.tokens(input)) {
            if (out.length() > 0) out.append(',');
            out.append(token.prefix()).append(token.plainBlock() ? place(token.body()) : token.body());
        }
        return out.toString();
    }

    private static String place(String block) {
        BlockState state = BlockState.parse(block);
        int[] legacy = table().toLegacy(state.type(), state.props()).orElseThrow(() -> unknown(state.type()));
        return legacy[0] + ":" + legacy[1];
    }

    /**
     * Masks match every variant of what was asked for: {@code oak_log} is all four axis values, never other species
     * (a bare numeric id would match every log type).
     */
    private static String translateMask(String input) {
        List<String> components = new ArrayList<>();
        for (String component : BlockSyntax.maskComponents(input)) components.add(maskComponent(component));
        return String.join(" ", components);
    }

    private static String maskComponent(String component) {
        int start = 0;
        while (start < component.length() && "!<>".indexOf(component.charAt(start)) >= 0) start++;
        String rest = component.substring(start);
        if (rest.isEmpty() || "#%=$/".indexOf(rest.charAt(0)) >= 0) return component; // WorldEdit's own masks
        StringBuilder out = new StringBuilder(component.substring(0, start));
        boolean first = true;
        for (BlockSyntax.Token token : BlockSyntax.tokens(rest)) {
            if (!first) out.append(',');
            first = false;
            if (!token.plainBlock()) {
                out.append(token.body());
                continue;
            }
            BlockState state = BlockState.parse(token.body());
            List<int[]> matches = table().matching(state.type(), state.props());
            if (matches.isEmpty()) throw unknown(state.type());
            for (int i = 0; i < matches.size(); i++) {
                out.append(i == 0 ? "" : ",").append(matches.get(i)[0]).append(':').append(matches.get(i)[1]);
            }
        }
        return out.toString();
    }

    private static EditException unknown(String type) {
        return new EditException("unknown block '" + type + "'. Closest: " + String.join(", ", table().suggest(type, 4)));
    }

    /** Modern state for a legacy id/data pair, e.g. {@code oak_stairs[facing=east,half=top]}. */
    static String modern(int id, int data) {
        String state = table().toModern(id, data);
        return state != null ? state : "legacy_" + id + "_" + data;
    }
}
