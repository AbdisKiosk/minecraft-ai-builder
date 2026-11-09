package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlockSyntaxTest {

    @Test
    void splitsOnlyOutsideBrackets() {
        assertEquals(List.of("oak_stairs[facing=east,half=top]", "stone"),
                BlockSyntax.splitTopLevel("oak_stairs[facing=east,half=top],stone", ','));
        assertEquals(List.of("a"), BlockSyntax.splitTopLevel("a", ','));
    }

    @Test
    void tokensKeepWeightsAndStates() {
        var tokens = BlockSyntax.tokens("70%stone_bricks,30%oak_stairs[facing=east,half=top]");
        assertEquals(2, tokens.size());
        assertEquals("70%", tokens.get(0).prefix());
        assertEquals("stone_bricks", tokens.get(0).body());
        assertEquals("oak_stairs[facing=east,half=top]", tokens.get(1).body());
        assertTrue(tokens.get(1).plainBlock());
    }

    @Test
    void specialsAndNumbersAreNotPlainBlocks() {
        for (String special : List.of("#existing", "^[facing]", "35:14", "*", "$biome")) {
            assertFalse(BlockSyntax.tokens(special).get(0).plainBlock(), special);
        }
        assertTrue(BlockSyntax.tokens("minecraft:stone").get(0).plainBlock());
        assertEquals("!", BlockSyntax.tokens("!air").get(0).prefix());
    }

    @Test
    void maskComponentsSplitOnSpacesOutsideBrackets() {
        assertEquals(List.of("stone,dirt", "!oak_stairs[facing=east,half=top]"),
                BlockSyntax.maskComponents("stone,dirt !oak_stairs[facing=east,half=top]"));
    }
}
