package me.abdiskiosk.minecraftaibuilder.common;

import java.util.Optional;
import java.util.UUID;

/**
 * Version-specific WorldEdit access. Implementations exist for WorldEdit 6 and 7.
 * Unless {@link #async()} is true, every method must be called on the server main thread.
 *
 * <p>An actor of {@code null} means the console, which has its own undo history.
 */
public interface WorldEditBridge {

    String name();

    /** True when FastAsyncWorldEdit is driving edits, so they may run off the main thread. */
    boolean async();

    /** The blocks this server can place; see {@link BlockCheck}. */
    BlockCatalog catalog();

    Optional<SelectionInfo> selection(UUID player);

    EditHandle begin(UUID actor, String world);

    /** @return number of steps actually undone */
    int undo(UUID actor, int steps);

    /**
     * Reads the block states in {@code box}, without the {@code minecraft:} namespace.
     * Index order is y, then z, then x (see {@link RegionCodec#index}).
     */
    String[] read(String world, Box box);
}
