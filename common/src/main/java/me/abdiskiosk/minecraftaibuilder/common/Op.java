package me.abdiskiosk.minecraftaibuilder.common;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A build operation with coordinates already resolved to absolute world positions.
 * Patterns and masks use WorldEdit syntax, e.g. {@code 70%stone_bricks,30%cracked_stone_bricks}.
 */
public sealed interface Op {

    /** Upper bound of blocks the op can touch; used for limits and tick budgeting. */
    long volume();

    Box bounds();

    /** Splits the op into pieces of roughly {@code budget} blocks that give the same result when applied in order. */
    default List<Op> split(long budget) {
        return List.of(this);
    }

    /** Block patterns this op places (WorldEdit syntax); checked against the server's blocks before anything is built. */
    default List<String> patterns() {
        return List.of();
    }

    /** Masks this op matches against (WorldEdit syntax). */
    default List<String> masks() {
        return List.of();
    }

    /** Lower-case op name for messages, e.g. "fill". */
    default String kind() {
        return getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }

    record Fill(Box box, String pattern) implements Op {
        public long volume() { return box.volume(); }
        public Box bounds() { return box; }
        public List<Op> split(long budget) { return slabs(box, budget).stream().<Op>map(b -> new Fill(b, pattern)).toList(); }
        public List<String> patterns() { return List.of(pattern); }
    }

    /** The four vertical sides of the box. */
    record Walls(Box box, String pattern) implements Op {
        public long volume() { return box.volume(); }
        public Box bounds() { return box; }
        public List<Op> split(long budget) { return slabs(box, budget).stream().<Op>map(b -> new Walls(b, pattern)).toList(); }
        public List<String> patterns() { return List.of(pattern); }
    }

    /** All six faces of the box. */
    record Outline(Box box, String pattern) implements Op {
        public long volume() { return box.volume(); }
        public Box bounds() { return box; }
        public List<String> patterns() { return List.of(pattern); }
        /** Walls in slabs plus the bottom and top layers: the same six faces. */
        public List<Op> split(long budget) {
            if (volume() <= budget) return List.of(this);
            List<Op> parts = new ArrayList<>(new Walls(box, pattern).split(budget));
            for (Box layer : new Box[]{box.withY(box.min().y(), box.min().y()), box.withY(box.max().y(), box.max().y())}) {
                parts.addAll(new Fill(layer, pattern).split(budget));
            }
            return parts;
        }
    }

    record Replace(Box box, String from, String pattern) implements Op {
        public long volume() { return box.volume(); }
        public Box bounds() { return box; }
        public List<Op> split(long budget) { return slabs(box, budget).stream().<Op>map(b -> new Replace(b, from, pattern)).toList(); }
        public List<String> patterns() { return List.of(pattern); }
        public List<String> masks() { return List.of(from); }
    }

    /** Cylinder standing on {@code base}, growing upwards {@code height} blocks. */
    record Cylinder(Vec3 base, String pattern, double radiusX, double radiusZ, int height, boolean hollow) implements Op {
        public long volume() { return (long) Math.ceil(Math.PI * (radiusX + 1) * (radiusZ + 1) * Math.max(1, height)); }
        public Box bounds() {
            int rx = (int) Math.ceil(radiusX), rz = (int) Math.ceil(radiusZ);
            return Box.of(base.add(-rx, 0, -rz), base.add(rx, Math.max(1, height) - 1, rz));
        }
        public List<String> patterns() { return List.of(pattern); }
        /** A cylinder is the same shape on every layer, so stacking shorter cylinders gives the same result. */
        public List<Op> split(long budget) {
            if (volume() <= budget) return List.of(this);
            long perLayer = Math.max(1, (long) Math.ceil(Math.PI * (radiusX + 1) * (radiusZ + 1)));
            int step = (int) Math.max(1, Math.min(Math.max(1, height), budget / perLayer));
            List<Op> parts = new ArrayList<>();
            for (int y = 0; y < Math.max(1, height); y += step) {
                parts.add(new Cylinder(base.add(0, y, 0), pattern, radiusX, radiusZ, Math.min(step, Math.max(1, height) - y), hollow));
            }
            return parts;
        }
    }

