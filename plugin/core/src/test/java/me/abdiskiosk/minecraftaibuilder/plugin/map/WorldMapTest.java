package me.abdiskiosk.minecraftaibuilder.plugin.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldMapTest {

    @TempDir
    Path world;

    private Path regionFile(String dir, long modified) throws IOException {
        Path d = Files.createDirectories(world.resolve(dir));
        Path f = d.resolve("r.0.0.mca");
        Files.write(f, new byte[8193]);
        Files.setLastModifiedTime(f, FileTime.fromMillis(modified));
        return d;
    }

    @Test
    void findsTheOldLayout() throws IOException {
        Path region = regionFile("region", 1000);
        assertEquals(region, WorldMap.regionDir(world, "NORMAL", null));
        Path nether = regionFile("DIM-1/region", 1000);
        assertEquals(nether, WorldMap.regionDir(world, "NETHER", null));
    }

    @Test
    void prefersTheFolderTheServerWritesTo() throws IOException {
        // A world upgraded to 26.1 with its old region folder left behind.
        regionFile("region", 1000);
        Path live = regionFile("dimensions/minecraft/overworld/region", 5000);
        assertEquals(live, WorldMap.regionDir(world, "NORMAL", "minecraft:overworld"));
        Path nether = regionFile("dimensions/minecraft/the_nether/region", 5000);
        assertEquals(nether, WorldMap.regionDir(world, "NETHER", "minecraft:the_nether"));
    }

    @Test
    void usesTheWorldKeyForCustomDimensions() throws IOException {
        Path custom = regionFile("dimensions/myplugin/arena/region", 1000);
        assertEquals(custom, WorldMap.regionDir(world, "CUSTOM", "myplugin:arena"));
    }

    @Test
    void aWorldFolderThatIsTheDimensionItself() throws IOException {
        Path region = regionFile("region", 1000);
        assertEquals(region, WorldMap.regionDir(world, "NETHER", "minecraft:the_nether"));
        // Nothing saved yet: where the old layout would put it.
        assertEquals(world.resolve("none/DIM1/region"), WorldMap.regionDir(world.resolve("none"), "THE_END", null));
    }

    @Test
    void tileVersionsFollowTheirRegions() {
        Map<Long, String> v = Map.of(WorldMap.key(0, 0), "a", WorldMap.key(1, 0), "b", WorldMap.key(-1, -1), "c");
        assertEquals("a", WorldMap.version(v, 0, 0, 0));
        assertNull(WorldMap.version(v, 0, 5, 5));
        String parent = WorldMap.version(v, 1, 0, 0);
        assertNotNull(parent);
        assertEquals(parent, WorldMap.version(Map.of(WorldMap.key(0, 0), "a", WorldMap.key(1, 0), "b"), 1, 0, 0));
        assertNotEquals(parent, WorldMap.version(Map.of(WorldMap.key(0, 0), "a", WorldMap.key(1, 0), "b2"), 1, 0, 0));
        assertNotNull(WorldMap.version(v, 1, -1, -1));
    }
}
