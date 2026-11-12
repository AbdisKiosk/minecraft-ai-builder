package me.abdiskiosk.minecraftaibuilder.common.map;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A small reader for Minecraft's NBT (big-endian, uncompressed). Compounds become {@code Map<String,Object>}, lists
 * {@code List<Object>}, arrays {@code byte[]}, {@code int[]} and {@code long[]}, numbers their boxed types. Tags whose
 * name is in {@code skip} are stepped over without being decoded, which is most of a chunk's bytes (light, entities,
 * biomes) and keeps reading a region file cheap.
 */
public final class Nbt {

    private static final int MAX_DEPTH = 64;

    private final ByteBuffer in;
    private final Set<String> skip;

    private Nbt(ByteBuffer in, Set<String> skip) {
        this.in = in;
        this.skip = skip;
    }

    /** The root compound of {@code data}; throws IOException on anything that is not well-formed NBT. */
    public static Map<String, Object> read(byte[] data, int length, Set<String> skip) throws IOException {
        Nbt nbt = new Nbt(ByteBuffer.wrap(data, 0, length), skip);
        try {
            int type = nbt.in.get();
            if (type != 10) throw new IOException("NBT root is tag " + type + ", not a compound");
            nbt.skipString();
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) nbt.value(10, 0);
            return root;
        } catch (BufferUnderflowException | IllegalArgumentException | NegativeArraySizeException e) {
            throw new IOException("truncated or malformed NBT", e);
        }
    }

    private Object value(int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nested too deeply");
        switch (type) {
            case 1: return in.get();
            case 2: return in.getShort();
            case 3: return in.getInt();
            case 4: return in.getLong();
            case 5: return in.getFloat();
            case 6: return in.getDouble();
            case 7: {
                byte[] a = new byte[length(1)];
                in.get(a);
                return a;
            }
            case 8: return string();
            case 9: {
                int elem = in.get();
                int n = in.getInt();
                if (n <= 0) return List.of();
                if (elem == 0) throw new IOException("NBT list of end tags");
                List<Object> list = new ArrayList<>(Math.min(n, 1024));
                for (int i = 0; i < n; i++) list.add(value(elem, depth + 1));
                return list;
            }
            case 10: {
                Map<String, Object> map = new HashMap<>();
                while (true) {
                    int t = in.get();
                    if (t == 0) return map;
                    String name = string();
                    if (skip.contains(name)) skipValue(t, depth + 1);
                    else map.put(name, value(t, depth + 1));
                }
            }
            case 11: {
                int[] a = new int[length(4)];
                in.asIntBuffer().get(a);
                in.position(in.position() + a.length * 4);
                return a;
            }
            case 12: {
                long[] a = new long[length(8)];
                in.asLongBuffer().get(a);
                in.position(in.position() + a.length * 8);
                return a;
            }
            default: throw new IOException("unknown NBT tag " + type);
        }
    }

    private void skipValue(int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nested too deeply");
        switch (type) {
            case 1 -> advance(1);
            case 2 -> advance(2);
            case 3, 5 -> advance(4);
            case 4, 6 -> advance(8);
            case 7 -> advance(length(1));
            case 8 -> skipString();
            case 9 -> {
                int elem = in.get();
                int n = in.getInt();
                for (int i = 0; i < n; i++) skipValue(elem, depth + 1);
            }
            case 10 -> {
                while (true) {
                    int t = in.get();
                    if (t == 0) return;
                    skipString();
                    skipValue(t, depth + 1);
                }
            }
            case 11 -> advance((long) length(4) * 4);
            case 12 -> advance((long) length(8) * 8);
            default -> throw new IOException("unknown NBT tag " + type);
        }
    }

    /** An array's element count, checked against what is left so a corrupt length cannot allocate gigabytes. */
    private int length(int elementSize) throws IOException {
        int n = in.getInt();
        if (n < 0 || (long) n * elementSize > in.remaining()) throw new IOException("NBT array longer than its data");
        return n;
    }

    private void advance(long n) throws IOException {
        if (n > in.remaining()) throw new IOException("NBT value longer than its data");
        in.position(in.position() + (int) n);
    }

    private void skipString() throws IOException {
        advance(in.getShort() & 0xFFFF);
    }

    /** Java's modified UTF-8; block names are ASCII, so plain UTF-8 decoding is close enough for the rest. */
    private String string() throws IOException {
        int n = in.getShort() & 0xFFFF;
        if (n > in.remaining()) throw new IOException("NBT string longer than its data");
        String s = new String(in.array(), in.arrayOffset() + in.position(), n, StandardCharsets.UTF_8);
        in.position(in.position() + n);
        return s;
    }

    // ---- typed access ----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof List ? (List<Object>) v : List.of();
    }

    public static String string(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof String s ? s : null;
    }

    /** A number of any NBT width, or {@code fallback}. */
    public static int integer(Map<String, Object> m, String key, int fallback) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    /** Builds a skip set; callers keep theirs in a constant. */
    public static Set<String> names(String... names) {
        return Set.copyOf(java.util.Arrays.asList(names));
    }
}
