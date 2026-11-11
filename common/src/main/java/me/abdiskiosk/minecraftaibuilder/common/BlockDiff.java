package me.abdiskiosk.minecraftaibuilder.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The blocks one edit changed: for each changed position the state before and after, like a git diff of the world.
 * Built from before/after reads of the boxes an edit touched; stored compactly with {@link #encode()}.
 */
public final class BlockDiff {

    public enum Kind { ADDED, REMOVED, CHANGED }

    public record Change(Vec3 pos, String before, String after) {
        public Kind kind() {
            if (RegionCodec.isAir(before)) return Kind.ADDED;
            if (RegionCodec.isAir(after)) return Kind.REMOVED;
            return Kind.CHANGED;
        }
    }

    /** Per block type (without properties), how many were placed and how many taken away. Like a diffstat line. */
    public record TypeCount(String type, long added, long removed) {
    }

    public record Stats(long added, long removed, long changed, List<TypeCount> types) {
        public long total() {
            return added + removed + changed;
        }
    }

    /** What reverting would do: the blocks to put back, and changed cells that were edited again since. */
    public record Revert(List<Op.BlockAt> blocks, List<Change> conflicts) {
    }

    private static final int MAGIC = 0x42443031; // "BD01"

    private final List<Change> changes;

    private BlockDiff(List<Change> changes) {
        this.changes = List.copyOf(changes);
    }

    public static BlockDiff empty() {
        return new BlockDiff(List.of());
    }

    /** Collects changes from several boxes, which must not overlap. */
    public static final class Builder {
        private final List<Change> changes = new ArrayList<>();

        /** @param before states in {@link RegionCodec#index} order, read before the edit; {@code after} likewise */
        public Builder add(Box box, String[] before, String[] after) {
            if (before.length != box.volume() || after.length != box.volume()) {
                throw new IllegalArgumentException("arrays do not match " + box);
            }
            int i = 0;
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    for (int x = box.min().x(); x <= box.max().x(); x++, i++) {
                        if (!same(before[i], after[i])) changes.add(new Change(new Vec3(x, y, z), air(before[i]), air(after[i])));
                    }
                }
            }
            return this;
        }

        /** Like {@link #add(Box, String[], String[])} with states looked up by index, so no arrays are built. */
        public Builder add(Box box, java.util.function.IntFunction<String> before, java.util.function.IntFunction<String> after) {
            int i = 0;
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    for (int x = box.min().x(); x <= box.max().x(); x++, i++) {
                        String b = before.apply(i), a = after.apply(i);
                        if (!same(b, a)) changes.add(new Change(new Vec3(x, y, z), air(b), air(a)));
                    }
                }
            }
            return this;
        }

        public BlockDiff build() {
            changes.sort(ORDER);
            return new BlockDiff(changes);
        }
    }

    public static BlockDiff of(Box box, String[] before, String[] after) {
        return new Builder().add(box, before, after).build();
    }

    private static final Comparator<Change> ORDER = Comparator.<Change>comparingInt(c -> c.pos().y())
            .thenComparingInt(c -> c.pos().z()).thenComparingInt(c -> c.pos().x());

    /** Equal states, treating every kind of air as the same. */
    public static boolean same(String a, String b) {
        if (RegionCodec.isAir(a) || RegionCodec.isAir(b)) return RegionCodec.isAir(a) && RegionCodec.isAir(b);
        return a.equals(b);
    }

    private static String air(String state) {
        return RegionCodec.isAir(state) ? "air" : state;
    }

    public List<Change> changes() {
        return changes;
    }

    public int size() {
        return changes.size();
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    /** The box around every change, or null when nothing changed. */
    public Box bounds() {
        if (changes.isEmpty()) return null;
        Vec3 lo = changes.get(0).pos(), hi = lo;
        for (Change c : changes) {
            lo = Vec3.min(lo, c.pos());
            hi = Vec3.max(hi, c.pos());
        }
        return Box.of(lo, hi);
    }

    public Stats stats() {
        long added = 0, removed = 0, changed = 0;
        Map<String, long[]> types = new HashMap<>();
        for (Change c : changes) {
            switch (c.kind()) {
                case ADDED -> added++;
                case REMOVED -> removed++;
                case CHANGED -> changed++;
            }
            if (!RegionCodec.isAir(c.after())) types.computeIfAbsent(RegionCodec.stripProperties(c.after()), k -> new long[2])[0]++;
            if (!RegionCodec.isAir(c.before())) types.computeIfAbsent(RegionCodec.stripProperties(c.before()), k -> new long[2])[1]++;
        }
        List<TypeCount> list = new ArrayList<>();
        types.forEach((type, n) -> list.add(new TypeCount(type, n[0], n[1])));
        list.sort(Comparator.comparingLong((TypeCount t) -> -(t.added() + t.removed())).thenComparing(TypeCount::type));
        return new Stats(added, removed, changed, list);
    }

    /**
     * {@code states} of {@code box} with this diff's before (or after) states written over the changed cells inside it.
     * Rebuilds what a region looked like around one edit from a current read.
     */
    public String[] overlay(Box box, String[] states, boolean after) {
        String[] out = states.clone();
        for (Change c : changes) {
            if (!inside(box, c.pos())) continue;
            out[RegionCodec.index(box, c.pos().x(), c.pos().y(), c.pos().z())] = after ? c.after() : c.before();
        }
        return out;
    }

    /** Changed cells inside {@code box} by {@link RegionCodec#index}, for {@link IsoRenderer} highlights. */
    public Map<Integer, Kind> highlight(Box box) {
        Map<Integer, Kind> out = new HashMap<>();
        for (Change c : changes) {
            if (inside(box, c.pos())) out.put(RegionCodec.index(box, c.pos().x(), c.pos().y(), c.pos().z()), c.kind());
        }
        return out;
    }

    /**
     * @param current the state now at a position
     * @param force   also put back cells that were changed again after this edit
     */
    public Revert revert(Function<Vec3, String> current, boolean force) {
        List<Op.BlockAt> blocks = new ArrayList<>();
        List<Change> conflicts = new ArrayList<>();
        for (Change c : changes) {
            String now = current.apply(c.pos());
            if (same(now, c.before())) continue; // already as it was
            if (!force && !same(now, c.after())) {
                conflicts.add(new Change(c.pos(), c.after(), air(now)));
                continue;
            }
            blocks.add(new Op.BlockAt(c.pos(), c.before()));
        }
        return new Revert(blocks, conflicts);
    }

    private static boolean inside(Box box, Vec3 p) {
        return p.x() >= box.min().x() && p.x() <= box.max().x() && p.y() >= box.min().y() && p.y() <= box.max().y()
                && p.z() >= box.min().z() && p.z() <= box.max().z();
    }

    /** Gzipped: a palette of states, then each change as zig-zag varint deltas from the previous position. */
    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
                Map<String, Integer> palette = new LinkedHashMap<>();
                for (Change c : changes) {
                    palette.putIfAbsent(c.before(), palette.size());
                    palette.putIfAbsent(c.after(), palette.size());
                }
                out.writeInt(MAGIC);
                varint(out, palette.size());
                for (String s : palette.keySet()) out.writeUTF(s);
                varint(out, changes.size());
                int px = 0, py = 0, pz = 0;
                for (Change c : changes) {
                    Vec3 p = c.pos();
                    varint(out, zigzag(p.x() - px));
                    varint(out, zigzag(p.y() - py));
                    varint(out, zigzag(p.z() - pz));
                    px = p.x();
                    py = p.y();
                    pz = p.z();
                    varint(out, palette.get(c.before()));
                    varint(out, palette.get(c.after()));
                }
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static BlockDiff decode(byte[] data) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)))) {
            if (in.readInt() != MAGIC) throw new IOException("not a block diff");
            String[] palette = new String[varint(in)];
            for (int i = 0; i < palette.length; i++) palette[i] = in.readUTF();
            int n = varint(in);
            List<Change> changes = new ArrayList<>(n);
            int x = 0, y = 0, z = 0;
            for (int i = 0; i < n; i++) {
                x += unzigzag(varint(in));
                y += unzigzag(varint(in));
                z += unzigzag(varint(in));
                changes.add(new Change(new Vec3(x, y, z), palette[varint(in)], palette[varint(in)]));
            }
            return new BlockDiff(changes);
        } catch (IOException | ArrayIndexOutOfBoundsException e) {
            throw new EditException("corrupt block diff: " + e.getMessage(), e);
        }
    }

    private static int zigzag(int v) {
        return (v << 1) ^ (v >> 31);
    }

    private static int unzigzag(int v) {
        return (v >>> 1) ^ -(v & 1);
    }

    private static void varint(DataOutputStream out, int v) throws IOException {
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    private static int varint(DataInputStream in) throws IOException {
        int v = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
        }
        throw new IOException("varint too long");
    }
}
