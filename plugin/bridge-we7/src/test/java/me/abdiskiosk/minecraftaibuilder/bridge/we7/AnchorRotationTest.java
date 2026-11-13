package me.abdiskiosk.minecraftaibuilder.bridge.we7;

import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.util.Direction;
import me.abdiskiosk.minecraftaibuilder.common.Anchor;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Anchor}'s turning against WorldEdit's own: the transform {@code We7Bridge.EditHandle#paste} builds for a
 * rotation moves the cells, and {@code BlockTransformExtent} turns a block's facing with that same transform.
 */
class AnchorRotationTest {

    @Test
    void anchorCellsMoveAsThePasteMovesThem() {
        int sx = 3, sz = 5; // not square, so a wrong axis or sign shows
        for (int rotation : new int[]{0, 90, 180, 270}) {
            // What the bridge's paste does: rotate every cell, then shift the rotated box's minimum corner to the origin.
            AffineTransform transform = new AffineTransform().rotateY(-rotation);
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            for (int[] corner : new int[][]{{0, 0}, {sx - 1, 0}, {0, sz - 1}, {sx - 1, sz - 1}}) {
                Vector3 r = transform.apply(Vector3.at(corner[0], 0, corner[1]));
                minX = Math.min(minX, (int) Math.round(r.getX()));
                minZ = Math.min(minZ, (int) Math.round(r.getZ()));
            }
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    Vector3 r = transform.apply(Vector3.at(x, 0, z));
                    Vec3 placed = new Vec3((int) Math.round(r.getX()) - minX, 4, (int) Math.round(r.getZ()) - minZ);
                    assertEquals(placed, Anchor.rotate(new Vec3(x, 4, z), sx, sz, rotation), "cell " + x + "," + z + " turned " + rotation);
                }
            }
        }
    }

    @Test
    void facingsTurnAsBlockStatesTurn() {
        String[] facings = {"north", "east", "south", "west"};
        for (String from : facings) {
            for (String to : facings) {
                int rotation = Anchor.rotation(from, to);
                AffineTransform transform = new AffineTransform().rotateY(-rotation);
                // BlockTransformExtent#getNewStateValue, then the closest of the six directions a "facing" property has.
                Vector3 v = transform.apply(Direction.valueOf(from.toUpperCase(Locale.ROOT)).toVector())
                        .subtract(transform.apply(Vector3.ZERO)).normalize();
                Direction turned = Direction.findClosest(v, Direction.Flag.CARDINAL | Direction.Flag.UPRIGHT);
                assertEquals(to, turned.name().toLowerCase(Locale.ROOT), from + " turned " + rotation);
            }
        }
        // A quarter turn is clockwise seen from above, as //rotate 90: north becomes east.
        assertEquals(90, Anchor.rotation("north", "east"));
    }
}
