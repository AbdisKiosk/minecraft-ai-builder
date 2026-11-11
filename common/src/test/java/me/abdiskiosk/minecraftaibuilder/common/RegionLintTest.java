package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RegionLintTest {

    /** A region that starts as air; cells are set with absolute coordinates. */
    private static final class Region {
        final Box box;
        final String[] states;

        Region(int x1, int y1, int z1, int x2, int y2, int z2) {
            box = Box.of(new Vec3(x1, y1, z1), new Vec3(x2, y2, z2));
            states = new String[(int) box.volume()];
            Arrays.fill(states, "air");
        }

        Region set(int x, int y, int z, String state) {
            states[RegionCodec.index(box, x, y, z)] = state;
            return this;
        }

        Region fill(int x1, int y1, int z1, int x2, int y2, int z2, String state) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    for (int x = x1; x <= x2; x++) set(x, y, z, state);
                }
            }
            return this;
        }

        List<RegionLint.Issue> check(String kind) {
            return check(kind, false);
        }

        List<RegionLint.Issue> check(String kind, boolean legacy) {
            return RegionLint.check(box, states, Set.of(kind), legacy);
        }

        RegionLint.Issue only(String kind) {
            List<RegionLint.Issue> issues = check(kind);
            assertEquals(1, issues.size(), issues.toString());
            return issues.get(0);
        }
    }

    private static Region ground() {
        return new Region(0, 0, 0, 6, 6, 6).fill(0, 0, 0, 6, 0, 6, "stone");
    }

    @Test
    void floatingFindsDetachedPiecesAndNamesTheirLowestBlock() {
        Region r = ground().set(3, 3, 3, "stone").set(3, 4, 3, "stone").set(1, 5, 1, "oak_planks");
        RegionLint.Issue issue = r.only("floating");
        assertEquals(2, issue.count());
        assertEquals(List.of(new Vec3(3, 3, 3), new Vec3(1, 5, 1)), issue.at());
        assertEquals("floating", issue.kind());

        // Connected to the ground through a pillar, or touching a side face of the box: not floating.
        Region ok = ground().fill(3, 1, 3, 3, 4, 3, "stone").set(0, 4, 2, "stone");
        assertTrue(ok.check("floating").isEmpty());
    }

    @Test
    void stepsThatTouchAlongEdgesAreOnePiece() {
        // A staircase or a stair roof: each step is one up and one along, so it shares only an edge with the next.
        Region steps = ground().set(2, 1, 2, "stone").set(3, 2, 2, "oak_stairs[facing=east]").set(4, 3, 2, "oak_stairs[facing=east]");
        assertTrue(steps.check("floating").isEmpty());
        // Corner to corner is not touching.
        assertEquals(1, ground().set(2, 1, 2, "stone").set(3, 2, 3, "stone").only("floating").count());
    }

    @Test
    void hangingLanternUnderACeilingIsFine() {
        Region r = ground().fill(2, 1, 2, 2, 4, 2, "oak_log").set(3, 4, 2, "oak_planks").set(3, 3, 2, "lantern[hanging=true]");
        assertTrue(r.check("floating").isEmpty());
        assertTrue(r.check("unsupported").isEmpty());

        // A standing lantern in the same spot has nothing below it.
        r.set(3, 3, 2, "lantern");
        assertEquals(List.of(new Vec3(3, 3, 2)), r.only("unsupported").at());
    }

    @Test
    void unsupportedFindsFallingAndHangingBlocks() {
        Region r = ground().fill(1, 1, 1, 1, 3, 1, "stone")
                .set(2, 3, 1, "sand")            // nothing below
                .set(3, 1, 3, "torch")           // on the floor: fine
                .set(1, 2, 2, "wall_torch[facing=south]")  // hangs on (1,2,1): fine
                .set(4, 2, 4, "wall_torch[facing=east]")   // (3,2,4) is air
                .set(5, 2, 1, "ladder");          // facing north by default, hangs on (5,2,2): air
        RegionLint.Issue issue = r.only("unsupported");
        assertEquals(3, issue.count());
        assertEquals(List.of(new Vec3(5, 2, 1), new Vec3(4, 2, 4), new Vec3(2, 3, 1)), issue.at());
    }

    @Test
    void nothingIsReportedThatDependsOnBlocksOutsideTheBox() {
        Region r = new Region(0, 0, 0, 2, 2, 2)
                .set(1, 0, 1, "sand")                     // below is outside
                .set(0, 1, 1, "wall_torch[facing=east]")  // support is outside
                .set(1, 1, 1, "stone").set(1, 2, 1, "oak_door") // upper half would be outside
                .set(2, 1, 1, "red_bed[facing=east]")     // head would be outside
                .set(1, 1, 0, "oak_stairs[facing=north]");// in front is outside
        assertTrue(RegionLint.check(r.box, r.states, null, false).stream()
                .noneMatch(i -> !i.kind().equals("floating")), RegionLint.check(r.box, r.states, null, false).toString());
    }

    @Test
    void halvesFindsLoneDoorAndBedParts() {
        Region r = ground()
                .set(1, 1, 1, "oak_door[facing=east]").set(1, 2, 1, "oak_door[facing=east,half=upper]")
                .set(3, 1, 1, "spruce_door").set(3, 2, 1, "oak_door[half=upper]")
                .set(1, 1, 3, "red_bed[facing=east]").set(2, 1, 3, "red_bed[facing=east,part=head]")
                .set(1, 1, 5, "blue_bed[part=head,facing=south]");
        RegionLint.Issue issue = r.only("halves");
        assertEquals(List.of(new Vec3(3, 1, 1), new Vec3(1, 1, 5), new Vec3(3, 2, 1)), issue.at());
    }

    /** A gable roof over x 0..6: the west slope rises east, the east slope rises west, a ridge at x=3. */
    private static Region roof(String eastSlopeFacing) {
        Region r = new Region(0, 0, 0, 6, 3, 0);
        for (int i = 0; i < 3; i++) {
            r.set(i, i, 0, "oak_stairs[facing=east]");
            r.set(6 - i, i, 0, "oak_stairs[facing=" + eastSlopeFacing + "]");
        }
        return r.set(3, 3, 0, "oak_planks");
    }

    @Test
    void stairsFlagsOneReversedRoofSlope() {
        assertTrue(roof("west").check("stairs").isEmpty());
        RegionLint.Issue issue = roof("east").only("stairs");
        assertEquals(List.of(new Vec3(5, 1, 0), new Vec3(4, 2, 0)), issue.at());
        assertTrue(issue.hint().contains("face it the way the slope rises"));
    }

    @Test
    void openStaircaseIsNotFlagged() {
        Region up = new Region(0, 0, 0, 4, 4, 0);
        for (int i = 0; i < 5; i++) up.set(i, i, 0, "stone_brick_stairs[facing=east]");
        assertTrue(up.check("stairs").isEmpty());

        Region wrong = new Region(0, 0, 0, 4, 4, 0);
        for (int i = 0; i < 5; i++) wrong.set(i, 4 - i, 0, "stone_brick_stairs[facing=east]");
        assertEquals(3, wrong.only("stairs").count());
    }

    @Test
    void unconnectedFindsFencesWithoutConnectionStates() {
        Region r = ground().set(1, 1, 1, "oak_fence").set(2, 1, 1, "oak_fence")
                .set(4, 1, 4, "oak_fence")                                   // alone: nothing to connect to
                .set(1, 1, 4, "glass_pane").set(1, 1, 5, "stone")            // next to a solid block
                .set(5, 1, 1, "cobblestone_wall[north=none,east=none,south=none,west=none]").set(5, 1, 2, "cobblestone_wall[north=low]");
        RegionLint.Issue issue = r.only("unconnected");
        assertEquals(List.of(new Vec3(1, 1, 1), new Vec3(2, 1, 1), new Vec3(5, 1, 1), new Vec3(1, 1, 4)), issue.at());
        assertTrue(r.check("unconnected", true).isEmpty());

        Region ok = ground().set(1, 1, 1, "oak_fence[east=true]").set(2, 1, 1, "oak_fence[west=true]");
        assertTrue(ok.check("unconnected").isEmpty());
    }

    /** A closed 5x5 stone room (inside 1..3) with a floor at y=0, walls up to y=4 and a roof at y=5. */
    private static Region room() {
        Region r = new Region(0, 0, 0, 4, 5, 4);
        r.fill(0, 0, 0, 4, 5, 4, "stone");
        return r.fill(1, 1, 1, 3, 4, 3, "air");
    }

    @Test
    void darkFindsUnlitRoofedFloor() {
        RegionLint.Issue issue = room().only("dark");
        assertEquals(9, issue.count());
        assertEquals(List.of(new Vec3(1, 1, 1)), issue.at());

        assertTrue(room().set(2, 1, 2, "torch").check("dark").isEmpty());
        assertTrue(room().set(2, 4, 2, "lantern[hanging=true]").check("dark").isEmpty());
        // A lamp that is off gives no light.
        assertEquals(9, room().set(2, 3, 2, "redstone_lamp").only("dark").count());
    }

    @Test
    void darkNeedsARoofAndLightIsStoppedByWalls() {
        Region open = room().fill(1, 5, 1, 3, 5, 3, "air");
        assertTrue(open.check("dark").isEmpty());

        // A torch outside the room does not light it through stone, but does through glass.
        Region big = new Region(0, 0, 0, 6, 5, 4);
        big.fill(0, 0, 0, 4, 5, 4, "stone").fill(1, 1, 1, 3, 4, 3, "air").fill(0, 0, 0, 6, 0, 4, "stone").set(5, 1, 2, "torch");
        assertEquals(9, big.only("dark").count());
        big.fill(4, 1, 1, 4, 4, 3, "glass");
        assertTrue(big.check("dark").isEmpty());
    }

    @Test
    void cleanBuildHasNoIssuesAndChecksCanBeChosen() {
        Region r = ground().fill(2, 1, 2, 2, 3, 2, "oak_log").set(2, 1, 3, "torch");
        assertTrue(RegionLint.check(r.box, r.states, null, false).isEmpty());
        r.set(5, 4, 5, "stone");
        assertEquals(1, RegionLint.check(r.box, r.states, null, false).size());
        assertTrue(RegionLint.check(r.box, r.states, Set.of("dark"), false).isEmpty());
    }

    @Test
    void atHoldsAtMostFivePositions() {
        Region r = ground();
        for (int x = 1; x <= 5; x++) {
            r.set(x, 2, 2, "sand");
            r.set(x, 2, 4, "sand");
        }
        RegionLint.Issue issue = r.only("unsupported");
        assertEquals(10, issue.count());
        assertEquals(5, issue.at().size());
        assertEquals(new Vec3(1, 2, 2), issue.at().get(0));
    }
}
