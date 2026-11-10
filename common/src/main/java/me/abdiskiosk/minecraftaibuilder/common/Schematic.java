package me.abdiskiosk.minecraftaibuilder.common;

/** A WorldEdit schematic file's blocks: modern states as {@link WorldEditBridge#read} returns them, in {@link RegionCodec#index} order. */
public record Schematic(int sizeX, int sizeY, int sizeZ, String[] states) {
}
