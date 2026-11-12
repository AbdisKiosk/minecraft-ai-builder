package me.abdiskiosk.minecraftaibuilder.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * The block states of a few boxes, read a slice at a time so a big read can be spread over server ticks.
 * States are kept as palette indexes (4 bytes a block) rather than strings.
 */
public final class RegionSnapshot {

    private final List<Box> boxes;
    private final int[][] data;
    private final List<String> palette = new ArrayList<>();
    private final Map<String, Integer> paletteIndex = new HashMap<>();
    private int box;
    /** Next row (one x line) of the current box, counted in {@link RegionCodec#index} order. */
    private long row;

    /** @param boxes must not overlap; see {@link #merge} */
    public RegionSnapshot(List<Box> boxes) {
        this.boxes = List.copyOf(boxes);
        this.data = new int[boxes.size()][];
        for (int i = 0; i < boxes.size(); i++) {
            long volume = boxes.get(i).volume();
            if (volume > Integer.MAX_VALUE) throw new EditException("snapshot box too large: " + boxes.get(i));
            data[i] = new int[(int) volume];
        }
    }

    /** An unread snapshot of the same boxes. */
    public RegionSnapshot fresh() {
        return new RegionSnapshot(boxes);
    }

    public List<Box> boxes() {
        return boxes;
    }

    public long volume() {
        return volume(boxes);
    }

    public static long volume(List<Box> boxes) {
        long v = 0;
        for (Box b : boxes) v += b.volume();
        return v;
    }

    public boolean done() {
        return box >= boxes.size();
    }

    /**
     * Reads about {@code budget} more blocks (at least one x line).
     *
     * @return true once everything is read
     */
    public boolean step(WorldEditBridge bridge, String world, long budget) {
        long left = Math.max(1, budget);
        while (!done() && left > 0) {
            Box b = boxes.get(box);
            int sx = b.sizeX(), sz = b.sizeZ();
            long rows = (long) b.sizeY() * sz;
            int layer = (int) (row / sz), zInLayer = (int) (row % sz);
            long wantRows = Math.max(1, left / sx);
            Box part;
            long took;
            if (zInLayer == 0 && wantRows >= sz) {
                int layers = (int) Math.min(b.sizeY() - layer, wantRows / sz);
                part = new Box(new Vec3(b.min().x(), b.min().y() + layer, b.min().z()),
                        new Vec3(b.max().x(), b.min().y() + layer + layers - 1, b.max().z()));
                took = (long) layers * sz;
            } else {
                int n = (int) Math.min(sz - zInLayer, wantRows);
                int y = b.min().y() + layer;
                part = new Box(new Vec3(b.min().x(), y, b.min().z() + zInLayer), new Vec3(b.max().x(), y, b.min().z() + zInLayer + n - 1));
                took = n;
            }
            String[] states = bridge.read(world, part);
            int offset = (int) (row * sx);
            for (int i = 0; i < states.length; i++) data[box][offset + i] = index(states[i]);
            row += took;
            left -= took * sx;
            if (row >= rows) {
                box++;
                row = 0;
            }
        }
        return done();
    }

    /** Reads everything now (for FastAsyncWorldEdit, which may read off the main thread). */
    public void readAll(WorldEditBridge bridge, String world, long slice) {
        while (!step(bridge, world, slice)) {
            // keep reading
        }
    }

    private int index(String state) {
        String s = RegionCodec.isAir(state) ? "air" : state;
        Integer i = paletteIndex.get(s);
        if (i != null) return i;
        palette.add(s);
        paletteIndex.put(s, palette.size() - 1);
        return palette.size() - 1;
    }

    /** States of box {@code i} by {@link RegionCodec#index}. */
    public IntFunction<String> states(int i) {
        int[] d = data[i];
        return k -> palette.get(d[k]);
    }

    /** All states of box {@code i}; the strings are shared, so this costs one reference per block. */
    public String[] array(int i) {
        String[] out = new String[data[i].length];
        for (int k = 0; k < out.length; k++) out[k] = palette.get(data[i][k]);
        return out;
    }

    /** The state at {@code p}, or null when it is outside every box. */
    public String get(Vec3 p) {
        for (int i = 0; i < boxes.size(); i++) {
            Box b = boxes.get(i);
            if (contains(b, p)) return palette.get(data[i][RegionCodec.index(b, p.x(), p.y(), p.z())]);
        }
        return null;
    }

    /** Before/after reads of the same boxes as a diff. */
    public static BlockDiff diff(RegionSnapshot before, RegionSnapshot after) {
        BlockDiff.Builder diff = new BlockDiff.Builder();
        for (int i = 0; i < before.boxes.size(); i++) diff.add(before.boxes.get(i), before.states(i), after.states(i));
        return diff.build();
    }

    /** Joins boxes that overlap or touch into their bounding boxes until none do. */
    public static List<Box> merge(List<Box> input) {
        List<Box> boxes = new ArrayList<>(input);
        boolean merged = true;
        while (merged) {
            merged = false;
            outer:
            for (int i = 0; i < boxes.size(); i++) {
                for (int j = i + 1; j < boxes.size(); j++) {
                    if (touch(boxes.get(i), boxes.get(j))) {
                        Box a = boxes.get(i), b = boxes.get(j);
                        boxes.set(i, Box.of(Vec3.min(a.min(), b.min()), Vec3.max(a.max(), b.max())));
                        boxes.remove(j);
                        merged = true;
                        break outer;
                    }
                }
            }
        }
        return boxes;
    }

    /** {@code box} cut to y {@code minY..maxY}, or null when nothing is left. */
    public static Box clipY(Box box, int minY, int maxY) {
        int lo = Math.max(minY, box.min().y()), hi = Math.min(maxY, box.max().y());
        return lo > hi ? null : box.withY(lo, hi);
    }

    private static boolean touch(Box a, Box b) {
        return a.min().x() <= b.max().x() + 1 && b.min().x() <= a.max().x() + 1
                && a.min().y() <= b.max().y() + 1 && b.min().y() <= a.max().y() + 1
                && a.min().z() <= b.max().z() + 1 && b.min().z() <= a.max().z() + 1;
    }

    private static boolean contains(Box b, Vec3 p) {
        return p.x() >= b.min().x() && p.x() <= b.max().x() && p.y() >= b.min().y() && p.y() <= b.max().y()
                && p.z() >= b.min().z() && p.z() <= b.max().z();
    }
}
