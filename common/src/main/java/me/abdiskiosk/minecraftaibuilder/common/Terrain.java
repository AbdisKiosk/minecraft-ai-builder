package me.abdiskiosk.minecraftaibuilder.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Fills that follow the ground of a region: the surface of a column is its highest {@link BlockTraits#ground}
 * block inside the region. States are in {@link RegionCodec#index} order.
 */
public final class Terrain {

    private Terrain() {
    }

    /**
     * Fills every column from just above its ground up to the region's top, the level just under a build;
     * plants, water and air in between are replaced. A column without ground is filled completely.
     */
    public static List<Op> foundation(Box region, String[] states, String pattern) {
        int minY = region.min().y(), maxY = region.max().y();
        return fills(region, states, pattern, top -> new int[]{top < minY ? minY : top + 1, maxY});
    }

    /**
     * With {@code raise == 0}, replaces the top {@code depth} blocks of each column's ground; with {@code raise > 0},
     * places that many blocks on top of it instead. Columns without ground are left alone.
     */
    public static List<Op> overlay(Box region, String[] states, String pattern, int depth, int raise) {
        int minY = region.min().y(), maxY = region.max().y();
        return fills(region, states, pattern, top -> top < minY ? null
                : raise > 0 ? new int[]{top + 1, Math.min(maxY, top + raise)}
                : new int[]{Math.max(minY, top - depth + 1), top});
    }

    /**
     * One fill per run of neighbouring columns with the same y range along x, merged with identical runs in
     * the z rows next to it, so flat ground costs a few ops instead of one per column.
     *
     * @param range y range to fill for a column, given its surface y ({@code min.y - 1} without ground); null or
     *              empty for nothing
     */
    private static List<Op> fills(Box region, String[] states, String pattern, IntFunction<int[]> range) {
        List<Op> out = new ArrayList<>();
        Map<List<Integer>, Box> open = new LinkedHashMap<>();
        for (int z = region.min().z(); z <= region.max().z(); z++) {
            Map<List<Integer>, Box> row = new LinkedHashMap<>();
            int runStart = 0;
            int[] run = null;
            for (int x = region.min().x(); x <= region.max().x() + 1; x++) {
                int[] r = x > region.max().x() ? null : range.apply(surface(region, states, x, z));
                if (r != null && r[0] > r[1]) r = null;
                if (run != null && (r == null || r[0] != run[0] || r[1] != run[1])) {
                    List<Integer> key = List.of(runStart, x - 1, run[0], run[1]);
                    Box above = open.remove(key);
                    row.put(key, Box.of(new Vec3(runStart, run[0], above == null ? z : above.min().z()), new Vec3(x - 1, run[1], z)));
                    run = null;
                }
                if (r != null && run == null) {
                    run = r;
                    runStart = x;
                }
            }
            open.values().forEach(b -> out.add(new Op.Fill(b, pattern)));
            open = row;
        }
        open.values().forEach(b -> out.add(new Op.Fill(b, pattern)));
        return out;
    }

    /** Highest ground y in the column, or {@code min.y - 1}. */
    private static int surface(Box region, String[] states, int x, int z) {
        for (int y = region.max().y(); y >= region.min().y(); y--) {
            if (BlockTraits.ground(states[RegionCodec.index(region, x, y, z)])) return y;
        }
        return region.min().y() - 1;
    }
}
