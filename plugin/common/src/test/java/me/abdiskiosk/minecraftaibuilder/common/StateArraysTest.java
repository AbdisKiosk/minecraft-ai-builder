package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Region data comes from other servers and the panel's storage, so decoding must refuse anything malformed. */
class StateArraysTest {

    private interface Body {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] gz(Body body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            body.write(out);
        }
        return bytes.toByteArray();
    }

    /** Header for {@code length} states and a palette of the given states. */
    private static void header(DataOutputStream out, int length, String... palette) throws IOException {
        out.writeInt(0x53413031);
        out.writeInt(length);
        out.writeInt(palette.length);
        for (String s : palette) out.writeUTF(s);
    }

    private static void refused(byte[] data, long max, String why) {
        EditException e = assertThrows(EditException.class, () -> StateArrays.decode(data, max));
        assertTrue(e.getMessage().startsWith("corrupt region data"), e.getMessage());
        assertTrue(e.getMessage().contains(why), e.getMessage());
    }

    @Test
    void roundTrips() {
        String[] states = {"stone", "stone", "air", "oak_stairs[facing=east]", "cave_air"};
        assertArrayEquals(new String[]{"stone", "stone", "air", "oak_stairs[facing=east]", "air"}, StateArrays.decode(StateArrays.encode(states), 5));
    }

    @Test
    void refusesAPaletteIndexOutsideThePalette() throws IOException {
        refused(gz(out -> { header(out, 2, "stone"); out.writeInt(1); out.writeInt(2); }), 2, "");
        refused(gz(out -> { header(out, 2, "stone"); out.writeInt(-1); out.writeInt(2); }), 2, "");
    }

    @Test
    void refusesRunsThatOverflowOrAreNotPositive() throws IOException {
        refused(gz(out -> { header(out, 2, "stone"); out.writeInt(0); out.writeInt(3); }), 2, "bad run");
        refused(gz(out -> { header(out, 2, "stone"); out.writeInt(0); out.writeInt(Integer.MAX_VALUE); }), 2, "bad run");
        refused(gz(out -> { header(out, 2, "stone"); out.writeInt(0); out.writeInt(1); out.writeInt(0); out.writeInt(Integer.MAX_VALUE); }), 2, "bad run");
        refused(gz(out -> { header(out, 2, "stone"); out.writeInt(0); out.writeInt(0); }), 2, "bad run");
        refused(gz(out -> { header(out, 2, "stone"); out.writeInt(0); out.writeInt(-5); }), 2, "bad run");
    }

    @Test
    void refusesTrailingData() throws IOException {
        refused(gz(out -> { header(out, 1, "stone"); out.writeInt(0); out.writeInt(1); out.writeByte(0); }), 1, "trailing");
        refused(gz(out -> { header(out, 1, "stone"); out.writeInt(0); out.writeInt(1); out.writeInt(0); out.writeInt(1); }), 1, "trailing");
    }

    @Test
    void refusesTruncatedInput() throws IOException {
        byte[] good = StateArrays.encode(new String[]{"stone", "dirt", "stone", "dirt"});
        for (int cut = 0; cut < good.length; cut++) {
            byte[] part = Arrays.copyOf(good, cut);
            assertThrows(EditException.class, () -> StateArrays.decode(part, 4), "cut at " + cut);
        }
        refused(gz(out -> { header(out, 4, "stone"); out.writeInt(0); out.writeInt(2); }), 4, "");
        refused(gz(out -> { out.writeInt(0x53413031); out.writeInt(1); out.writeInt(1); out.writeShort(10); out.writeBytes("sto"); }), 1, "");
    }

    @Test
    void refusesAbsurdSizesBeforeAllocating() throws IOException {
        refused(gz(out -> header(out, Integer.MAX_VALUE)), 2_000_000, "more than");
        refused(gz(out -> header(out, -1)), 2_000_000, "more than");
        refused(gz(out -> { out.writeInt(0x53413031); out.writeInt(4); out.writeInt(Integer.MAX_VALUE); }), 4, "bad palette size");
        refused(gz(out -> { out.writeInt(0x53413031); out.writeInt(2); out.writeInt(3); }), 2, "bad palette size");
        refused(gz(out -> { out.writeInt(0x53413031); out.writeInt(4); out.writeInt(-1); }), 4, "bad palette size");
        refused(gz(out -> { header(out, 1, ""); out.writeInt(0); out.writeInt(1); }), 1, "bad palette");
        refused(gz(out -> { out.writeInt(0x12345678); out.writeInt(1); }), 1, "not a state array");
        refused(new byte[]{1, 2, 3}, 1, "");
    }
}
