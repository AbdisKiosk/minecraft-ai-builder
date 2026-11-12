package me.abdiskiosk.minecraftaibuilder.common.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the top block of each of a chunk's 256 columns, from saved chunk NBT of any version: 1.8-1.12 ids
 * ({@code Level.Sections[].Blocks}), 1.13-1.17 palettes ({@code Level.Sections[].Palette}/{@code BlockStates}) and
 * 1.18+ ({@code sections[].block_states}). Chunks still being generated are skipped.
 */
public final class ChunkColumns {

    /** Everything a map does not need; skipped unread, which is most of a chunk. */
    public static final Set<String> SKIP = Nbt.names("Entities", "TileEntities", "block_entities", "BlockLight",
            "SkyLight", "Biomes", "biomes", "Heightmaps", "HeightMap", "Structures", "structures", "PostProcessing",
            "Lights", "CarvingMasks", "fluid_ticks", "block_ticks", "TileTicks", "LiquidTicks", "ToBeTicked",
            "LiquidsToBeTicked", "Properties", "blending_data", "UpgradeData", "CarvingMask", "below_zero_retrogen",
            "ChunkBukkitValues", "PublicBukkitValues", "Paper.Version");

    /** Generation steps from which a chunk's blocks are final enough to draw. */
    private static final Set<String> DRAWN = Set.of("full", "fullchunk", "postprocessed", "mobs_spawned", "finalized",
            "lighted", "light", "initialize_light", "spawn", "heightmaps", "features", "decorated");

    /** One block lookup, packed as {@link MapRaster#packed}. */
    public interface Blocks {
        int at(int x, int y, int z);
    }

    private ChunkColumns() {
    }

    /** How a block of this name shows on the map, packed as {@link MapRaster#packed}; cached. */
    public static int named(String name) {
        return Palette.packed(name);
    }

    /** How legacy block id:data shows on the map, packed as {@link MapRaster#packed}; cached. */
    public static int legacy(int id, int data) {
        return Legacy.packed(id, data);
    }

    /** No ceiling: columns are scanned from the top of the world. */
    public static final int OPEN_SKY = Integer.MAX_VALUE;
    /** Where columns start in the nether: under its bedrock roof, so the map shows the floor of its caverns. */
    public static final int NETHER_CEILING = 120;

