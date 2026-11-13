package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnchorTest {
    /** 3 wide (x), 2 high, 2 long (z): no quarter turn maps it onto itself. */
    private static final Blueprint BP = Blueprint.of(Map.of('S', "stone", 'P', "oak_planks"),
            List.of(List.of("SSS", "SSP"), List.of("SSS", "SSS")));
    private static final Vec3 AT = new Vec3(10, 64, 10);

    @Test
    void eachQuarterTurnPutsTheAnchorCellAtTheGivenPosition() {
        Anchor anchor = new Anchor(new Vec3(2, 1, 0), "north");
        // facing -> rotation (clockwise from north) and the origin that puts cell 2,1,0 at AT after that rotation
        Object[][] cases = {
                {"north", 0, new Vec3(8, 63, 10)},  // cell stays at 2,1,0
                {"east", 90, new Vec3(9, 63, 8)},   // at sizeZ-1-z, y, x = 1,1,2
                {"south", 180, new Vec3(10, 63, 9)}, // at sizeX-1-x, y, sizeZ-1-z = 0,1,1
                {"west", 270, new Vec3(10, 63, 10)}, // at z, y, sizeX-1-x = 0,1,0
        };
        for (Object[] c : cases) {
            Op.Paste paste = anchor.paste(BP, AT, (String) c[0], 0);
            assertEquals(c[1], paste.rotation(), (String) c[0]);
            assertEquals(c[2], paste.origin(), (String) c[0]);
            assertSame(BP, paste.blueprint());
            Box b = paste.bounds();
            assertTrue(AT.x() >= b.min().x() && AT.x() <= b.max().x() && AT.z() >= b.min().z() && AT.z() <= b.max().z(),
                    "the anchor lies in the pasted box for " + c[0]);
        }
        // A stored facing other than north turns relative to it.
        assertEquals(90, new Anchor(new Vec3(0, 0, 0), "west").paste(BP, AT, "north", 0).rotation());
        assertEquals(270, new Anchor(new Vec3(0, 0, 0), "south").paste(BP, AT, "east", 0).rotation());
    }

    @Test
    void rotationWithoutFacingTurnsTheAnchorToo() {
        Op.Paste paste = new Anchor(new Vec3(2, 1, 0), null).paste(BP, AT, null, 90);
        assertEquals(90, paste.rotation());
        assertEquals(new Vec3(9, 63, 8), paste.origin());
        assertEquals(new Vec3(9, 63, 8), new Anchor(new Vec3(2, 1, 0), null).paste(BP, AT, null, -270).origin());
    }

    @Test
    void withoutAnAnchorAtIsTheMinimumCorner() {
        for (int rotation : new int[]{0, 90, 180, 270}) {
            Op.Paste paste = Anchor.NONE.paste(BP, AT, null, rotation);
            assertEquals(AT, paste.origin());
            assertEquals(rotation, paste.rotation());
        }
        // A facing without an anchor cell turns the asset; the minimum corner still goes at AT.
        Op.Paste turned = new Anchor(null, "north").paste(BP, AT, "south", 0);
        assertEquals(new Op.Paste(BP, AT, 180), turned);
    }

    @Test
    void facingNeedsAStoredFacingAndTurnsOnlyHorizontally() {
        EditException legacy = assertThrows(EditException.class, () -> Anchor.NONE.paste(BP, AT, "north", 0));
        assertEquals("this blueprint has no stored facing; use origin and rotation (an asset's owner can set one on the panel)", legacy.getMessage());
        assertThrows(EditException.class, () -> new Anchor(new Vec3(0, 0, 0), null).paste(BP, AT, "north", 0));
        assertEquals("facing must be north, south, east, west, up or down",
                assertThrows(EditException.class, () -> new Anchor(null, "north").paste(BP, AT, "sideways", 0)).getMessage());

        Anchor floor = new Anchor(new Vec3(1, 0, 1), "up");
        assertEquals("this asset faces up; paste turns only between north, south, east and west, so use facing=up or leave it out",
                assertThrows(EditException.class, () -> floor.paste(BP, AT, "north", 0)).getMessage());
        assertThrows(EditException.class, () -> floor.paste(BP, AT, "down", 0));
        assertEquals(new Op.Paste(BP, new Vec3(9, 64, 9), 0), floor.paste(BP, AT, "up", 0));
        assertEquals(90, floor.paste(BP, AT, null, 90).rotation(), "rotation still turns it around its own axis");
        assertEquals("this asset faces east; paste turns only between north, south, east and west, so facing must be one of those",
                assertThrows(EditException.class, () -> new Anchor(null, "east").paste(BP, AT, "down", 0)).getMessage());
    }

    @Test
    void rotationBetweenFacingsIsClockwise() {
        assertEquals(0, Anchor.rotation("north", "north"));
        assertEquals(90, Anchor.rotation("north", "east"));
        assertEquals(180, Anchor.rotation("east", "west"));
        assertEquals(270, Anchor.rotation("north", "west"));
        assertEquals(90, Anchor.rotation("west", "north"));
    }
}
