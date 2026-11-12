package me.abdiskiosk.minecraftaibuilder.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** A region's states in {@link RegionCodec#index} order as compact bytes (palette + run lengths, gzipped). */
public final class StateArrays {

    private static final int MAGIC = 0x53413031; // "SA01"

    private StateArrays() {
    }

    public static byte[] encode(String[] states) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
                Map<String, Integer> palette = new LinkedHashMap<>();
                for (String s : states) palette.putIfAbsent(RegionCodec.isAir(s) ? "air" : s, palette.size());
                out.writeInt(MAGIC);
                out.writeInt(states.length);
                out.writeInt(palette.size());
                for (String s : palette.keySet()) out.writeUTF(s);
                int i = 0;
                while (i < states.length) {
                    int p = palette.get(RegionCodec.isAir(states[i]) ? "air" : states[i]);
                    int run = 1;
                    while (i + run < states.length && palette.get(RegionCodec.isAir(states[i + run]) ? "air" : states[i + run]) == p) run++;
                    out.writeInt(p);
                    out.writeInt(run);
                    i += run;
                }
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @param maxLength refuses data that claims more states than this */
    public static String[] decode(byte[] data, long maxLength) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)))) {
            if (in.readInt() != MAGIC) throw new IOException("not a state array");
            int length = in.readInt();
            if (length < 0 || length > maxLength) throw new IOException(length + " states is more than " + maxLength);
            String[] palette = new String[in.readInt()];
            for (int i = 0; i < palette.length; i++) palette[i] = in.readUTF();
            String[] out = new String[length];
            int i = 0;
            while (i < length) {
                String s = palette[in.readInt()];
                int run = in.readInt();
                if (run <= 0 || i + run > length) throw new IOException("bad run");
                java.util.Arrays.fill(out, i, i + run, s);
                i += run;
            }
            return out;
        } catch (IOException | ArrayIndexOutOfBoundsException e) {
            throw new EditException("corrupt region data: " + e.getMessage(), e);
        }
    }
}
