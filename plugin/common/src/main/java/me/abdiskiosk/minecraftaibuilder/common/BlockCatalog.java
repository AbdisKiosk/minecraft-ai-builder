package me.abdiskiosk.minecraftaibuilder.common;

import java.util.List;
import java.util.Optional;

/**
 * What blocks the running server can place. Names are modern, without namespace, and aliases for renamed
 * blocks resolve to the canonical name. Lets the plugin reject unknown blocks before anything is built.
 */
public interface BlockCatalog {

    /** e.g. "Minecraft 1.8.8"; used in error messages. */
    String describe();

    /** True on 1.8-1.12 servers, where blocks are id:data pairs and newer blocks do not exist. */
    boolean legacy();

    /**
     * Whether an unknown property name is an error. WorldEdit 7 rejects them itself; on 1.8 many modern
     * properties (shape, waterlogged, fence connections) are valid but cannot be stored, so unknown names only warn.
     */
    boolean strictProperties();

    Optional<BlockInfo> info(String type);

    /** Closest valid block names for a name that does not exist, best first. */
    List<String> suggest(String type, int max);

    /** Block names containing every word of the query. */
    List<String> search(String query, int max);
}
