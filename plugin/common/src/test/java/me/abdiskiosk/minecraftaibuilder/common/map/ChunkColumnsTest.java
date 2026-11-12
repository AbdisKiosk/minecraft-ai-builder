package me.abdiskiosk.minecraftaibuilder.common.map;

import me.abdiskiosk.minecraftaibuilder.common.BlockColors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ChunkColumnsTest {

    @TempDir
    Path dir;

    private static final int GRASS = BlockColors.rgb("grass_block");
    private static final int SAND = BlockColors.rgb("sand");

    // ---- 1.18+ ----

    @Test
    void readsModernSectionsWithNegativeY() throws IOException {
        // Section -1 is stone; section 4 has grass at y=64 (local 0) over the whole chunk, with sand at one column.
        String[] palette = {"minecraft:air", "minecraft:grass_block", "minecraft:sand"};
        int[] idx = new int[4096];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) idx[(0 << 8) | (z << 4) | x] = 1;
        idx[(0 << 8) | (3 << 4) | 2] = 2;
        Map<String, Object> root = Map.of(
                "DataVersion", 3700,
                "Status", "minecraft:full",
                "sections", List.of(
                        modernSection(-1, new String[]{"minecraft:stone"}, null),
                        modernSection(4, palette, pack(idx, 4, false)),
                        modernSection(10, new String[]{"minecraft:air"}, null)));
        MapRaster r = draw(root, ChunkColumns.OPEN_SKY);
        assertEquals(64, r.height(0, 0));
        assertEquals(color(GRASS), pixel(r, 0, 0) & 0xFFFFFF);
        assertEquals(color(SAND), pixel(r, 2, 3) & 0xFFFFFF);
    }

    @Test
    void skipsChunksStillGenerating() throws IOException {
        Map<String, Object> root = Map.of("Status", "minecraft:noise",
                "sections", List.of(modernSection(4, new String[]{"minecraft:stone"}, null)));
        MapRaster r = new MapRaster(16);
        assertFalse(ChunkColumns.draw(roundTrip(root), r, 0, 0, ChunkColumns.OPEN_SKY));
        assertFalse(r.isSet(0, 0));
    }

    @Test
    void waterShowsItsSurfaceAndDepth() throws IOException {
        // Stone floor at y=60, water from 61 to 63.
        String[] palette = {"minecraft:stone", "minecraft:water", "minecraft:air"};
        int[] idx = new int[4096];
        for (int y = 0; y < 16; y++) {
            int abs = 48 + y;
            int v = abs <= 60 ? 0 : abs <= 63 ? 1 : 2;
            for (int i = 0; i < 256; i++) idx[(y << 8) | i] = v;
        }
        Map<String, Object> root = Map.of("Status", "full", "sections", List.of(modernSection(3, palette, pack(idx, 4, false))));
        MapRaster r = draw(root, ChunkColumns.OPEN_SKY);
        assertEquals(63, r.height(5, 5));
        int p = pixel(r, 5, 5);
        assertTrue((p & 0xFF) > ((p >> 16) & 0xFF), "water is blue: " + Integer.toHexString(p));
    }

    // ---- 1.13-1.15: palette indices run across longs ----

    @Test
    void readsSpanningBlockStates() throws IOException {
        // 17 entries need 5 bits, which do not divide 64: before 1.16 they spill into the next long.
        String[] palette = new String[17];
        palette[0] = "minecraft:air";
        for (int i = 1; i < 17; i++) palette[i] = "minecraft:stone";
        palette[16] = "minecraft:sand";
        int[] idx = new int[4096];
        for (int i = 0; i < 256; i++) idx[(5 << 8) | i] = 16;
        long[] packed = pack(idx, 5, true);
        assertEquals(320, packed.length);
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("Y", (byte) 4);
        section.put("Palette", names(palette));
        section.put("BlockStates", packed);
        Map<String, Object> root = Map.of("DataVersion", 2230, "Level", Map.of("Status", "postprocessed", "Sections", List.of(section)));
        MapRaster r = draw(root, ChunkColumns.OPEN_SKY);
        assertEquals(69, r.height(7, 9));
        assertEquals(color(SAND), pixel(r, 7, 9) & 0xFFFFFF);
    }

    @Test
    void unpacksBothLayouts() {
        int[] idx = new int[4096];
        for (int i = 0; i < 4096; i++) idx[i] = (i * 7) % 20;
        for (boolean spanning : new boolean[]{true, false}) {
            short[] out = ChunkColumns.unpack(pack(idx, 5, spanning), 20);
            assertNotNull(out);
            for (int i = 0; i < 4096; i++) assertEquals(idx[i], out[i], "index " + i + (spanning ? " spanning" : ""));
        }
    }

    // ---- 1.8-1.12 ----

    @Test
    void readsLegacyIds() throws IOException {
        byte[] blocks = new byte[4096];
        byte[] data = new byte[2048];
        for (int i = 0; i < 256; i++) blocks[(2 << 8) | i] = 2; // grass at y=18
        int wool = (3 << 8) | (1 << 4) | 1; // red wool at (1, 19, 1)
        blocks[wool] = 35;
        data[wool >> 1] |= (byte) (14 << ((wool & 1) * 4));
        Map<String, Object> section = Map.of("Y", (byte) 1, "Blocks", blocks, "Data", data);
        Map<String, Object> root = Map.of("Level", Map.of("Sections", List.of(section)));
        MapRaster r = draw(root, ChunkColumns.OPEN_SKY);
        assertEquals(18, r.height(0, 0));
        assertEquals(19, r.height(1, 1));
        int top = pixel(r, 1, 1); // a block above its neighbours is lit, so compare hue rather than the exact colour
        assertTrue(((top >> 16) & 0xFF) > 2 * ((top >> 8) & 0xFF), Integer.toHexString(top));
    }

    @Test
    void namesLegacyVariants() {
        assertEquals("red_wool", LegacyBlocks.name(35, 14));
        assertEquals("birch_leaves", LegacyBlocks.name(18, 2 | 8));
        assertEquals("dark_oak_log", LegacyBlocks.name(162, 1));
        assertEquals("andesite", LegacyBlocks.name(1, 5));
        assertEquals("air", LegacyBlocks.name(0, 0));
        assertEquals("stone", LegacyBlocks.name(254, 0));
    }

    // ---- the nether ----

    @Test
    void underACeilingShowsTheFloorBelowIt() throws IOException {
        // Netherrack roof from 112 up, air 100-111, floor at 99.
        String[] palette = {"minecraft:netherrack", "minecraft:air"};
        List<Object> sections = new ArrayList<>();
        for (int sy = 6; sy <= 7; sy++) {
            int[] idx = new int[4096];
            for (int y = 0; y < 16; y++) {
                int abs = sy * 16 + y;
                int v = abs >= 112 || abs <= 99 ? 0 : 1;
                for (int i = 0; i < 256; i++) idx[(y << 8) | i] = v;
            }
            sections.add(modernSection(sy, palette, pack(idx, 4, false)));
        }
        Map<String, Object> root = Map.of("Status", "full", "sections", sections);
        assertEquals(127, draw(root, ChunkColumns.OPEN_SKY).height(0, 0));
        assertEquals(99, draw(root, ChunkColumns.NETHER_CEILING).height(0, 0));
    }

    // ---- region files ----

    @Test
    void readsChunksFromARegionFile() throws IOException {
        Map<String, Object> chunk = Map.of("Status", "full", "Entities", List.of(Map.of("id", "minecraft:pig")),
                "sections", List.of(modernSection(4, new String[]{"minecraft:stone"}, null)));
        Path file = dir.resolve("r.-1.2.mca");
        writeRegion(file, Map.of(3 + 5 * 32, nbt(chunk)));
        try (RegionFile region = RegionFile.open(file)) {
            assertEquals(-1, region.regionX());
            assertEquals(2, region.regionZ());
            assertTrue(region.has(3, 5));
            assertFalse(region.has(0, 0));
            Map<String, Object> read = region.chunk(3, 5, ChunkColumns.SKIP);
            assertNotNull(read);
            assertFalse(read.containsKey("Entities"), "skipped");
            MapRaster r = new MapRaster(16);
            assertTrue(ChunkColumns.draw(read, r, 0, 0, ChunkColumns.OPEN_SKY));
            assertEquals(79, r.height(0, 0));
            assertNull(region.chunk(0, 0, Set.of()));
        }
        assertArrayEquals(new int[]{-1, 2}, RegionFile.coords("r.-1.2.mca"));
        assertNull(RegionFile.coords("r.1.mca"));
    }

    @Test
    void aCorruptChunkIsUnreadableNotFatal() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        writeRegion(file, Map.of(0, new byte[]{10, 0, 0, 7, 0, 0, 0x7F, 0x7F, 0x7F}));
        try (RegionFile region = RegionFile.open(file)) {
            assertNull(region.chunk(0, 0, ChunkColumns.SKIP));
            assertEquals(1, region.unreadable());
        }
    }

    // ---- images ----

    @Test
    void downsamplesFourTilesIntoOne() {
        int[] red = new int[16];
        Arrays.fill(red, 0xFFFF0000);
        int[] half = new int[16];
        for (int i = 0; i < 16; i += 2) half[i] = 0xFF0000FF; // every other pixel transparent
        int[] out = MapRaster.downsample(new int[][]{red, null, half, null}, 4);
        assertEquals(0xFFFF0000, out[0]);
        assertEquals(0, out[2], "nothing in the north-east quarter");
        assertEquals(0xFF0000FF, out[2 * 4], "transparent pixels do not darken the average");
        int[] back = MapRaster.decode(MapRaster.png(out, 4), 4);
        assertArrayEquals(out, back);
    }

    @Test
    void slopesFacingNorthWestAreLighter() {
        MapRaster r = new MapRaster(4);
        for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) r.set(x, z, 0x808080, 64 - x - z, 0);
        int[] px = r.argb();
        // Height falls towards the south-east, so every column is higher than its south-east neighbour and lower
        // than its north-west one: all equally darker, including the first row and column (no seam).
        int first = px[0] & 0xFF;
        assertTrue(first < 0x80);
        for (int p : px) assertEquals(first, p & 0xFF);
    }

    // ---- helpers ----

    private static int color(int rgb) {
        return rgb; // flat ground has no slope, so the shaded colour is the block's own
    }

    private static int pixel(MapRaster r, int x, int z) {
        return r.argb()[z * r.size() + x];
    }

    private MapRaster draw(Map<String, Object> root, int ceiling) throws IOException {
        MapRaster r = new MapRaster(16);
        assertTrue(ChunkColumns.draw(roundTrip(root), r, 0, 0, ceiling));
        return r;
    }

    private static Map<String, Object> roundTrip(Map<String, Object> root) throws IOException {
        byte[] b = nbt(root);
        return Nbt.read(b, b.length, ChunkColumns.SKIP);
    }

    private static Map<String, Object> modernSection(int y, String[] palette, long[] data) {
        Map<String, Object> states = new LinkedHashMap<>();
        states.put("palette", names(palette));
        if (data != null) states.put("data", data);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("Y", (byte) y);
        s.put("block_states", states);
        s.put("SkyLight", new byte[2048]);
        return s;
    }

    private static List<Object> names(String[] palette) {
        List<Object> out = new ArrayList<>();
        for (String n : palette) out.add(Map.of("Name", n, "Properties", Map.of("snowy", "false")));
        return out;
    }

    static long[] pack(int[] idx, int bits, boolean spanning) {
        if (spanning) {
            long[] out = new long[64 * bits];
            for (int i = 0; i < 4096; i++) {
                int bit = i * bits, at = bit >> 6, off = bit & 63;
                out[at] |= (long) idx[i] << off;
                if (off + bits > 64) out[at + 1] |= (long) idx[i] >>> (64 - off);
            }
            return out;
        }
        int perLong = 64 / bits;
        long[] out = new long[(4096 + perLong - 1) / perLong];
        for (int i = 0; i < 4096; i++) out[i / perLong] |= (long) idx[i] << ((i % perLong) * bits);
        return out;
    }

    // A minimal NBT writer: enough for these tests.
    static byte[] nbt(Map<String, Object> root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(10);
        out.writeUTF("");
        writeValue(out, root);
        return bytes.toByteArray();
    }

    private static int type(Object v) {
        if (v instanceof Byte) return 1;
        if (v instanceof Short) return 2;
        if (v instanceof Integer) return 3;
        if (v instanceof Long) return 4;
        if (v instanceof byte[]) return 7;
        if (v instanceof String) return 8;
        if (v instanceof List) return 9;
        if (v instanceof Map) return 10;
        if (v instanceof int[]) return 11;
        if (v instanceof long[]) return 12;
        throw new IllegalArgumentException(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(DataOutputStream out, Object v) throws IOException {
        switch (type(v)) {
            case 1 -> out.writeByte((Byte) v);
            case 2 -> out.writeShort((Short) v);
            case 3 -> out.writeInt((Integer) v);
            case 4 -> out.writeLong((Long) v);
            case 7 -> {
                out.writeInt(((byte[]) v).length);
                out.write((byte[]) v);
            }
            case 8 -> out.writeUTF((String) v);
            case 9 -> {
                List<Object> list = (List<Object>) v;
                out.writeByte(list.isEmpty() ? 0 : type(list.get(0)));
                out.writeInt(list.size());
                for (Object o : list) writeValue(out, o);
            }
            case 10 -> {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                    out.writeByte(type(e.getValue()));
                    out.writeUTF(e.getKey());
                    writeValue(out, e.getValue());
                }
                out.writeByte(0);
            }
            case 11 -> {
                out.writeInt(((int[]) v).length);
                for (int i : (int[]) v) out.writeInt(i);
            }
            case 12 -> {
                out.writeInt(((long[]) v).length);
                for (long l : (long[]) v) out.writeLong(l);
            }
            default -> throw new IllegalStateException();
        }
    }

    /** Writes a region file with the given chunks (index → raw NBT), zlib-compressed like the game does. */
    static void writeRegion(Path file, Map<Integer, byte[]> chunks) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(8192);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int sector = 2;
        for (Map.Entry<Integer, byte[]> c : chunks.entrySet()) {
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            try (DeflaterOutputStream d = new DeflaterOutputStream(z)) {
                d.write(c.getValue());
            }
            byte[] compressed = z.toByteArray();
            ByteBuffer chunk = ByteBuffer.allocate(((compressed.length + 5 + 4095) / 4096) * 4096);
            chunk.putInt(compressed.length + 1).put((byte) 2).put(compressed);
            int sectors = chunk.capacity() / 4096;
            header.putInt(c.getKey() * 4, sector << 8 | sectors);
            body.write(chunk.array());
            sector += sectors;
        }
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(header.array());
        all.write(body.toByteArray());
        Files.write(file, all.toByteArray());
    }
}
