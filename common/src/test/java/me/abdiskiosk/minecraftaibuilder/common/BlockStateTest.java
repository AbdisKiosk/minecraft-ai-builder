package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockStateTest {

    @Test
    void parsesTypeAndProperties() {
        BlockState s = BlockState.parse("minecraft:Oak_Stairs[facing=EAST, half=top]");
        assertEquals("oak_stairs", s.type());
        assertEquals("east", s.props().get("facing"));
        assertEquals("top", s.props().get("half"));
        assertEquals("oak_stairs[facing=east,half=top]", s.toString());
    }

    @Test
    void plainNames() {
        assertEquals(new BlockState("stone", java.util.Map.of()), BlockState.parse(" stone "));
        assertEquals("stone", BlockState.parse("stone[]").toString());
    }

    @Test
    void rejectsBrokenStates() {
        assertThrows(EditException.class, () -> BlockState.parse("oak_stairs[facing=east"));
        assertThrows(EditException.class, () -> BlockState.parse("oak_stairs[facing]"));
        assertThrows(EditException.class, () -> BlockState.parse("oak_stairs[facing=]"));
        assertThrows(EditException.class, () -> BlockState.parse("[facing=east]"));
    }

    @Test
    void nbtInTheStateStringPointsToTheDataObject() {
        var e = assertThrows(EditException.class, () -> BlockState.parse("oak_sign[rotation=4]{Text1:'x'}"));
        assertTrue(e.getMessage().contains("data object"), e.getMessage());
    }
}
