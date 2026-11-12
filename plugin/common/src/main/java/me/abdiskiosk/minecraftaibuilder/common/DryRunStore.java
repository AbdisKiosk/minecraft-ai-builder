package me.abdiskiosk.minecraftaibuilder.common;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The blocks a dry-run edit session wrote, kept instead of reaching the world. Reads answer from what was written,
 * then from {@code overlay} (an open draft), then from the world. A bridge keeps one per preview, with its own
 * block type {@code B}.
 */
public final class DryRunStore<B> {

    private final Function<Vec3, String> overlay;
    private final Function<String, B> parse;
    private final Function<B, String> name;
    private final long maxBlocks;
    private final Map<String, B> parsed = new HashMap<>();
    private final Map<Long, B> after = new HashMap<>();
    private final Map<Long, String> before = new HashMap<>();

    /**
     * @param overlay   state at a position in the open draft, or null where the draft has nothing
     * @param parse     turns a state name into the bridge's block (results are cached)
     * @param name      turns the bridge's block into a state name, as {@link WorldEditBridge#read} would
     * @param maxBlocks the most positions one preview may write
     */
    public DryRunStore(Function<Vec3, String> overlay, Function<String, B> parse, Function<B, String> name, long maxBlocks) {
        this.overlay = overlay == null ? p -> null : overlay;
        this.parse = parse;
        this.name = name;
        this.maxBlocks = maxBlocks;
    }

    /** Packs a block position into one long: 26 bits x, 26 bits z, 12 bits y. */
    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static Vec3 unkey(long k) {
        int x = (int) (k >> 38);
        int z = (int) (k << 26 >> 38);
        int y = (int) (k << 52 >> 52);
        return new Vec3(x, y, z);
    }

    public B read(int x, int y, int z, Supplier<B> world) {
        B b = after.get(key(x, y, z));
        if (b != null) return b;
        String o = overlay.apply(new Vec3(x, y, z));
        if (o != null) return parsed.computeIfAbsent(o, parse);
        return world.get();
    }

    public void write(int x, int y, int z, B block, Supplier<B> world) {
        long k = key(x, y, z);
        if (!before.containsKey(k)) {
            if (before.size() >= maxBlocks) {
                throw new EditException("the draft would change more than " + maxBlocks + " blocks (review.max-volume)");
            }
            String o = overlay.apply(new Vec3(x, y, z));
            before.put(k, o != null ? o : name.apply(world.get()));
        }
        after.put(k, block);
    }

    public int size() {
        return after.size();
    }

    /** What the session changed, against the world plus overlay as they were when each block was first written. */
    public BlockDiff diff() {
        BlockDiff.Builder b = new BlockDiff.Builder();
        for (Map.Entry<Long, B> e : after.entrySet()) {
            b.change(unkey(e.getKey()), before.get(e.getKey()), name.apply(e.getValue()));
        }
        return b.build();
    }
}
