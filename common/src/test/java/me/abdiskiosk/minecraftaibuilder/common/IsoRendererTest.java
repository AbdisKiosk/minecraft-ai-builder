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
    void coloursPreferSpecificKeywords() {
        assertNotEquals(BlockColors.rgb("sandstone"), BlockColors.rgb("red_sandstone"));
        assertEquals(-1, BlockColors.rgb("cave_air"));
        assertEquals(BlockColors.rgb("stone_brick_wall"), BlockColors.rgb("stone_bricks"));
        assertNotEquals(BlockColors.rgb("bricks"), BlockColors.rgb("stone_bricks"));
        assertEquals(BlockColors.rgb("light_blue_wool"), BlockColors.rgb("light_blue_concrete"));
    }
}
