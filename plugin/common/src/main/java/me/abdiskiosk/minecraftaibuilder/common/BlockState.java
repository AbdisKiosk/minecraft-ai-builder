package me.abdiskiosk.minecraftaibuilder.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** A modern block state such as {@code oak_stairs[facing=east,half=top]}, without namespace. */
public record BlockState(String type, Map<String, String> props) {

    public BlockState {
        props = Collections.unmodifiableMap(new LinkedHashMap<>(props));
    }

    public static BlockState parse(String input) {
        String s = input.trim();
        String body = s;
        Map<String, String> props = new LinkedHashMap<>();
        int open = s.indexOf('[');
        if (open >= 0) {
            int close = s.lastIndexOf(']');
            if (close < open) throw new EditException("missing ']' in block state '" + input + "'");
            if (!s.substring(close + 1).isBlank()) {
                throw new EditException("unexpected '" + s.substring(close + 1).trim() + "' after ']' in '" + input
                        + "'. Block entity data (sign text, heads, ...) goes in the data object, not in the state string");
            }
            body = s.substring(0, open);
            for (String pair : BlockSyntax.splitTopLevel(s.substring(open + 1, close), ',')) {
                if (pair.isBlank()) continue;
                int eq = pair.indexOf('=');
                if (eq <= 0 || eq == pair.length() - 1) {
                    throw new EditException("bad property '" + pair.trim() + "' in '" + input + "', expected name=value");
                }
                props.put(pair.substring(0, eq).trim().toLowerCase(Locale.ROOT), pair.substring(eq + 1).trim().toLowerCase(Locale.ROOT));
            }
        }
        body = body.trim().toLowerCase(Locale.ROOT);
        if (body.startsWith("minecraft:")) body = body.substring("minecraft:".length());
        if (body.isEmpty()) throw new EditException("empty block name in '" + input + "'");
        return new BlockState(body, props);
    }

    @Override
    public String toString() {
        if (props.isEmpty()) return type;
        StringBuilder sb = new StringBuilder(type).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : props.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }
}
