package me.abdiskiosk.minecraftaibuilder.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns raw block arrays into token-cheap summaries, or into the {@link Blueprint} text format. */
public final class RegionCodec {

    /** ASCII first, since those are the cheapest to read; Greek and Cyrillic letters carry regions with many states. */
    private static final String KEYS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%&*+-=@!?~<>/|:;"
            + "αβγδεζηθικλμνξοπρστυφχψωΓΔΘΛΞΠΣΦΨΩ"
            + "бвгджзийклмнптфцчшщъыьэюяБГДЖЗИЙЛПФЦЧШЩЪЫЬЭЮЯ";
    public static final char AIR_KEY = '_';

    private RegionCodec() {
    }

    public static int index(Box box, int x, int y, int z) {
        return ((y - box.min().y()) * box.sizeZ() + (z - box.min().z())) * box.sizeX() + (x - box.min().x());
    }

    /** The states of {@code inner}, which must lie inside {@code box}, in the same y, z, x order. */
    public static String[] crop(Box box, String[] states, Box inner) {
        String[] out = new String[(int) inner.volume()];
        int i = 0;
        for (int y = inner.min().y(); y <= inner.max().y(); y++) {
            for (int z = inner.min().z(); z <= inner.max().z(); z++) {
                for (int x = inner.min().x(); x <= inner.max().x(); x++) {
                    out[i++] = states[index(box, x, y, z)];
                }
            }
        }
        return out;
    }

    public static boolean isAir(String state) {
        return state == null || state.equals("air") || state.equals("cave_air") || state.equals("void_air");
    }

    public record Summary(Box box, long volume, long nonAir, Box nonAirBounds, Map<String, Long> counts,
                          Integer surfaceMin, Integer surfaceMax, List<String> heightmap) {
    }

    /**
     * @param topCounts   how many of the most common block types to list
     * @param withHeights include per-column surface heights (relative to box min y, -1 = empty column)
     */
    public static Summary summarize(Box box, String[] states, int topCounts, boolean withHeights) {
        Map<String, Long> counts = new HashMap<>();
        long nonAir = 0;
        Vec3 lo = null, hi = null;
        int[][] top = new int[box.sizeZ()][box.sizeX()];
        for (int[] row : top) java.util.Arrays.fill(row, -1);

        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    String s = states[index(box, x, y, z)];
                    String type = stripProperties(s);
                    counts.merge(type == null ? "air" : type, 1L, Long::sum);
                    if (isAir(type)) continue;
                    nonAir++;
                    Vec3 p = new Vec3(x, y, z);
                    lo = lo == null ? p : Vec3.min(lo, p);
                    hi = hi == null ? p : Vec3.max(hi, p);
                    top[z - box.min().z()][x - box.min().x()] = y - box.min().y();
                }
            }
        }

        Map<String, Long> sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(topCounts)
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));

        Integer sMin = null, sMax = null;
        List<String> heights = withHeights ? new ArrayList<>() : null;
        for (int[] row : top) {
            StringBuilder line = withHeights ? new StringBuilder() : null;
            for (int i = 0; i < row.length; i++) {
                int h = row[i];
                if (h >= 0) {
                    int abs = h + box.min().y();
                    sMin = sMin == null ? abs : Math.min(sMin, abs);
                    sMax = sMax == null ? abs : Math.max(sMax, abs);
                }
                if (line != null) line.append(i == 0 ? "" : ",").append(h);
            }
            if (heights != null) heights.add(line.toString());
        }
        return new Summary(box, box.volume(), nonAir, lo == null ? null : Box.of(lo, hi), sorted, sMin, sMax, heights);
    }

    /**
     * Palette + RLE layers, directly pasteable as a {@link Blueprint}. Air is written as {@value #AIR_KEY}.
     * A row equal to the one before it is written as a repeat marker ({@code "^"}, {@code "3^"}), and so is a
     * layer equal to the one below it ({@code ["^"]}), so walls and floors cost one row instead of one per block.
     */
    public record Encoded(Map<Character, String> palette, List<List<String>> layers) {
    }

    public static Encoded encode(Box box, String[] states) {
        Map<String, Character> keys = new HashMap<>();
        Map<Character, String> palette = new LinkedHashMap<>();
        List<List<String>> layers = new ArrayList<>();
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            List<String> rows = new ArrayList<>();
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                StringBuilder row = new StringBuilder();
                char prev = 0;
                int run = 0;
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    String s = states[index(box, x, y, z)];
                    char key = keyFor(isAir(s) ? "air" : s, keys, palette);
                    if (key == prev) {
                        run++;
                    } else {
                        appendRun(row, prev, run);
                        prev = key;
                        run = 1;
                    }
                }
                appendRun(row, prev, run);
                rows.add(row.toString());
            }
            layers.add(rows);
        }
        return new Encoded(palette, packLayers(layers));
    }

    private static List<List<String>> packLayers(List<List<String>> layers) {
        List<List<String>> out = new ArrayList<>();
        int run = 0;
        for (int y = 0; y < layers.size(); y++) {
            if (y > 0 && layers.get(y).equals(layers.get(y - 1))) {
                run++;
                continue;
            }
            if (run > 0) out.add(List.of(repeat(run)));
            run = 0;
            out.add(packRows(layers.get(y)));
        }
        if (run > 0) out.add(List.of(repeat(run)));
        return out;
    }

    private static List<String> packRows(List<String> rows) {
        List<String> out = new ArrayList<>();
        int run = 0;
        for (int z = 0; z < rows.size(); z++) {
            if (z > 0 && rows.get(z).equals(rows.get(z - 1))) {
                run++;
                continue;
            }
            if (run > 0) out.add(repeat(run));
            run = 0;
            out.add(rows.get(z));
        }
        if (run > 0) out.add(repeat(run));
        return out;
    }

    private static String repeat(int run) {
        return (run == 1 ? "" : String.valueOf(run)) + Blueprint.REPEAT;
    }

    private static char keyFor(String state, Map<String, Character> keys, Map<Character, String> palette) {
        Character existing = keys.get(state);
        if (existing != null) return existing;
        char key;
        if (state.equals("air")) {
            key = AIR_KEY;
        } else {
            int used = (int) palette.keySet().stream().filter(c -> c != AIR_KEY).count();
            if (used >= KEYS.length()) {
                throw new EditException("region has more than " + KEYS.length() + " distinct block states; read a smaller region");
            }
            key = KEYS.charAt(used);
        }
        keys.put(state, key);
        palette.put(key, state);
        return key;
    }

    private static void appendRun(StringBuilder row, char key, int run) {
        if (run == 0) return;
        if (run >= 3) {
            row.append(run).append(key);
        } else {
            for (int i = 0; i < run; i++) row.append(key);
        }
    }

    public static String stripProperties(String state) {
        if (state == null) return null;
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }
}
