package me.abdiskiosk.minecraftaibuilder.common;

import java.util.List;

/**
 * A player's WorldEdit selection.
 *
 * @param type   selector name as WorldEdit reports it (cuboid, poly2d, ellipsoid, ...)
 * @param points polygon vertices (x, z) for poly2d selections, empty otherwise
 */
public record SelectionInfo(String world, String type, Box bounds, long volume, List<int[]> points) {
}
