package me.abdiskiosk.minecraftaibuilder.plugin.map;

import me.abdiskiosk.minecraftaibuilder.common.map.ChunkColumns;
import me.abdiskiosk.minecraftaibuilder.common.map.MapRaster;
import org.bukkit.ChunkSnapshot;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws a {@link ChunkSnapshot} of a loaded chunk into a map, for chunks edited since they were last saved. Reads go
 * through reflection because the API differs: 1.8-1.12 have numeric ids and data (preferred where they exist, since
 * 1.12's Material has no colours), 1.13+ only {@code getBlockType}, a Material named like the block.
 * Snapshots are copies, so this runs off the main thread.
 */
final class Snapshots {

    private static final Method TYPE_ID = method("getBlockTypeId");
    private static final Method DATA = TYPE_ID != null ? method("getBlockData") : null;
    private static final Method TYPE = TYPE_ID == null ? method("getBlockType") : null;
    private static final Map<Object, Integer> MATERIALS = new ConcurrentHashMap<>();

    private Snapshots() {
    }

    private static Method method(String name) {
        try {
            return ChunkSnapshot.class.getMethod(name, int.class, int.class, int.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** Replaces the chunk's 256 columns in {@code out}, its north-west column at (ox, oz). */
    static void draw(ChunkSnapshot s, MapRaster out, int ox, int oz, int minY, int maxY, int ceiling) {
        ChunkColumns.Blocks blocks = (x, y, z) -> packed(s, x, y, z);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                out.clear(ox + x, oz + z);
                // The snapshot's height map skips flowers, snow layers and the like; they are a block or two above it.
                int top = Math.min(maxY - 1, s.getHighestBlockYAt(x, z) + 8);
                ChunkColumns.column(blocks, x, z, top, minY, ceiling, out, ox + x, oz + z);
            }
        }
    }

    private static int packed(ChunkSnapshot s, int x, int y, int z) {
        try {
            if (TYPE_ID != null) {
                int id = ((Number) TYPE_ID.invoke(s, x, y, z)).intValue();
                int data = DATA == null ? 0 : ((Number) DATA.invoke(s, x, y, z)).intValue();
                return ChunkColumns.legacy(id, data);
            }
            if (TYPE != null) {
                Object material = TYPE.invoke(s, x, y, z);
                if (material == null) return MapRaster.AIR;
                return MATERIALS.computeIfAbsent(material, m -> {
                    String name = m instanceof Enum<?> e ? e.name() : String.valueOf(m);
                    return ChunkColumns.named(name.toLowerCase(Locale.ROOT));
                });
            }
            return MapRaster.AIR;
        } catch (ReflectiveOperationException | ClassCastException e) {
            return MapRaster.AIR;
        }
    }
}