    /**
     * Draws the chunk into {@code out} with its north-west column at (ox, oz).
     *
     * @param ceiling {@link #OPEN_SKY}, or the height to look down from past the first solid layer (the nether)
     * @return false when the chunk is not drawable (still generating, or not a chunk this understands)
     */
    public static boolean draw(Map<String, Object> root, MapRaster out, int ox, int oz, int ceiling) {
        Map<String, Object> level = Nbt.compound(root, "Level");
        Map<String, Object> base = level != null ? level : root;
        String status = Nbt.string(base, "Status");
        if (status != null) {
            String s = status.startsWith("minecraft:") ? status.substring(10) : status;
            if (!DRAWN.contains(s)) return false;
        }
        List<Object> raw = Nbt.list(base, "sections");
        if (raw.isEmpty()) raw = Nbt.list(base, "Sections");
        List<Section> sections = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Section s = Section.of((Map<String, Object>) o);
            if (s != null && !s.empty()) sections.add(s);
        }
        sections.sort((a, b) -> Integer.compare(b.y, a.y));
        if (sections.isEmpty()) return true; // generated but empty (the void, or the end's outer islands' gaps)
        int top = sections.get(0).y * 16 + 15;
        int bottom = sections.get(sections.size() - 1).y * 16;
        Section[] byY = new Section[(top - bottom) / 16 + 1];
        for (Section s : sections) byY[(s.y * 16 - bottom) / 16] = s;
        Blocks blocks = (x, y, z) -> {
            Section s = byY[(y - bottom) >> 4];
            return s == null ? MapRaster.AIR : s.at(((y & 15) << 8) | (z << 4) | x);
        };
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) column(blocks, x, z, top, bottom, ceiling, out, ox + x, oz + z);
        }
        return true;
    }

    /**
     * Scans one column down from {@code top} to {@code bottom} and records its surface in {@code out}. Below a
     * ceiling, the scan starts there and first steps through solid blocks to the air beneath them; a column with no
     * air under the ceiling shows the ceiling's own block.
     */
    public static void column(Blocks blocks, int x, int z, int top, int bottom, int ceiling, MapRaster out, int px, int pz) {
        int surface = Integer.MIN_VALUE;
        int water = 0;
        int start = Math.min(top, ceiling);
        if (ceiling != OPEN_SKY) {
            int y = start;
            while (y >= bottom && blocks.at(x, y, z) != MapRaster.AIR) y--;
            if (y >= bottom) start = y;
        }
        for (int y = start; y >= bottom; y--) {
            int v = blocks.at(x, y, z);
            if (v == MapRaster.AIR) {
                if (surface != Integer.MIN_VALUE) break; // an air pocket under water: call the water deep
                continue;
            }
            if ((v & 0xFF000000) == MapRaster.WATER) {
                if (surface == Integer.MIN_VALUE) surface = y;
                if (++water >= MapRaster.MAX_DEPTH) break;
                continue;
            }
            out.set(px, pz, v & 0xFFFFFF, surface == Integer.MIN_VALUE ? y : surface, water);
            return;
        }
        if (surface != Integer.MIN_VALUE) out.set(px, pz, -1, surface, water);
    }

    /** One 16x16x16 section, decoded to packed map values. */
    private static final class Section {
        final int y;
        /** Every block's packed value when the section is uniform, else -1. */
        final int uniform;
        final short[] index;
        final int[] palette;
        final byte[] ids;
        final byte[] add;
        final byte[] data;

        private Section(int y, int uniform, short[] index, int[] palette, byte[] ids, byte[] add, byte[] data) {
            this.y = y;
            this.uniform = uniform;
            this.index = index;
            this.palette = palette;
            this.ids = ids;
            this.add = add;
            this.data = data;
        }

        boolean empty() {
            return uniform == MapRaster.AIR;
        }

        int at(int i) {
            if (uniform != -1) return uniform;
            if (ids != null) {
                int id = ids[i] & 0xFF;
                if (add != null) id |= nibble(add, i) << 8;
                return Legacy.packed(id, data == null ? 0 : nibble(data, i));
            }
            int p = index[i];
            return p < palette.length ? palette[p] : MapRaster.AIR;
        }

        static Section of(Map<String, Object> s) {
            int y = Nbt.integer(s, "Y", Integer.MIN_VALUE);
            if (y == Integer.MIN_VALUE) return null;
            if (s.get("Blocks") instanceof byte[] blocks && blocks.length == 4096) {
                byte[] add = s.get("Add") instanceof byte[] a && a.length == 2048 ? a : null;
                byte[] data = s.get("Data") instanceof byte[] d && d.length == 2048 ? d : null;
                return new Section(y, -1, null, null, blocks, add, data);
            }
            Map<String, Object> states = Nbt.compound(s, "block_states");
            List<Object> names = states != null ? Nbt.list(states, "palette") : Nbt.list(s, "Palette");
            Object packedData = states != null ? states.get("data") : s.get("BlockStates");
            if (names.isEmpty()) return null;
            int[] palette = new int[names.size()];
            for (int i = 0; i < palette.length; i++) {
                @SuppressWarnings("unchecked")
                String name = names.get(i) instanceof Map ? Nbt.string((Map<String, Object>) names.get(i), "Name") : null;
                palette[i] = name == null ? MapRaster.AIR : Palette.packed(name);
            }
            if (palette.length == 1 || !(packedData instanceof long[] longs)) {
                return new Section(y, palette[0], null, palette, null, null, null);
            }
            short[] index = unpack(longs, palette.length);
            if (index == null) return null;
            return new Section(y, -1, index, palette, null, null, null);
        }

        private static int nibble(byte[] a, int i) {
            return (a[i >> 1] >> ((i & 1) * 4)) & 0xF;
        }
    }

    /**
     * Unpacks 4096 palette indices. Before 1.16 they run across long boundaries, since then each long holds a whole
     * number of them; the array's length tells which.
     */
    static short[] unpack(long[] data, int paletteSize) {
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
        for (int b = bits; b <= 16; b++) {
            int perLong = 64 / b;
            if (data.length == (4096 + perLong - 1) / perLong) return unpack(data, b, false);
            if (data.length == 64 * b) return unpack(data, b, true);
        }
        return null;
    }

    private static short[] unpack(long[] data, int bits, boolean spanning) {
        short[] out = new short[4096];
        long mask = (1L << bits) - 1;
        if (spanning) {
            for (int i = 0; i < 4096; i++) {
                int bit = i * bits;
                int at = bit >> 6, off = bit & 63;
                long v = data[at] >>> off;
                if (off + bits > 64) v |= data[at + 1] << (64 - off);
                out[i] = (short) (v & mask);
            }
        } else {
            int perLong = 64 / bits;
            for (int i = 0; i < 4096; i++) {
                out[i] = (short) ((data[i / perLong] >>> ((i % perLong) * bits)) & mask);
            }
        }
        return out;
    }

    /** Packed values by block name, cached: a region has a few hundred distinct names among a million entries. */
    static final class Palette {
        private static final java.util.concurrent.ConcurrentHashMap<String, Integer> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

        static int packed(String name) {
            return CACHE.computeIfAbsent(name, MapRaster::packed);
        }
    }

    /** Packed values of legacy id:data, cached in a table filled as they are met. */
    static final class Legacy {
        private static final int[] TABLE = new int[4096 * 16];
        private static final int UNSET = 0x7F000000;

        static {
            java.util.Arrays.fill(TABLE, UNSET);
        }

        static int packed(int id, int data) {
            int key = (id & 4095) << 4 | (data & 15);
            int v = TABLE[key];
            if (v == UNSET) TABLE[key] = v = MapRaster.packed(LegacyBlocks.name(id, data));
            return v;
        }
    }
}
