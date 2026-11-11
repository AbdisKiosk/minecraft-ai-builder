package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BlockColorsTest {

    @Test
    void dyedBlocksHaveTheirDyeColour() {
        int red = BlockColors.rgb("red_concrete");
        for (String block : new String[]{"red_wool", "red_carpet", "red_terracotta", "red_stained_glass",
                "red_stained_glass_pane", "red_bed[part=head]", "red_glazed_terracotta", "red_wall_banner"}) {
            assertEquals(red, BlockColors.rgb(block), block);
        }
        assertNotEquals(BlockColors.rgb("white_wool"), BlockColors.rgb("red_wool"));
        assertNotEquals(BlockColors.rgb("blue_wool"), BlockColors.rgb("light_blue_wool"));
        assertNotEquals(BlockColors.rgb("gray_wool"), BlockColors.rgb("light_gray_wool"));
        assertEquals(BlockColors.rgb("light_blue_concrete"), BlockColors.rgb("light_blue_terracotta"));
    }

    @Test
    void aDyeWordInANameIsNotADye() {
        assertEquals(BlockColors.rgb("smooth_red_sandstone"), BlockColors.rgb("red_sandstone"));
        assertNotEquals(BlockColors.rgb("red_wool"), BlockColors.rgb("red_sandstone"));
        assertEquals(BlockColors.rgb("ice"), BlockColors.rgb("blue_ice"));
        assertEquals(BlockColors.rgb("terracotta"), BlockColors.rgb("terracotta[axis=y]"));
        assertNotEquals(BlockColors.rgb("terracotta"), BlockColors.rgb("white_terracotta"));
        assertEquals(BlockColors.rgb("glass"), BlockColors.rgb("glass_pane"));
    }
}
