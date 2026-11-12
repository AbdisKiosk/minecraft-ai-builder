package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardScanTest {

    /** A stand-in region: the highest priority region at a point decides, as in WorldGuard. */
    private record Region(String name, int priority, boolean member, Box bounds, boolean cuboid) implements GuardScan.Shape {
        static Region cuboid(String name, int priority, boolean member, int x1, int y1, int z1, int x2, int y2, int z2) {
            return new Region(name, priority, member, Box.of(new Vec3(x1, y1, z1), new Vec3(x2, y2, z2)), true);
        }

        /** A diamond |x - cx| + |z - cz| <= r over all of y, like a polygonal region. */
        static Region diamond(String name, int cx, int cz, int r) {
            return new Region(name, 0, false, Box.of(new Vec3(cx - r, -64, cz - r), new Vec3(cx + r, 319, cz + r)), false);
        }

        @Override
        public boolean contains(int x, int y, int z) {
            if (y < bounds.min().y() || y > bounds.max().y()) return false;
            if (cuboid) {
                return x >= bounds.min().x() && x <= bounds.max().x() && z >= bounds.min().z() && z <= bounds.max().z();
            }
            int cx = (bounds.min().x() + bounds.max().x()) / 2, cz = (bounds.min().z() + bounds.max().z()) / 2;
            return Math.abs(x - cx) + Math.abs(z - cz) <= (bounds.max().x() - bounds.min().x()) / 2;
        }
    }

    private static Function<Vec3, String> world(List<Region> regions, AtomicInteger asked) {
        return p -> {
            asked.incrementAndGet();
            return regions.stream().filter(r -> r.contains(p.x(), p.y(), p.z()))
                    .max(Comparator.comparingInt(Region::priority))
                    .filter(r -> !r.member()).map(Region::name).orElse(null);
        };
    }

    private static List<String> scan(Box box, List<Region> all) {
        List<Region> touching = all.stream().filter(r -> overlaps(r.bounds(), box)).toList();
        return GuardScan.denied(box, touching, world(all, new AtomicInteger()), 5, GuardScan.MAX_CELLS);
    }

    private static List<String> bruteForce(Box box, List<Region> all) {
        Function<Vec3, String> at = world(all, new AtomicInteger());
        List<String> names = new ArrayList<>();
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    String d = at.apply(new Vec3(x, y, z));
                    if (d != null && !names.contains(d)) names.add(d);
                }
            }
        }
        return names;
    }

    private static boolean overlaps(Box a, Box b) {
        return a.min().x() <= b.max().x() && a.max().x() >= b.min().x() && a.min().y() <= b.max().y()
                && a.max().y() >= b.min().y() && a.min().z() <= b.max().z() && a.max().z() >= b.min().z();
    }

    private static Box box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return Box.of(new Vec3(x1, y1, z1), new Vec3(x2, y2, z2));
    }

    private final List<Region> town = List.of(
            Region.cuboid("town", 0, false, 0, -64, 0, 99, 319, 99),
            Region.cuboid("plot", 10, true, 20, 60, 20, 29, 90, 29));

    @Test
    void aPlotInsideAProtectedTownIsBuildableForItsMembers() {
        assertEquals(List.of(), scan(box(20, 60, 20, 29, 90, 29), town));
        assertEquals(List.of(), scan(box(22, 64, 22, 25, 70, 25), town));
    }

    @Test
    void reachingOutOfThePlotIsRefusedWithTheRegionThatForbidsIt() {
        assertEquals(List.of("town"), scan(box(20, 60, 20, 30, 90, 29), town));
        assertEquals(List.of("town"), scan(box(20, 91, 20, 29, 91, 29), town), "above the plot");
    }

    @Test
    void wildernessIsAskedOnceAtTheBoxCorner() {
        AtomicInteger asked = new AtomicInteger();
        assertEquals(List.of(), GuardScan.denied(box(500, 0, 500, 900, 200, 900), List.of(), world(List.of(), asked), 5, GuardScan.MAX_CELLS));
        assertEquals(1, asked.get());
        List<String> global = GuardScan.denied(box(500, 0, 500, 900, 200, 900), List.of(), p -> "__global__", 5, GuardScan.MAX_CELLS);
        assertEquals(List.of("__global__"), global);
    }

    @Test
    void cuboidsAreAskedOncePerDistinctSetOfRegions() {
        AtomicInteger asked = new AtomicInteger();
        GuardScan.denied(box(-50, 0, -50, 150, 100, 150), town, world(town, asked), 5, GuardScan.MAX_CELLS);
        assertTrue(asked.get() <= 3, "asked " + asked.get() + " times"); // nothing, town, town + plot
    }

    @Test
    void polygonsAreCheckedColumnByColumn() {
        List<Region> diamond = List.of(Region.diamond("hill", 0, 0, 10));
        assertEquals(List.of(), scan(box(6, 64, 6, 10, 70, 10), diamond), "inside the bounds, outside the diamond");
        assertEquals(List.of("hill"), scan(box(5, 64, 5, 10, 70, 10), diamond), "touches the diamond at 5,5");
    }

    @Test
    void agreesWithCheckingEveryBlock() {
        List<Region> regions = new ArrayList<>(town);
        regions.add(Region.diamond("hill", 50, 50, 8));
        regions.add(Region.cuboid("vault", 20, false, 24, 70, 24, 25, 72, 25));
        Random random = new Random(7);
        for (int i = 0; i < 300; i++) {
            int x = random.nextInt(140) - 20, y = 50 + random.nextInt(50), z = random.nextInt(140) - 20;
            Box b = box(x, y, z, x + random.nextInt(20), y + random.nextInt(20), z + random.nextInt(20));
            assertEquals(bruteForce(b, regions).stream().sorted().toList(), scan(b, regions).stream().sorted().toList(), b.toString());
        }
    }

    @Test
    void stopsAtTheLimit() {
        List<Region> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) many.add(Region.cuboid("r" + i, 0, false, i * 10, 0, 0, i * 10 + 9, 10, 9));
        assertEquals(3, GuardScan.denied(box(0, 0, 0, 99, 10, 9), many, world(many, new AtomicInteger()), 3, GuardScan.MAX_CELLS).size());
    }

    @Test
    void refusesBoxesCutIntoTooManyCells() {
        List<Region> diamond = List.of(Region.diamond("hill", 0, 0, 100));
        EditException e = assertThrows(EditException.class, () -> GuardScan.denied(box(-100, 0, -100, 100, 100, 100), diamond,
                p -> null, 5, 10_000));
        assertTrue(e.getMessage().contains("smaller boxes"), e.getMessage());
    }
}
