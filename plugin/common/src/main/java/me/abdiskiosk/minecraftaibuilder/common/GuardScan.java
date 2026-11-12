package me.abdiskiosk.minecraftaibuilder.common;

import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Finds where in a box a region plugin forbids building, asking it once per distinct set of regions instead of once
 * per block. Whether a point is buildable depends only on which regions contain it, and that is the same everywhere
 * inside a cell cut by the regions' bounds: on y for every region, on x and z for cuboids. Other shapes (polygons) are
 * cut into single columns over their bounds.
 */
public final class GuardScan {

    /** Most cells one box may be cut into before it is refused as too costly to check. */
    public static final long MAX_CELLS = 4_000_000;

    /** A region that touches the box. */
    public interface Shape {
        Box bounds();

        boolean cuboid();

        boolean contains(int x, int y, int z);
    }

    private static final String ALLOWED = "";

    private GuardScan() {
    }

    /**
     * @param shapes   the regions that touch {@code box}, not counting the world-wide one
     * @param deniedAt the name of the region that forbids building at a point, or null where building is allowed
     * @param limit    stop after this many names
     * @return the names, in the order found
     */
    public static List<String> denied(Box box, List<? extends Shape> shapes, Function<Vec3, String> deniedAt, int limit,
                                      long maxCells) {
        int[] xs = cuts(box.min().x(), box.max().x(), shapes, 0);
        int[] ys = cuts(box.min().y(), box.max().y(), shapes, 1);
        int[] zs = cuts(box.min().z(), box.max().z(), shapes, 2);
        long cells = (long) xs.length * ys.length * zs.length;
        if (cells > maxCells) {
            throw new EditException("edit " + box + " crosses too many WorldGuard region edges to check ("
                    + cells + " cells); split it into smaller boxes");
        }
        Map<BitSet, String> seen = new HashMap<>();
        Set<String> names = new LinkedHashSet<>();
        for (int x : xs) {
            for (int z : zs) {
                for (int y : ys) {
                    BitSet in = new BitSet(shapes.size());
                    for (int i = 0; i < shapes.size(); i++) {
                        Shape s = shapes.get(i);
                        if (inside(s.bounds(), x, y, z) && s.contains(x, y, z)) in.set(i);
                    }
                    String name = seen.get(in);
                    if (name == null) {
                        String d = deniedAt.apply(new Vec3(x, y, z));
                        name = d == null ? ALLOWED : d;
                        seen.put(in, name);
                        if (d != null) {
                            names.add(d);
                            if (names.size() >= limit) return List.copyOf(names);
                        }
                    }
                }
            }
        }
        return List.copyOf(names);
    }

    /** The first coordinate of every cell along one axis of [lo, hi]. */
    private static int[] cuts(int lo, int hi, List<? extends Shape> shapes, int axis) {
        TreeSet<Integer> at = new TreeSet<>();
        at.add(lo);
        for (Shape s : shapes) {
            int min = coord(s.bounds().min(), axis), max = coord(s.bounds().max(), axis);
            if (axis == 1 || s.cuboid()) {
                at.add(min);
            } else {
                for (int c = Math.max(min, lo); c <= Math.min(max, hi); c++) at.add(c);
            }
            if (max < Integer.MAX_VALUE) at.add(max + 1);
        }
        return at.subSet(lo, true, hi, true).stream().mapToInt(Integer::intValue).toArray();
    }

    private static int coord(Vec3 v, int axis) {
        return axis == 0 ? v.x() : axis == 1 ? v.y() : v.z();
    }

    private static boolean inside(Box b, int x, int y, int z) {
        return x >= b.min().x() && x <= b.max().x() && y >= b.min().y() && y <= b.max().y()
                && z >= b.min().z() && z <= b.max().z();
    }
}
