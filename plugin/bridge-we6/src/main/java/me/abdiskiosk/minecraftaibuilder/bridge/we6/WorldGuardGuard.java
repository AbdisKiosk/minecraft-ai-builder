package me.abdiskiosk.minecraftaibuilder.bridge.we6;

import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.domains.Association;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.association.Associables;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.DefaultFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionType;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.GuardScan;
import me.abdiskiosk.minecraftaibuilder.common.RegionGuard;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** WorldGuard 6.1+ (with WorldEdit 6). Loaded by the plugin only when WorldGuard is installed. */
public final class WorldGuardGuard implements RegionGuard {

    private static final String PROBE = "__aibuilder_probe__";

    private final WorldGuardPlugin plugin = WorldGuardPlugin.inst();

    @Override
    public String name() {
        return "WorldGuard " + plugin.getDescription().getVersion();
    }

    @Override
    public List<String> denied(UUID actor, String world, Box box) {
        RegionManager regions = manager(world);
        if (regions == null) return List.of(); // regions are off in this world
        Player player = actor == null ? null : Bukkit.getPlayer(actor);
        if (player != null && player.hasPermission("worldguard.region.bypass." + world)) return List.of();
        return denied(regions, associable(actor, player), box);
    }

    /** {@link #denied(UUID, String, Box)} once the world's regions and the actor are known. */
    static List<String> denied(RegionManager regions, RegionAssociable who, Box box) {
        ProtectedCuboidRegion probe = new ProtectedCuboidRegion(PROBE, vec(box.min()), vec(box.max()));
        List<Shape> shapes = new ArrayList<>();
        for (ProtectedRegion r : regions.getApplicableRegions(probe)) {
            if (!(r instanceof GlobalProtectedRegion)) shapes.add(new Shape(r));
        }
        return GuardScan.denied(box, shapes, p -> {
            ApplicableRegionSet here = regions.getApplicableRegions(vec(p));
            if (here.testState(who, DefaultFlag.BUILD, DefaultFlag.BLOCK_PLACE, DefaultFlag.BLOCK_BREAK)) return null;
            return here.getRegions().stream().max(Comparator.comparingInt(ProtectedRegion::getPriority))
                    .map(ProtectedRegion::getId).orElse(ProtectedRegion.GLOBAL_REGION);
        }, MAX_NAMED, GuardScan.MAX_CELLS);
    }

    @Override
    public Map<String, Box> regions(String world) {
        RegionManager regions = manager(world);
        return regions == null ? Map.of() : regions(regions);
    }

    static Map<String, Box> regions(RegionManager regions) {
        Map<String, Box> out = new TreeMap<>();
        for (ProtectedRegion r : regions.getRegions().values()) {
            if (!(r instanceof GlobalProtectedRegion)) out.put(r.getId(), box(r));
        }
        return out;
    }

    @Override
    public Optional<Box> region(String world, String name) {
        RegionManager regions = manager(world);
        if (regions == null) return Optional.empty();
        ProtectedRegion r = regions.getRegion(name);
        return r == null || r instanceof GlobalProtectedRegion ? Optional.empty() : Optional.of(box(r));
    }

    private RegionManager manager(String world) {
        World w = Bukkit.getWorld(world);
        return w == null ? null : plugin.getRegionContainer().get(w);
    }

    /** The online player as WorldGuard sees them (groups included); otherwise owners and members by id only. */
    private RegionAssociable associable(UUID actor, Player player) {
        if (player != null) return plugin.wrapPlayer(player);
        return byId(actor);
    }

    /** Owners and members by id, through parent regions too, as WorldGuard does for a player. */
    static RegionAssociable byId(UUID actor) {
        if (actor == null) return Associables.constant(Association.NON_MEMBER);
        return list -> {
            Association best = Association.NON_MEMBER;
            for (ProtectedRegion r : list) {
                for (ProtectedRegion up = r; up != null; up = up.getParent()) {
                    if (up.getOwners().contains(actor)) return Association.OWNER;
                    if (up.getMembers().contains(actor)) best = Association.MEMBER;
                }
            }
            return best;
        };
    }

    private static Box box(ProtectedRegion r) {
        BlockVector min = r.getMinimumPoint(), max = r.getMaximumPoint();
        return Box.of(new Vec3(min.getBlockX(), min.getBlockY(), min.getBlockZ()),
                new Vec3(max.getBlockX(), max.getBlockY(), max.getBlockZ()));
    }

    private static BlockVector vec(Vec3 v) {
        return new BlockVector(v.x(), v.y(), v.z());
    }

    private record Shape(ProtectedRegion region, Box bounds) implements GuardScan.Shape {
        Shape(ProtectedRegion region) {
            this(region, box(region));
        }

        @Override
        public boolean cuboid() {
            return region.getType() == RegionType.CUBOID;
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return region.contains(x, y, z);
        }
    }
}
