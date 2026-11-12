package me.abdiskiosk.minecraftaibuilder.common;

import java.util.List;

/**
 * Where a library asset attaches and which way it faces, as its owner set them on the panel. Either may be null: assets
 * saved before anchors and uploaded schematics have neither, and then a paste {@code at} a position places the minimum
 * corner there, as {@code origin} does.
 *
 * @param cell   the attachment cell, zero-based from the blueprint's minimum corner before rotation, or null
 * @param facing north, south, east, west, up or down: the way the asset faces away from what it attaches to, or null
 */
public record Anchor(Vec3 cell, String facing) {
    /** Clockwise seen from above, the order {@link EditHandle#paste}'s rotation turns them in. */
    private static final List<String> HORIZONTAL = List.of("north", "east", "south", "west");
    public static final List<String> FACINGS = List.of("north", "east", "south", "west", "up", "down");
    /** For blueprints that are not library assets. */
    public static final Anchor NONE = new Anchor(null, null);

    /**
     * The paste that puts the anchor cell at {@code at}, the asset facing {@code facing} (null: keep the rotation given).
     * Only horizontal facings turn; an asset that faces up or down can only be pasted facing that way.
     *
     * @param rotation degrees, a multiple of 90, used when {@code facing} is null
     */
    public Op.Paste paste(Blueprint blueprint, Vec3 at, String facing, int rotation) {
        if (facing != null) {
            if (!FACINGS.contains(facing)) throw new EditException("facing must be north, south, east, west, up or down");
            if (this.facing == null) throw new EditException("this blueprint has no stored facing; use origin and rotation"
                    + " (an asset's owner can set one on the panel)");
            boolean vertical = !HORIZONTAL.contains(this.facing);
            if ((vertical || !HORIZONTAL.contains(facing)) && !facing.equals(this.facing)) {
                throw new EditException("this asset faces " + this.facing + "; paste turns only between north, south, east and west, so "
                        + (vertical ? "use facing=" + this.facing + " or leave it out" : "facing must be one of those"));
            }
            rotation = vertical ? 0 : rotation(this.facing, facing);
        }
        if (cell == null) return new Op.Paste(blueprint, at, rotation);
        Vec3 turned = rotate(cell, blueprint.sizeX(), blueprint.sizeZ(), rotation);
        return new Op.Paste(blueprint, at.add(-turned.x(), -turned.y(), -turned.z()), rotation);
    }

    /** Degrees (0, 90, 180 or 270) that turn horizontal facing {@code from} into {@code to}. */
    public static int rotation(String from, String to) {
        return Math.floorMod(HORIZONTAL.indexOf(to) - HORIZONTAL.indexOf(from), 4) * 90;
    }

    /**
     * Where {@code cell} of a {@code sizeX} by {@code sizeZ} blueprint ends up, relative to the pasted minimum corner,
     * after {@code rotation} degrees clockwise seen from above (WorldEdit's //rotate, as {@link EditHandle#paste} uses).
     */
    public static Vec3 rotate(Vec3 cell, int sizeX, int sizeZ, int rotation) {
        int x = cell.x(), z = cell.z();
        return switch (Math.floorMod(rotation, 360)) {
            case 0 -> cell;
            case 90 -> new Vec3(sizeZ - 1 - z, cell.y(), x);
            case 180 -> new Vec3(sizeX - 1 - x, cell.y(), sizeZ - 1 - z);
            case 270 -> new Vec3(z, cell.y(), sizeX - 1 - x);
            default -> throw new EditException("rotation must be a multiple of 90");
        };
    }
}
