package me.abdiskiosk.minecraftaibuilder.common;

/** Inclusive axis-aligned block box. Always normalised so min <= max on every axis. */
public record Box(Vec3 min, Vec3 max) {

    public Box {
        Vec3 lo = Vec3.min(min, max);
        Vec3 hi = Vec3.max(min, max);
        min = lo;
        max = hi;
    }

    public static Box of(Vec3 a, Vec3 b) {
        return new Box(a, b);
    }

    public int sizeX() { return max.x() - min.x() + 1; }
    public int sizeY() { return max.y() - min.y() + 1; }
    public int sizeZ() { return max.z() - min.z() + 1; }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    public boolean contains(Box other) {
        return other.min.x() >= min.x() && other.min.y() >= min.y() && other.min.z() >= min.z()
                && other.max.x() <= max.x() && other.max.y() <= max.y() && other.max.z() <= max.z();
    }

    public Box withY(int minY, int maxY) {
        return new Box(new Vec3(min.x(), minY, min.z()), new Vec3(max.x(), maxY, max.z()));
    }

    @Override
    public String toString() {
        return min + ".." + max;
    }
}
