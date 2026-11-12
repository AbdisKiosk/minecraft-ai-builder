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

    /**
     * A dry-run edit session for drafts: nothing reaches the world.
     *
     * @param overlay   the open draft's state at a position, or null; the session reads it as if it were placed
     * @param maxBlocks the most positions the preview may change
     * @throws EditException when this WorldEdit cannot preview (FastAsyncWorldEdit writes around the world API)
     */
    default PreviewHandle preview(UUID actor, String world, java.util.function.Function<Vec3, String> overlay, long maxBlocks) {
        throw new EditException("drafts are not supported with " + name());
    }

    /** @return number of steps actually undone */
    int undo(UUID actor, int steps);

    /**
     * Reads the block states in {@code box}, without the {@code minecraft:} namespace.
     * Index order is y, then z, then x (see {@link RegionCodec#index}).
     */
    String[] read(String world, Box box);

    /**
     * Reads a WorldEdit schematic file ({@code .schem}, {@code .schematic}). It does no world access, so unlike the
     * other methods it may be called from any thread.
     *
     * @throws EditException when WorldEdit does not recognise the format or the clipboard holds more than
     *                       {@code maxVolume} blocks
     */
    Schematic readSchematic(java.nio.file.Path file, long maxVolume);
}
