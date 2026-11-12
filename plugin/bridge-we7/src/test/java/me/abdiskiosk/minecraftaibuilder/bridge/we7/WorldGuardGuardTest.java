package me.abdiskiosk.minecraftaibuilder.bridge.we7;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.SimpleFlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.index.PriorityRTreeIndex;
import com.sk89q.worldguard.protection.managers.storage.MemoryRegionDatabase;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The region check against WorldGuard's own region index and flag rules, without a server. */
class WorldGuardGuardTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    private RegionManager regions;

    @BeforeEach
    void setUp() {
        regions = new RegionManager(new MemoryRegionDatabase(), new PriorityRTreeIndex.Factory(), new SimpleFlagRegistry());
        ProtectedRegion town = cuboid("town", 0, -64, 0, 99, 319, 99);
        ProtectedRegion plot = cuboid("plot", 20, 60, 20, 29, 90, 29);
        plot.setPriority(10);
        plot.getOwners().addPlayer(OWNER);
        ProtectedRegion square = cuboid("square", 40, -64, 40, 49, 319, 49);
        square.setPriority(10);
        square.setFlag(Flags.BUILD, StateFlag.State.ALLOW);
        ProtectedRegion hill = new ProtectedPolygonalRegion("hill", List.of(
                BlockVector2.at(200, 190), BlockVector2.at(210, 200), BlockVector2.at(200, 210), BlockVector2.at(190, 200)), 0, 255);
        regions.addRegion(town);
        regions.addRegion(plot);
        regions.addRegion(square);
        regions.addRegion(hill);
    }

    private static ProtectedRegion cuboid(String id, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new ProtectedCuboidRegion(id, BlockVector3.at(x1, y1, z1), BlockVector3.at(x2, y2, z2));
    }

    private List<String> denied(UUID actor, int x1, int y1, int z1, int x2, int y2, int z2) {
        return WorldGuardGuard.denied(regions, WorldGuardGuard.byId(actor),
                Box.of(new Vec3(x1, y1, z1), new Vec3(x2, y2, z2)));
    }

    @Test
    void listsEveryRegionByNameWithItsBounds() {
        regions.addRegion(new GlobalProtectedRegion(ProtectedRegion.GLOBAL_REGION));
        Map<String, Box> all = WorldGuardGuard.regions(regions);
        assertEquals(List.of("hill", "plot", "square", "town"), List.copyOf(all.keySet()));
        assertEquals(Box.of(new Vec3(20, 60, 20), new Vec3(29, 90, 29)), all.get("plot"));
        assertEquals(Box.of(new Vec3(190, 0, 190), new Vec3(210, 255, 210)), all.get("hill"));
    }

    @Test
    void ownersBuildInTheirPlotInsideAProtectedTown() {
        assertEquals(List.of(), denied(OWNER, 20, 60, 20, 29, 90, 29));
        assertEquals(List.of("town"), denied(OWNER, 20, 60, 20, 30, 90, 29));
        assertEquals(List.of("town"), denied(OWNER, 25, 91, 25, 25, 91, 25), "above the plot");
    }

    @Test
    void othersAndScriptsWithoutAPlayerAreKeptOut() {
        assertEquals(List.of("plot"), denied(STRANGER, 22, 64, 22, 24, 66, 24));
        assertEquals(List.of("plot"), denied(null, 22, 64, 22, 24, 66, 24));
    }

    @Test
    void theBuildFlagLetsEveryoneIn() {
        assertEquals(List.of(), denied(STRANGER, 40, 64, 40, 49, 70, 49));
    }

    @Test
    void wildernessIsFreeUnlessTheGlobalRegionDeniesBuilding() {
        assertEquals(List.of(), denied(null, 500, 64, 500, 520, 70, 520));
        GlobalProtectedRegion global = new GlobalProtectedRegion(ProtectedRegion.GLOBAL_REGION);
        global.setFlag(Flags.BUILD, StateFlag.State.DENY);
        regions.addRegion(global);
        assertEquals(List.of(ProtectedRegion.GLOBAL_REGION), denied(null, 500, 64, 500, 520, 70, 520));
    }

    @Test
    void polygonsCountOnlyInsideTheirOutline() {
        assertEquals(List.of(), denied(STRANGER, 206, 64, 206, 210, 70, 210), "inside the bounds, outside the outline");
        assertEquals(List.of("hill"), denied(STRANGER, 200, 64, 200, 210, 70, 210));
    }

    @Test
    void membersOfAParentBuildInItsChildren() throws Exception {
        ProtectedRegion estate = cuboid("estate", 300, 0, 300, 399, 255, 399);
        estate.getMembers().addPlayer(OWNER);
        ProtectedRegion barn = cuboid("barn", 310, 60, 310, 319, 80, 319);
        barn.setPriority(5);
        barn.setParent(estate);
        regions.addRegion(estate);
        regions.addRegion(barn);
        assertEquals(List.of(), denied(OWNER, 310, 60, 310, 319, 80, 319));
        assertEquals(List.of("barn"), denied(STRANGER, 310, 60, 310, 319, 80, 319));
    }
}
