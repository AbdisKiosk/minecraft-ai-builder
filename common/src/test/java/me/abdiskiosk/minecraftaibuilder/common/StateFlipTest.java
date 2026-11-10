package me.abdiskiosk.minecraftaibuilder.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StateFlipTest {

    private static void flips(String state, char axis, String expected) {
        assertEquals(expected, StateFlip.flip(state, axis), state + " flipped on " + axis);
    }

    @Test
    void facingSwapsAlongTheAxis() {
        flips("oak_stairs[facing=east,half=top]", 'x', "oak_stairs[facing=west,half=top]");
        flips("oak_stairs[facing=north]", 'x', "oak_stairs[facing=north]");
        flips("oak_stairs[facing=north]", 'z', "oak_stairs[facing=south]");
        flips("ladder[facing=west]", 'z', "ladder[facing=west]");
        flips("hopper[facing=down]", 'x', "hopper[facing=down]");
    }

    @Test
    void sidesTradeValuesOrMoveWhenOnlyOneIsGiven() {
        flips("oak_fence[east=true]", 'x', "oak_fence[west=true]");
        flips("oak_fence[east=true,west=false]", 'x', "oak_fence[east=false,west=true]");
        flips("cobblestone_wall[east=low,north=tall,up=true]", 'x', "cobblestone_wall[west=low,north=tall,up=true]");
        flips("cobblestone_wall[east=low,north=tall,up=true]", 'z', "cobblestone_wall[east=low,south=tall,up=true]");
        flips("redstone_wire[north=side,south=up]", 'z', "redstone_wire[north=up,south=side]");
        flips("vine[west=true]", 'x', "vine[east=true]");
    }

    @Test
    void handednessSwaps() {
        flips("oak_stairs[facing=east,shape=inner_left]", 'x', "oak_stairs[facing=west,shape=inner_right]");
        flips("oak_stairs[facing=north,shape=outer_right]", 'z', "oak_stairs[facing=south,shape=outer_left]");
        flips("oak_door[facing=east,hinge=right,half=upper]", 'x', "oak_door[facing=west,hinge=left,half=upper]");
        flips("chest[facing=south,type=left]", 'x', "chest[facing=south,type=right]");
        flips("oak_slab[type=top]", 'x', "oak_slab[type=top]");
    }

    @Test
    void rotationMirrors() {
        flips("oak_sign[rotation=4]", 'x', "oak_sign[rotation=12]");
        flips("oak_sign[rotation=3]", 'x', "oak_sign[rotation=13]");
        flips("white_banner[rotation=0]", 'z', "white_banner[rotation=8]");
        flips("white_banner[rotation=12]", 'z', "white_banner[rotation=12]");
        flips("white_banner[rotation=8]", 'x', "white_banner[rotation=8]");
    }

    @Test
    void railShapesSwapDirectionWords() {
        flips("rail[shape=ascending_east]", 'x', "rail[shape=ascending_west]");
        flips("rail[shape=north_east]", 'x', "rail[shape=north_west]");
        flips("powered_rail[shape=south_west]", 'z', "powered_rail[shape=north_west]");
        flips("rail[shape=east_west]", 'z', "rail[shape=east_west]");
        flips("rail[shape=ascending_north]", 'x', "rail[shape=ascending_north]");
    }

    @Test
    void otherStatesAreLeftAlone() {
        flips("stone", 'x', "stone");
        flips("oak_log[axis=x]", 'x', "oak_log[axis=x]");
        flips("minecraft:oak_log[axis=z]", 'z', "minecraft:oak_log[axis=z]");
    }

    /** Reads leave out defaults, so a mirror that changes one writes it out. */
    @Test
    void changedDefaultsAreWrittenOut() {
        flips("oak_stairs", 'z', "oak_stairs[facing=south]");
        flips("oak_stairs", 'x', "oak_stairs");
        flips("oak_stairs[half=top]", 'z', "oak_stairs[half=top,facing=south]");
        flips("oak_door[half=upper]", 'x', "oak_door[half=upper,hinge=right]");
        flips("oak_sign", 'z', "oak_sign[rotation=8]");
        flips("oak_wall_sign", 'x', "oak_wall_sign");
    }

    @Test
    void flippingTwiceIsTheIdentity() {
        for (String s : List.of("oak_stairs[facing=east,half=top,shape=inner_left]", "oak_fence[east=true,west=false]",
                "oak_fence[north=true]", "oak_door[facing=north,hinge=left,half=lower]", "oak_sign[rotation=5]",
                "rail[shape=ascending_south]", "chest[facing=west,type=right]", "stone")) {
            for (char axis : new char[]{'x', 'z'}) {
                assertEquals(s, StateFlip.flip(StateFlip.flip(s, axis), axis), s + " on " + axis);
            }
        }
    }

    @Test
    void patternsKeepWeightsAndNonBlockTokens() {
        assertEquals("70%oak_stairs[facing=west,half=top],30%stone",
                StateFlip.flipPattern("70%oak_stairs[facing=east,half=top],30%stone", 'x'));
        assertEquals("#existing,5:2", StateFlip.flipPattern("#existing,5:2", 'x'));
        assertEquals("air", StateFlip.flipPattern("air", 'z'));
    }
}
