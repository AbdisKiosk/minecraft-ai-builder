package me.abdiskiosk.minecraftaibuilder.common;

/**
 * An edit session that changes nothing in the world: WorldEdit runs every op as usual, but the blocks are recorded
 * instead of placed. {@link #close()} never commits or remembers anything for undo.
 */
public interface PreviewHandle extends EditHandle {

    /** What the ops would change; call after {@link #close()}. */
    BlockDiff changes();
}
