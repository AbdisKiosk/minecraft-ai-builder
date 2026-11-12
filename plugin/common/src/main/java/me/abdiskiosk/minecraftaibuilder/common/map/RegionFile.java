package me.abdiskiosk.minecraftaibuilder.common.map;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads chunks straight from an Anvil region file ({@code r.<x>.<z>.mca}, 32x32 chunks), the format every version
 * from 1.2 to today saves in. The server is not involved: nothing is loaded into the world, so this can run on any
 * thread at any time. Chunks larger than a megabyte live in {@code c.<x>.<z>.mcc} next to it, and are read from there.
 * LZ4-compressed chunks (an opt-in setting since 1.20.5) count as unreadable.
 */
public final class RegionFile implements Closeable {

    public static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    /** A chunk decompressing to more than this is treated as corrupt rather than read. */
    static final int MAX_CHUNK_BYTES = 32 * 1024 * 1024;
    private static final int SECTOR = 4096;

    private final Path path;
    private final FileChannel channel;
    private final int rx;
    private final int rz;
    private final int[] locations = new int[1024];
    private byte[] buffer = new byte[256 * 1024];
    /** Chunks present in the file whose data could not be read, for diagnostics. */
    private int unreadable;

    private RegionFile(Path path, FileChannel channel, int rx, int rz) {
        this.path = path;
        this.channel = channel;
        this.rx = rx;
        this.rz = rz;
    }

    /** The region coordinates in a file name, or null when it is not a region file. */
    public static int[] coords(String fileName) {
        Matcher m = NAME.matcher(fileName);
        if (!m.matches()) return null;
        try {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static RegionFile open(Path path) throws IOException {
        int[] c = coords(path.getFileName().toString());
        if (c == null) throw new IOException(path.getFileName() + " is not a region file name");
        FileChannel ch = FileChannel.open(path, StandardOpenOption.READ);
        RegionFile f = new RegionFile(path, ch, c[0], c[1]);
        try {
            ByteBuffer header = ByteBuffer.allocate(SECTOR);
            while (header.hasRemaining() && ch.read(header, header.position()) > 0) {
                // read the whole header; a shorter file has no chunks
            }
            if (header.position() == SECTOR) {
                header.flip();
                header.asIntBuffer().get(f.locations);
            }
        } catch (IOException e) {
            ch.close();
            throw e;
        }
        return f;
    }

    public int regionX() {
        return rx;
    }

    public int regionZ() {
        return rz;
    }

    public int unreadable() {
        return unreadable;
    }

    /** True when the file has data for the chunk at (lx, lz), 0-31 within the region. */
    public boolean has(int lx, int lz) {
        return locations[index(lx, lz)] != 0;
    }

    /** The chunk's root compound without the tags in {@code skip}, or null when it is absent or unreadable. */
    public Map<String, Object> chunk(int lx, int lz, Set<String> skip) {
        int loc = locations[index(lx, lz)];
        if (loc == 0) return null;
        try {
            int length = decompress(loc, lx, lz);
            if (length < 0) {
                unreadable++;
                return null;
            }
            return Nbt.read(buffer, length, skip);
        } catch (IOException | RuntimeException e) {
            unreadable++;
            return null;
        }
    }

    private static int index(int lx, int lz) {
        return (lx & 31) + (lz & 31) * 32;
    }

    /** Decompresses the chunk into {@link #buffer}; returns its length, or -1 for a compression it cannot read. */
    private int decompress(int loc, int lx, int lz) throws IOException {
        long offset = (long) (loc >>> 8) * SECTOR;
        int sectors = loc & 0xFF;
        if (offset < 2L * SECTOR || sectors == 0) return -1;
        ByteBuffer head = ByteBuffer.allocate(5);
        if (readFully(head, offset) < 5) return -1;
        head.flip();
        int length = head.getInt();
        int type = head.get() & 0xFF;
        byte[] compressed;
        if ((type & 0x80) != 0) {
            Path external = path.resolveSibling("c." + (rx * 32 + lx) + "." + (rz * 32 + lz) + ".mcc");
            if (!Files.isRegularFile(external) || Files.size(external) > MAX_CHUNK_BYTES) return -1;
            compressed = Files.readAllBytes(external);
            type &= 0x7F;
        } else {
            if (length <= 1 || length > sectors * SECTOR) return -1;
            ByteBuffer data = ByteBuffer.allocate(length - 1);
            if (readFully(data, offset + 5) < length - 1) return -1;
            compressed = data.array();
        }
        InputStream in;
        switch (type) {
            case 1 -> in = new GZIPInputStream(new ByteArrayInputStream(compressed));
            case 2 -> in = new InflaterInputStream(new ByteArrayInputStream(compressed));
            case 3 -> {
                ensure(compressed.length);
                System.arraycopy(compressed, 0, buffer, 0, compressed.length);
                return compressed.length;
            }
            default -> {
                return -1;
            }
        }
        try (in) {
            int n = 0;
            while (true) {
                if (n == buffer.length) {
                    if (buffer.length >= MAX_CHUNK_BYTES) throw new IOException("chunk too large");
                    buffer = Arrays.copyOf(buffer, Math.min(MAX_CHUNK_BYTES, buffer.length * 2));
                }
                int r = in.read(buffer, n, buffer.length - n);
                if (r < 0) return n;
                n += r;
            }
        }
    }

    private void ensure(int n) throws IOException {
        if (n > MAX_CHUNK_BYTES) throw new IOException("chunk too large");
        if (buffer.length < n) buffer = new byte[n];
    }

    private int readFully(ByteBuffer b, long at) throws IOException {
        while (b.hasRemaining()) {
            int r = channel.read(b, at + b.position());
            if (r < 0) break;
        }
        return b.position();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
