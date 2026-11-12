package me.abdiskiosk.minecraftaibuilder.common;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Splits WorldEdit pattern and mask strings without breaking on the commas inside {@code [...]}. */
public final class BlockSyntax {

    private static final Pattern WEIGHT = Pattern.compile("^\\d+(\\.\\d+)?%");
    private static final Pattern PLAIN_BLOCK = Pattern.compile("^[A-Za-z_][A-Za-z0-9_:]*(\\[.*)?$");

    private BlockSyntax() {
    }

    /** One comma-separated pattern entry: {@code prefix} ("70%", "!", ">") then {@code body} ("stone_bricks"). */
    public record Token(String prefix, String body) {
        /** True for a block name with optional state (even a malformed one, so it is reported); false for numeric ids, {@code #existing}, {@code ^[facing]}, ... */
        public boolean plainBlock() {
            return PLAIN_BLOCK.matcher(body).matches();
        }
    }

    /** Splits on {@code sep} outside of square and curly brackets. */
    public static List<String> splitTopLevel(String s, char sep) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth = Math.max(0, depth - 1);
            } else if (c == sep && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    /** Comma-separated entries of a pattern, e.g. {@code 70%stone_bricks,30%oak_stairs[facing=east,half=top]}. */
    public static List<Token> tokens(String patternOrMask) {
        List<Token> out = new ArrayList<>();
        for (String raw : splitTopLevel(patternOrMask, ',')) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            int start = 0;
            var weight = WEIGHT.matcher(t);
            if (weight.find()) start = weight.end();
            while (start < t.length() && (t.charAt(start) == '!' || t.charAt(start) == '>' || t.charAt(start) == '<')) start++;
            out.add(new Token(t.substring(0, start), t.substring(start)));
        }
        return out;
    }

    /** Space-separated mask components ({@code stone,dirt !air}); WorldEdit ANDs them. */
    public static List<String> maskComponents(String mask) {
        List<String> out = new ArrayList<>();
        for (String c : splitTopLevel(mask, ' ')) {
            if (!c.isBlank()) out.add(c.trim());
        }
        return out;
    }
}