    record Sphere(Vec3 center, String pattern, double radiusX, double radiusY, double radiusZ, boolean hollow) implements Op {
        public long volume() { return (long) Math.ceil(4.0 / 3 * Math.PI * (radiusX + 1) * (radiusY + 1) * (radiusZ + 1)); }
        public Box bounds() {
            int rx = (int) Math.ceil(radiusX), ry = (int) Math.ceil(radiusY), rz = (int) Math.ceil(radiusZ);
            return Box.of(center.add(-rx, -ry, -rz), center.add(rx, ry, rz));
        }
        public List<String> patterns() { return List.of(pattern); }
    }

    /** Pyramid with its base centred on {@code base}. */
    record Pyramid(Vec3 base, String pattern, int size, boolean hollow) implements Op {
        public long volume() { return (long) size * size * size * 4 / 3 + 1; }
        public Box bounds() { return Box.of(base.add(-size, 0, -size), base.add(size, size, size)); }
        public List<String> patterns() { return List.of(pattern); }
    }

    record Line(Vec3 from, Vec3 to, String pattern, double thickness) implements Op {
        public long volume() {
            double len = Math.sqrt(sq(to.x() - from.x()) + sq(to.y() - from.y()) + sq(to.z() - from.z())) + 1;
            double t = Math.max(1, thickness * 2 + 1);
            return (long) Math.ceil(len * t * t);
        }
        public Box bounds() {
            int t = (int) Math.ceil(thickness);
            return Box.of(Vec3.min(from, to).add(-t, -t, -t), Vec3.max(from, to).add(t, t, t));
        }
        public List<String> patterns() { return List.of(pattern); }
        private static double sq(int v) { return (double) v * v; }
    }

    /** Explicit single blocks, each with its own block state (e.g. {@code oak_stairs[facing=east]}). */
    record Blocks(List<BlockAt> blocks) implements Op {
        public long volume() { return blocks.size(); }
        public Box bounds() {
            Vec3 lo = blocks.get(0).pos(), hi = lo;
            for (BlockAt b : blocks) {
                lo = Vec3.min(lo, b.pos());
                hi = Vec3.max(hi, b.pos());
            }
            return Box.of(lo, hi);
        }
        public List<String> patterns() {
            Set<String> states = new LinkedHashSet<>();
            for (BlockAt b : blocks) states.add(b.state());
            return List.copyOf(states);
        }
        public List<Op> split(long budget) {
            int step = (int) Math.max(1, Math.min(Integer.MAX_VALUE, budget));
            List<Op> parts = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i += step) {
                parts.add(new Blocks(blocks.subList(i, Math.min(blocks.size(), i + step))));
            }
            return parts;
        }
    }

    /** Pastes a blueprint; {@code origin} is the minimum corner after rotation. */
    record Paste(Blueprint blueprint, Vec3 origin, int rotation) implements Op {
        public long volume() { return blueprint.cellCount(); }
        public Box bounds() {
            boolean swap = Math.floorMod(rotation, 180) == 90;
            int sx = swap ? blueprint.sizeZ() : blueprint.sizeX();
            int sz = swap ? blueprint.sizeX() : blueprint.sizeZ();
            return Box.of(origin, origin.add(sx - 1, blueprint.sizeY() - 1, sz - 1));
        }
        public List<String> patterns() { return List.copyOf(new LinkedHashSet<>(blueprint.palette().values())); }
        /** Layer ranges of the blueprint; every piece keeps the full footprint so rotation lines up. */
        public List<Op> split(long budget) {
            if (volume() <= budget || blueprint.sizeY() <= 1) return List.of(this);
            long perLayer = Math.max(1, blueprint.cellCount() / blueprint.sizeY());
            int step = (int) Math.max(1, Math.min(blueprint.sizeY(), budget / perLayer));
            List<Op> parts = new ArrayList<>();
            for (int y = 0; y < blueprint.sizeY(); y += step) {
                parts.add(new Paste(blueprint.slice(y, Math.min(blueprint.sizeY(), y + step)), origin.add(0, y, 0), rotation));
            }
            return parts;
        }
    }

    record BlockAt(Vec3 pos, String state) {
    }

    /** Horizontal slabs of the box, each at most {@code budget} blocks (but at least one layer). */
    static List<Box> slabs(Box box, long budget) {
        long layer = (long) box.sizeX() * box.sizeZ();
        int layersPerSlab = (int) Math.max(1, Math.min(box.sizeY(), budget / Math.max(1, layer)));
        List<Box> out = new ArrayList<>();
        for (int y = box.min().y(); y <= box.max().y(); y += layersPerSlab) {
            out.add(box.withY(y, Math.min(box.max().y(), y + layersPerSlab - 1)));
        }
        return out;
    }
}
