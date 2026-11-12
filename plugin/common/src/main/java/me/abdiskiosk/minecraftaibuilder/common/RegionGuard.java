package me.abdiskiosk.minecraftaibuilder.common;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A region plugin (WorldGuard): its regions are named places Claude can build in ("build a pillar in spawn"), and with
 * protection on, edits keep out of regions the acting player may not build in. The bridges implement it for WorldGuard
 * 6 and 7. Its methods only read the plugin's region index, so they may be called from any thread.
 *
 * <p>An actor of {@code null} means nobody in particular, as for a script with no player online: it builds as a
 * non-member everywhere.
 */
public interface RegionGuard {

    /** At most this many regions are named when an edit is refused. */
    int MAX_NAMED = 5;

    /** e.g. "WorldGuard 7.0.9" */
    String name();

    /**
     * For protection: the regions that keep {@code actor} from building somewhere in {@code box}, at most
     * {@link #MAX_NAMED}; empty when it may change all of it. This follows the plugin's own rules (owners, members,
     * priorities, parents, the build flags, the bypass permission), so a plot inside a protected town is buildable for
     * the plot's members.
     *
     * @throws EditException when the box crosses so many region edges that checking it would take too long
     */
    List<String> denied(UUID actor, String world, Box box);

    /** Every region of {@code world} (not the world-wide one), by name, as bounding boxes. */
    Map<String, Box> regions(String world);

    /** The bounding box of one region, by its name. */
    Optional<Box> region(String world, String name);
}
