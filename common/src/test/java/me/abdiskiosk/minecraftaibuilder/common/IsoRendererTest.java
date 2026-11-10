package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class IsoRendererTest {

    @Test
    void rendersPng() throws Exception {
        System.setProperty("java.awt.headless", "true");
        Box box = Box.of(new Vec3(0, 0, 0), new Vec3(15, 7, 15));
        String[] states = new String[(int) box.volume()];
        for (int i = 0; i < states.length; i++) states[i] = i % 3 == 0 ? "stone_bricks" : "air";
        byte[] png = IsoRenderer.renderPng(box, states, IsoRenderer.View.BOTH);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img);
        assertTrue(img.getWidth() > 100 && img.getWidth() <= 1500, "width " + img.getWidth());
    }

    @Test
    void cropsToTheBlocks() {
        Box box = Box.of(new Vec3(0, 0, 0), new Vec3(63, 15, 63));
        String[] states = new String[(int) box.volume()];
        java.util.Arrays.fill(states, "air");
        for (int x = 30; x <= 33; x++) {
            for (int y = 5; y <= 7; y++) {
                for (int z = 30; z <= 33; z++) states[RegionCodec.index(box, x, y, z)] = "stone_bricks";
            }
        }
        IsoRenderer.Rendered small = IsoRenderer.render(box, states, IsoRenderer.View.BOTH, IsoRenderer.Size.LARGE);
        assertEquals(Box.of(new Vec3(29, 4, 29), new Vec3(34, 8, 34)), small.box());
        // 6 blocks across at the largest cell size: nowhere near the size cap.
        assertTrue(small.width() <= 300 && small.height() <= 300, small.width() + "x" + small.height());

        java.util.Arrays.fill(states, "air");
        assertEquals(box, IsoRenderer.render(box, states, IsoRenderer.View.TOP, IsoRenderer.Size.SMALL).box());
    }

    @Test
    void sizeCapsTheCanvas() throws Exception {
        Box box = Box.of(new Vec3(0, 0, 0), new Vec3(63, 15, 63));
        String[] states = new String[(int) box.volume()];
        java.util.Arrays.fill(states, "stone");
        long before = 0;
        for (IsoRenderer.Size size : IsoRenderer.Size.values()) {
            for (IsoRenderer.View view : IsoRenderer.View.values()) {
                IsoRenderer.Rendered r = IsoRenderer.render(box, states, view, size);
                long pixels = (long) r.width() * r.height();
                assertTrue(pixels <= (long) size.maxWidth * size.maxHeight, size + " " + view + " is " + r.width() + "x" + r.height());
                assertTrue(Math.max(r.width(), r.height()) <= size.maxWidth, size + " " + view + " is " + r.width() + "x" + r.height());
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(r.png()));
                assertEquals(r.width(), img.getWidth());
                assertEquals(r.height(), img.getHeight());
                if (view == IsoRenderer.View.BOTH) {
                    assertTrue(pixels > before, "a larger size gives a larger picture");
                    before = pixels;
                }
            }
        }
    }

    @Test
    void coloursPreferSpecificKeywords() {
        assertNotEquals(BlockColors.rgb("sandstone"), BlockColors.rgb("red_sandstone"));
        assertEquals(-1, BlockColors.rgb("cave_air"));
        assertEquals(BlockColors.rgb("stone_brick_wall"), BlockColors.rgb("stone_bricks"));
        assertNotEquals(BlockColors.rgb("bricks"), BlockColors.rgb("stone_bricks"));
        assertEquals(BlockColors.rgb("light_blue_wool"), BlockColors.rgb("light_blue_concrete"));
    }

    private static final int BACKGROUND = 0x1E2228;

    private static BufferedImage image(IsoRenderer.Rendered r) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(r.png()));
    }

    private static int rgb(BufferedImage img, int x, int y) {
        return img.getRGB(x, y) & 0xFFFFFF;
    }

    private static boolean samePixels(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        return Arrays.equals(a.getRGB(0, 0, a.getWidth(), a.getHeight(), null, 0, a.getWidth()),
                b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth()));
    }

    /** 5 x 3 x 4: a stone floor, a gold tower in the north-west corner, a diamond block south-east, planks north. */
    private static String[] asymmetric(Box box) {
        String[] states = new String[(int) box.volume()];
        Arrays.fill(states, "air");
        for (int x = 0; x < 5; x++) for (int z = 0; z < 4; z++) states[RegionCodec.index(box, x, 0, z)] = "stone";
        states[RegionCodec.index(box, 0, 1, 0)] = "gold_block";
        states[RegionCodec.index(box, 0, 2, 0)] = "gold_block";
        states[RegionCodec.index(box, 4, 1, 3)] = "diamond_block";
        states[RegionCodec.index(box, 2, 1, 0)] = "oak_planks";
        return states;
    }

    @Test
    void cornersGiveDifferentPictures() throws Exception {
        Box box = Box.of(new Vec3(0, 0, 0), new Vec3(4, 2, 3));
        String[] states = asymmetric(box);
        IsoRenderer.Corner[] corners = IsoRenderer.Corner.values();
        BufferedImage[] images = new BufferedImage[corners.length];
        for (int i = 0; i < corners.length; i++) {
            images[i] = image(IsoRenderer.render(box, states, IsoRenderer.View.ISO, corners[i], IsoRenderer.Size.MEDIUM));
        }
        for (int i = 0; i < corners.length; i++) {
            for (int j = i + 1; j < corners.length; j++) assertFalse(samePixels(images[i], images[j]), corners[i] + " vs " + corners[j]);
        }
        // The old entry point is the south-east view.
        assertTrue(samePixels(images[0], image(IsoRenderer.render(box, states, IsoRenderer.View.ISO, IsoRenderer.Size.MEDIUM))));
    }

    @Test
    void cornersRotateTheBuildInsteadOfMirroringIt() throws Exception {
        Box box = Box.of(new Vec3(0, 0, 0), new Vec3(4, 2, 3));
        String[] states = asymmetric(box);
        int sx = box.sizeX(), sy = box.sizeY(), sz = box.sizeZ();
        for (IsoRenderer.Corner corner : new IsoRenderer.Corner[]{IsoRenderer.Corner.SW, IsoRenderer.Corner.NW, IsoRenderer.Corner.NE}) {
            // Turn the build counter-clockwise (seen from above) until the corner the camera stands at is the south-east one.
            int turns = corner == IsoRenderer.Corner.SW ? 1 : corner == IsoRenderer.Corner.NW ? 2 : 3;
            boolean odd = turns % 2 == 1;
            Box turned = Box.of(new Vec3(0, 0, 0), new Vec3((odd ? sz : sx) - 1, sy - 1, (odd ? sx : sz) - 1));
            String[] out = new String[states.length];
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    for (int x = 0; x < sx; x++) {
                        // One counter-clockwise quarter turn: west goes south, south goes east, (x, z) -> (z, w - 1 - x).
                        int nx = x, nz = z, w = sx, d = sz;
                        for (int t = 0; t < turns; t++) {
                            int tx = nz;
                            nz = w - 1 - nx;
                            nx = tx;
                            int tw = d;
                            d = w;
                            w = tw;
                        }
                        out[RegionCodec.index(turned, nx, y, nz)] = states[RegionCodec.index(box, x, y, z)];
                    }
                }
            }
            BufferedImage expected = image(IsoRenderer.render(turned, out, IsoRenderer.View.ISO, IsoRenderer.Corner.SE, IsoRenderer.Size.MEDIUM));
            BufferedImage actual = image(IsoRenderer.render(box, states, IsoRenderer.View.ISO, corner, IsoRenderer.Size.MEDIUM));
            assertTrue(samePixels(expected, actual), corner.toString());
        }
    }

    /** Top row of column {@code x} that is not background. */
    private static int firstRow(BufferedImage img, int x) {
        for (int y = 0; y < img.getHeight(); y++) if (rgb(img, x, y) != BACKGROUND) return y;
        return -1;
    }

    @Test
    void bottomSlabLeavesTheUpperHalfEmpty() throws Exception {
        // A cube at x=0 fixes the picture's extent; the block at x=2 is a cube or a slab.
        Box box = Box.of(new Vec3(0, 0, 0), new Vec3(2, 0, 0));
        BufferedImage cube = image(IsoRenderer.render(box, new String[]{"stone", "air", "stone"}, IsoRenderer.View.ISO, IsoRenderer.Size.MEDIUM));
        BufferedImage slab = image(IsoRenderer.render(box, new String[]{"stone", "air", "stone_slab"}, IsoRenderer.View.ISO, IsoRenderer.Size.MEDIUM));
        assertEquals(cube.getWidth(), slab.getWidth());
        assertEquals(cube.getHeight(), slab.getHeight());
        // 3 blocks at medium size: 14 px tiles. A column a little left of the last tile's centre, inside its top face.
        int a = 14, column = cube.getWidth() - 3 - a - 2;
        assertEquals(a / 2, firstRow(slab, column) - firstRow(cube, column));
    }

    @Test
    void elevationsShowTheFaceFromOutside() throws Exception {
        // One block deep, 4 wide, 2 high: gold at the east end and diamond at the west end of the bottom row.
        Box box = Box.of(new Vec3(0, 0, 0), new Vec3(3, 1, 0));
        String[] states = new String[(int) box.volume()];
        Arrays.fill(states, "stone");
        states[RegionCodec.index(box, 3, 0, 0)] = "gold_block";
        states[RegionCodec.index(box, 0, 0, 0)] = "diamond_block";
        int gold = BlockColors.rgb("gold_block"), diamond = BlockColors.rgb("diamond_block");

        IsoRenderer.Rendered north = IsoRenderer.render(box, states, IsoRenderer.View.NORTH, IsoRenderer.Size.MEDIUM);
        assertEquals(64, north.width());
        assertEquals(32, north.height());
        BufferedImage n = image(north);
        assertEquals(gold, rgb(n, 8, 24), "seen from the north, east is on the left");
        assertEquals(diamond, rgb(n, 56, 24));
        assertEquals(BlockColors.rgb("stone"), rgb(n, 8, 8));

        BufferedImage s = image(IsoRenderer.render(box, states, IsoRenderer.View.SOUTH, IsoRenderer.Size.MEDIUM));
        assertEquals(diamond, rgb(s, 8, 24));
        assertEquals(gold, rgb(s, 56, 24));

        IsoRenderer.Rendered east = IsoRenderer.render(box, states, IsoRenderer.View.EAST, IsoRenderer.Size.MEDIUM);
        assertEquals(16, east.width());
        assertEquals(gold, rgb(image(east), 8, 24), "the nearest block is drawn");
    }

    @Test
    void elevationDrawsSlabsAndStairProfiles() throws Exception {
        Box box = Box.of(new Vec3(0, 0, 0), new Vec3(1, 0, 0));
        String[] states = {"stone_slab", "oak_stairs[facing=east]"};
        BufferedImage img = image(IsoRenderer.render(box, states, IsoRenderer.View.SOUTH, IsoRenderer.Size.MEDIUM));
        assertEquals(BACKGROUND, rgb(img, 8, 4));
        assertEquals(BlockColors.rgb("stone_slab"), rgb(img, 8, 12));
        // Seen from the south the stair shows its L profile, tall on the east (right) side.
        assertEquals(BACKGROUND, rgb(img, 20, 4));
        assertEquals(BlockColors.rgb("oak_stairs"), rgb(img, 28, 4));
        assertEquals(BlockColors.rgb("oak_stairs"), rgb(img, 20, 12));
    }
}
