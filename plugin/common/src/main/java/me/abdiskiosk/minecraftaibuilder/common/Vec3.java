package me.abdiskiosk.minecraftaibuilder.common;

public record Vec3(int x, int y, int z) {

    public Vec3 add(int dx, int dy, int dz) {
        return new Vec3(x + dx, y + dy, z + dz);
    }

    public static Vec3 min(Vec3 a, Vec3 b) {
        return new Vec3(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z));
    }

    public static Vec3 max(Vec3 a, Vec3 b) {
        return new Vec3(Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z));
    }

    @Override
    public String toString() {
        return "[" + x + "," + y + "," + z + "]";
    }
}
