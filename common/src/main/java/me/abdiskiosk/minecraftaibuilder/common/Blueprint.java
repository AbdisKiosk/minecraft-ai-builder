package me.abdiskiosk.minecraftaibuilder.common;

import java.util.List;
import java.util.Map;

/**
 * Compact structure format: a palette of single-character keys and {@code layers[y][z]} row strings.
 *
 * <p>Each row character is a palette key for one block along +X. A count before a key repeats it
 * ({@code "4S2."} = SSSS..). {@code '.'} and {@code ' '} mean "leave the existing block". Air must be
 * explicit, e.g. {@code "_": "air"}. Palette values are WorldEdit patterns, so {@code "70%stone_bricks,30%cobblestone"} works.
 */
public final class Blueprint {

    public static final char SKIP = 0;

    private final Map<Character, String> palette;
    private final char[][][] grid; // [y][z][x]
    private final int sizeX;
    private final long cellCount;

    private Blueprint(Map<Character, String> palette, char[][][] grid, int sizeX, long cellCount) {
        this.palette = palette;
        this.grid = grid;
        this.sizeX = sizeX;
        this.cellCount = cellCount;
    }

    public static Blueprint of(Map<Character, String> palette, List<List<String>> layers) {
        if (layers.isEmpty()) {
            throw new EditException("blueprint has no layers");
        }
        for (char key : palette.keySet()) {
            if (Character.isDigit(key) || key == '.' || key == ' ') {
                throw new EditException("palette key '" + key + "' is reserved (digits, '.' and ' ')");
            }
        }
        int sizeZ = layers.stream().mapToInt(List::size).max().orElse(0);
        char[][][] grid = new char[layers.size()][sizeZ][];
        int sizeX = 0;
        long cells = 0;
        for (int y = 0; y < layers.size(); y++) {
            List<String> rows = layers.get(y);
            for (int z = 0; z < sizeZ; z++) {
                char[] row = z < rows.size() ? expand(rows.get(z), palette, y, z) : new char[0];
                grid[y][z] = row;
                sizeX = Math.max(sizeX, row.length);
                for (char c : row) {
                    if (c != SKIP) cells++;
                }
            }
        }
        return new Blueprint(Map.copyOf(palette), grid, sizeX, cells);
    }

    private static char[] expand(String row, Map<Character, String> palette, int y, int z) {
        StringBuilder out = new StringBuilder(row.length());
        int count = 0;
        boolean counting = false;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (Character.isDigit(c)) {
                count = count * 10 + (c - '0');
                counting = true;
                if (count > 4096) throw new EditException("run length too large in layer " + y + " row " + z);
                continue;
            }
            char key;
            if (c == '.' || c == ' ') {
                key = SKIP;
            } else if (palette.containsKey(c)) {
                key = c;
            } else {
                throw new EditException("unknown palette key '" + c + "' in layer " + y + " row " + z);
            }
            int n = counting ? count : 1;
            for (int k = 0; k < n; k++) out.append(key);
            count = 0;
            counting = false;
        }
        if (counting) throw new EditException("row ends with a count in layer " + y + " row " + z);
        return out.toString().toCharArray();
    }

    /** Layers {@code fromY} (inclusive) to {@code toY} (exclusive); keeps the footprint so rotated pieces line up. */
    public Blueprint slice(int fromY, int toY) {
        char[][][] part = java.util.Arrays.copyOfRange(grid, fromY, toY);
        long cells = 0;
        for (char[][] layer : part) {
            for (char[] row : layer) {
                for (char c : row) {
                    if (c != SKIP) cells++;
                }
            }
        }
        return new Blueprint(palette, part, sizeX, cells);
    }

    public int sizeX() { return sizeX; }
    public int sizeY() { return grid.length; }
    public int sizeZ() { return grid.length == 0 ? 0 : grid[0].length; }

    /** Number of cells that place a block (excludes skipped cells). */
    public long cellCount() { return cellCount; }

    public Map<Character, String> palette() { return palette; }

    /** Palette key at the position, or {@link #SKIP}. */
    public char at(int x, int y, int z) {
        char[] row = grid[y][z];
        return x < row.length ? row[x] : SKIP;
    }

    public interface CellVisitor {
        void visit(int x, int y, int z, String pattern);
    }

    public void forEachCell(CellVisitor visitor) {
        for (int y = 0; y < sizeY(); y++) {
            for (int z = 0; z < sizeZ(); z++) {
                char[] row = grid[y][z];
                for (int x = 0; x < row.length; x++) {
                    if (row[x] != SKIP) visitor.visit(x, y, z, palette.get(row[x]));
                }
            }
        }
    }
}
