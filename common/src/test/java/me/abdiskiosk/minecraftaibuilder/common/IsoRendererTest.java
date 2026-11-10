package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

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
}
