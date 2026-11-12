package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class HalvesTest {

    private static Op.BlockAt at(int x, int y, int z, String state) {
        return new Op.BlockAt(new Vec3(x, y, z), state);
    }

    @Test
    void aDoorGetsItsUpperHalf() {
        List<Op.BlockAt> out = Halves.complete(List.of(at(0, 64, 0, "oak_door[facing=south,hinge=right]"), at(5, 64, 0, "stone")));
        assertEquals(List.of(at(0, 64, 0, "oak_door[facing=south,hinge=right]"),
                at(0, 65, 0, "oak_door[facing=south,hinge=right,half=upper]"), at(5, 64, 0, "stone")), out);
    }

    @Test
    void anUpperHalfGetsTheLowerOne() {
        assertEquals(at(0, 63, 0, "oak_door[half=lower]"), Halves.complete(List.of(at(0, 64, 0, "oak_door[half=upper]"))).get(1));
    }

    @Test
    void aBedGrowsTowardsItsFacing() {
        assertEquals(at(1, 64, 0, "red_bed[facing=east,part=head]"), Halves.complete(List.of(at(0, 64, 0, "red_bed[facing=east]"))).get(1));
        // Without a facing a bed faces north.
        assertEquals(at(0, 64, -1, "red_bed[part=head]"), Halves.complete(List.of(at(0, 64, 0, "red_bed"))).get(1));
        assertEquals(at(0, 64, 1, "red_bed[part=foot,facing=north]"),
                Halves.complete(List.of(at(0, 64, 0, "red_bed[part=head,facing=north]"))).get(1));
    }

    @Test
    void tallPlantsGetTheirTop() {
        assertEquals(at(0, 65, 0, "sunflower[half=upper]"), Halves.complete(List.of(at(0, 64, 0, "sunflower"))).get(1));
    }

    @Test
    void aHalfThatIsAlreadyGivenIsLeftAlone() {
        List<Op.BlockAt> both = List.of(at(0, 64, 0, "oak_door[half=lower]"), at(0, 65, 0, "oak_door[half=upper,hinge=right]"));
        assertSame(both, Halves.complete(both));
        List<Op.BlockAt> plain = List.of(at(0, 64, 0, "stone"), at(0, 65, 0, "oak_trapdoor[half=top]"));
        assertSame(plain, Halves.complete(plain));
    }
}
