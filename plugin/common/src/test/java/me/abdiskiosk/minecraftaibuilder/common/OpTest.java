package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpTest {

    @Test
    void fillSplitsIntoSlabsCoveringTheBox() {
        Box box = Box.of(new Vec3(0, 0, 0), new Vec3(9, 9, 9)); // 100 blocks per layer
        List<Op> parts = new Op.Fill(box, "stone").split(250);
        assertEquals(5, parts.size());
        assertEquals(box.volume(), parts.stream().mapToLong(Op::volume).sum());
        assertEquals(new Vec3(0, 8, 0), ((Op.Fill) parts.get(4)).box().min());
    }

    @Test
    void splitKeepsAtLeastOneLayer() {
        Box box = Box.of(new Vec3(0, 0, 0), new Vec3(99, 1, 99));
        assertEquals(2, new Op.Walls(box, "stone").split(10).size());
    }

    @Test
    void blocksSplitIntoChunks() {
        List<Op.BlockAt> blocks = new ArrayList<>();
        for (int i = 0; i < 7; i++) blocks.add(new Op.BlockAt(new Vec3(i, 0, 0), "stone"));
        List<Op> parts = new Op.Blocks(blocks).split(3);
        assertEquals(3, parts.size());
        assertEquals(Box.of(new Vec3(0, 0, 0), new Vec3(6, 0, 0)), new Op.Blocks(blocks).bounds());
    }

    @Test
    void pasteBoundsSwapAxesWhenRotated() {
        Blueprint bp = Blueprint.of(java.util.Map.of('S', "stone"), List.of(List.of("SSSS"), List.of("SSSS")));
        Op.Paste rotated = new Op.Paste(bp, new Vec3(0, 64, 0), 90);
        assertEquals(Box.of(new Vec3(0, 64, 0), new Vec3(0, 65, 3)), rotated.bounds());
    }

    private static java.util.Set<Vec3> cells(Op op) {
        java.util.Set<Vec3> out = new java.util.HashSet<>();
        Box b = op.bounds();
        for (int y = b.min().y(); y <= b.max().y(); y++) {
            for (int z = b.min().z(); z <= b.max().z(); z++) {
                for (int x = b.min().x(); x <= b.max().x(); x++) {
                    boolean wall = x == b.min().x() || x == b.max().x() || z == b.min().z() || z == b.max().z();
                    if (op instanceof Op.Fill || wall) out.add(new Vec3(x, y, z));
                }
            }
        }
        return out;
    }

    @Test
    void outlineSplitCoversExactlyTheSixFaces() {
        Box box = Box.of(new Vec3(0, 0, 0), new Vec3(9, 9, 9));
        java.util.Set<Vec3> expected = new java.util.HashSet<>();
        for (int y = 0; y <= 9; y++) for (int z = 0; z <= 9; z++) for (int x = 0; x <= 9; x++) {
            if (x == 0 || x == 9 || y == 0 || y == 9 || z == 0 || z == 9) expected.add(new Vec3(x, y, z));
        }
        List<Op> parts = new Op.Outline(box, "stone").split(300);
        assertTrue(parts.size() > 1);
        java.util.Set<Vec3> got = new java.util.HashSet<>();
        parts.forEach(p -> got.addAll(cells(p)));
        assertEquals(expected, got);
        assertEquals(1, new Op.Outline(box, "stone").split(5000).size(), "small outlines stay one piece");
    }

    @Test
    void cylinderSplitStacksTheSameShape() {
        Op.Cylinder cyl = new Op.Cylinder(new Vec3(5, 64, 5), "stone", 5, 3, 40, true);
        List<Op> parts = cyl.split(500);
        assertTrue(parts.size() > 1);
        int y = 64;
        for (Op p : parts) {
            Op.Cylinder c = (Op.Cylinder) p;
            assertEquals(y, c.base().y());
            assertEquals(5, c.radiusX());
            assertEquals(3, c.radiusZ());
            assertTrue(c.hollow());
            y += c.height();
        }
        assertEquals(64 + 40, y);
        assertEquals(1, new Op.Cylinder(new Vec3(0, 0, 0), "stone", 2, 2, 3, false).split(5000).size());
    }

    @Test
    void pasteSplitKeepsTheFootprintAndEveryCell() {
        List<List<String>> layers = new ArrayList<>();
        for (int y = 0; y < 6; y++) layers.add(List.of("SSSS", "SSSS", y == 5 ? "S" : "SSSS"));
        Blueprint bp = Blueprint.of(java.util.Map.of('S', "stone"), layers);
        for (int rotation : new int[]{0, 90}) {
            Op.Paste paste = new Op.Paste(bp, new Vec3(10, 64, 10), rotation);
            List<Op> parts = paste.split(30);
            assertTrue(parts.size() > 1);
            long cells = 0;
            int expectedY = 64;
            for (Op p : parts) {
                Op.Paste piece = (Op.Paste) p;
                assertEquals(bp.sizeX(), piece.blueprint().sizeX());
                assertEquals(bp.sizeZ(), piece.blueprint().sizeZ());
                assertEquals(expectedY, piece.origin().y());
                expectedY += piece.blueprint().sizeY();
                cells += piece.blueprint().cellCount();
            }
            assertEquals(bp.cellCount(), cells);
            assertEquals(64 + 6, expectedY);
        }
    }

    @Test
    void opsExposeTheirPatternsAndMasks() {
        Box box = Box.of(new Vec3(0, 0, 0), new Vec3(1, 1, 1));
        assertEquals(List.of("stone"), new Op.Fill(box, "stone").patterns());
        Op.Replace r = new Op.Replace(box, "dirt", "stone");
        assertEquals(List.of("dirt"), r.masks());
        assertEquals(List.of("stone"), r.patterns());
        assertEquals(List.of("stone", "glass"), new Op.Blocks(List.of(new Op.BlockAt(new Vec3(0, 0, 0), "stone"),
                new Op.BlockAt(new Vec3(1, 0, 0), "glass"), new Op.BlockAt(new Vec3(2, 0, 0), "stone"))).patterns());
        assertEquals("fill", new Op.Fill(box, "stone").kind());
    }

    @Test
    void boxNormalises() {
        Box box = Box.of(new Vec3(5, 1, -3), new Vec3(1, 4, 2));
        assertEquals(new Vec3(1, 1, -3), box.min());
        assertEquals(120, box.volume());
    }

    @Test
    void smoothCoversItsBoxInOnePiece() {
        Box box = Box.of(new Vec3(0, 60, 0), new Vec3(9, 69, 9));
        Op.Smooth smooth = new Op.Smooth(box, 2);
        assertEquals(box, smooth.bounds());
        assertEquals(1000, smooth.volume());
        assertEquals(List.of(smooth), smooth.split(10));
        assertTrue(smooth.patterns().isEmpty());
        assertEquals("smooth", smooth.kind());
    }
}
