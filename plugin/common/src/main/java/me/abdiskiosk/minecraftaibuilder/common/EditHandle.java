package me.abdiskiosk.minecraftaibuilder.common;

/**
 * One WorldEdit EditSession. Everything applied through a handle becomes a single undo step,
 * stored in the actor's WorldEdit history when the handle is closed.
 */
public interface EditHandle extends AutoCloseable {

    /**
     * Parses and translates everything {@code op} needs (patterns, masks, block states) without touching the world.
     * Called for every op before the first {@link #apply}, so bad input fails before anything is placed.
     */
    default void prepare(Op op) {
    }

    /** @return number of blocks changed */
    int apply(Op op);

    /**
     * Pastes a blueprint with its minimum corner at {@code origin} after rotation.
     *
     * @param rotation degrees around Y, multiple of 90, same direction as WorldEdit's //rotate
     */
    int paste(Blueprint blueprint, Vec3 origin, int rotation);

    /** Writes buffered changes to the world (spreads work across ticks on vanilla WorldEdit). */
    void flush();

    /** Commits remaining changes and remembers the session for undo. */
    @Override
    void close();
}
