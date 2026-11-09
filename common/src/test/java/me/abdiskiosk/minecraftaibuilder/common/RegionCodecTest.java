package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RegionCodecTest {

    private static final Box BOX = Box.of(new Vec3(10, 60, -5), new Vec3(13, 61, -4)); // 4 x 2 x 2

    private static String[] sample() {
        String[] s = new String[(int) BOX.volume()];
        for (int z = -5; z <= -4; z++) {
            for (int x = 10; x <= 13; x++) {
                s[RegionCodec.index(BOX, x, 60, z)] = "stone";
                s[RegionCodec.index(BOX, x, 61, z)] = x == 12 ? "oak_stairs[facing=east]" : "air";
            }
        }
        return s;
    }

    @Test
    void indexIsYThenZThenX() {
        assertEquals(0, RegionCodec.index(BOX, 10, 60, -5));
        assertEquals(1, RegionCodec.index(BOX, 11, 60, -5));
        assertEquals(4, RegionCodec.index(BOX, 10, 60, -4));
        assertEquals(8, RegionCodec.index(BOX, 10, 61, -5));
    }

    @Test
    void summaryCountsAndBounds() {
        RegionCodec.Summary sum = RegionCodec.summarize(BOX, sample(), 10, true);
        assertEquals(10, sum.nonAir());
        assertEquals(8L, sum.counts().get("stone"));
        assertEquals(2L, sum.counts().get("oak_stairs"));
        assertEquals(Box.of(new Vec3(10, 60, -5), new Vec3(13, 61, -4)), sum.nonAirBounds());
        assertEquals(60, sum.surfaceMin());
        assertEquals(61, sum.surfaceMax());
        assertEquals(List.of("0,0,1,0", "0,0,1,0"), sum.heightmap());
    }

    @Test
    void encodeRoundTripsThroughBlueprint() {
        String[] states = sample();
        RegionCodec.Encoded enc = RegionCodec.encode(BOX, states);
        assertEquals(List.of(List.of("4A", "4A"), List.of("__B_", "__B_")), enc.layers());
        Blueprint bp = Blueprint.of(enc.palette(), enc.layers());
        for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 4; x++) {
                    String expected = states[RegionCodec.index(BOX, 10 + x, 60 + y, -5 + z)];
                    assertEquals(expected, bp.palette().get(bp.at(x, y, z)));
                }
            }
        }
        assertEquals(Map.of('A', "stone", '_', "air", 'B', "oak_stairs[facing=east]"), enc.palette());
    }
}
