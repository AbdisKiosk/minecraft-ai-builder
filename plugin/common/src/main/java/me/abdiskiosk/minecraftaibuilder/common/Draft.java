package me.abdiskiosk.minecraftaibuilder.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Edits waiting for approval in one world, like an unmerged branch. Each edit is an {@link Entry} with its own diff
 * (against the world plus the earlier entries); together they give one change per position, from the world's
 * state when the draft first touched it to the newest state.
 */
public final class Draft {

    public enum Status { OPEN, APPROVED, REJECTED }

    /**
     * @param diff  what this edit changed, against the world plus the entries before it
     * @param label a short description, e.g. "promote from staging"
     */
    public record Entry(String tool, String source, String caller, long time, BlockDiff diff, String label) {
    }

    private final String id;
    private final String world;
    private final long created;
    private Status status = Status.OPEN;
    private String closedBy;
    private long closed;
    private String job;
    private final List<Entry> entries = new ArrayList<>();
    /** Position → {state before the draft, state after it}. */
    private final Map<Vec3, String[]> changes = new HashMap<>();

    public Draft(String id, String world, long created) {
        this.id = id;
        this.world = world;
        this.created = created;
    }

    public String id() { return id; }
    public String world() { return world; }
    public long created() { return created; }
    public Status status() { return status; }
    public String closedBy() { return closedBy; }
    public long closedAt() { return closed; }
    /** The job that placed an approved draft. */
    public String job() { return job; }
    public List<Entry> entries() { return List.copyOf(entries); }
    public int size() { return changes.size(); }
    public boolean open() { return status == Status.OPEN; }

    public void add(Entry entry) {
        requireOpen();
        for (BlockDiff.Change c : entry.diff().changes()) {
            String[] cur = changes.get(c.pos());
            String original = cur != null ? cur[0] : c.before();
            put(c.pos(), original, c.after());
        }
        entries.add(entry);
    }

    /** Takes the newest entry back out; null when there is none. */
    public Entry undoLast() {
        requireOpen();
        if (entries.isEmpty()) return null;
        Entry last = entries.remove(entries.size() - 1);
        for (BlockDiff.Change c : last.diff().changes()) {
            String[] cur = changes.get(c.pos());
            // Gone from the map means the newest entry put the original state back.
            String original = cur != null ? cur[0] : c.after();
            put(c.pos(), original, c.before());
        }
        return last;
    }

    private void put(Vec3 pos, String before, String after) {
        if (BlockDiff.same(before, after)) {
            changes.remove(pos);
        } else {
            changes.put(pos, new String[]{before, after});
        }
    }

    /** The draft's state at {@code pos}, or null where it changes nothing. */
    public String after(Vec3 pos) {
        String[] c = changes.get(pos);
        return c == null ? null : c[1];
    }

    /** Writes the draft's states over {@code states} (a read of {@code box}). */
    public void overlay(Box box, String[] states) {
        if (changes.isEmpty()) return;
        if (box.volume() < changes.size()) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    for (int x = box.min().x(); x <= box.max().x(); x++) {
                        String[] c = changes.get(new Vec3(x, y, z));
                        if (c != null) states[RegionCodec.index(box, x, y, z)] = c[1];
                    }
                }
            }
            return;
        }
        for (Map.Entry<Vec3, String[]> e : changes.entrySet()) {
            Vec3 p = e.getKey();
            if (box.contains(Box.of(p, p))) states[RegionCodec.index(box, p.x(), p.y(), p.z())] = e.getValue()[1];
        }
    }

    /** All changes, from the world as the draft found it to the draft's states. */
    public BlockDiff diff() {
        BlockDiff.Builder b = new BlockDiff.Builder();
        changes.forEach((pos, c) -> b.change(pos, c[0], c[1]));
        return b.build();
    }

    public void close(Status status, String by, long at, String job) {
        requireOpen();
        if (status == Status.OPEN) throw new IllegalArgumentException("close with APPROVED or REJECTED");
        this.status = status;
        this.closedBy = by;
        this.closed = at;
        this.job = job;
    }

    private void requireOpen() {
        if (status != Status.OPEN) throw new EditException("draft " + id + " is " + status.name().toLowerCase(java.util.Locale.ROOT));
    }
}
