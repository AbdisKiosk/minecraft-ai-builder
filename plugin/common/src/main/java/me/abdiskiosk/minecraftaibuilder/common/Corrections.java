package me.abdiskiosk.minecraftaibuilder.common;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * What a person changed in blocks an edit placed: the cells of a history entry whose state the world no longer holds.
 * The expected state of a cell is the entry's after-state, or the after-state of the newest later entry that changed
 * it, so later edits, reverts and approved drafts are not counted. Changes the world makes by itself (leaf decay,
 * grass spreading, crops growing, liquids flowing, fire, melting) are dropped, and so is a result below a threshold.
 * Only cells the entry changed are seen; blocks added beside it are not.
 */
public final class Corrections {

    /** A result is reported only when it has at least {@code minCells} cells or {@code minShare} of the entry. */
    public record Threshold(int minCells, double minShare) {
        public static final Threshold DEFAULT = new Threshold(4, 0.02);
    }

    /** Cells of block type {@code from} that are now {@code to} (types without properties; {@code from} may be air). */
    public record Substitution(String from, String to, long count, List<Vec3> at) {
    }

    public record TypeCount(String type, long count) {
    }

    /** Cells of {@code type} whose properties changed; {@code props} names those that differ, most frequent first. */
    public record StateChange(String type, long count, List<String> props) {
    }

    /**
     * @param diff          expected state to state now, for each corrected cell
     * @param placed        cells the entry changed
     * @param substitutions largest first; likewise {@code removed} (now air) and {@code states} (same type, other
     *                      properties)
     */
    public record Result(BlockDiff diff, long cells, long placed, double share, List<Substitution> substitutions,
                         List<TypeCount> removed, List<StateChange> states) {
        public boolean corrected() {
            return cells > 0;
        }
    }

    /** Sample positions listed per substitution. */
    public static final int MAX_AT = 5;
    /** Properties named per state row. */
    public static final int MAX_PROPS = 3;

    private Corrections() {
    }

    public static Result find(BlockDiff entry, List<BlockDiff> later, Function<Vec3, String> current) {
        return find(entry, later, List.of(), current, Threshold.DEFAULT);
    }

    /**
     * @param later   diffs of later entries that overlap the entry, oldest first
     * @param unknown boxes of later edits whose diff is unknown; cells inside them are skipped
     * @param current the state now at a position
     */
    public static Result find(BlockDiff entry, List<BlockDiff> later, List<Box> unknown, Function<Vec3, String> current,
                              Threshold threshold) {
        Map<Vec3, String> expected = new HashMap<>(entry.size() * 2);
        for (BlockDiff.Change c : entry.changes()) expected.put(c.pos(), c.after());
        for (BlockDiff d : later) {
            for (BlockDiff.Change c : d.changes()) expected.computeIfPresent(c.pos(), (p, old) -> c.after());
        }

        BlockDiff.Builder diff = new BlockDiff.Builder();
        Map<List<String>, long[]> subs = new HashMap<>();
        Map<List<String>, List<Vec3>> subsAt = new HashMap<>();
        Map<String, long[]> removed = new HashMap<>();
        Map<String, long[]> states = new HashMap<>();
        Map<String, Map<String, long[]>> stateProps = new HashMap<>();
        Map<List<String>, Boolean> naturalCache = new HashMap<>();
        long cells = 0;
        for (BlockDiff.Change c : entry.changes()) {
            Vec3 p = c.pos();
            if (inside(unknown, p)) continue;
            String want = air(expected.get(p));
            String now = air(current.apply(p));
            if (BlockDiff.same(want, now)) continue;
            if (naturalCache.computeIfAbsent(List.of(want, now), k -> natural(k.get(0), k.get(1)))) continue;
            cells++;
            diff.change(p, want, now);
            String from = RegionCodec.stripProperties(want), to = RegionCodec.stripProperties(now);
            if (RegionCodec.isAir(now)) {
                removed.computeIfAbsent(from, k -> new long[1])[0]++;
            } else if (from.equals(to)) {
                states.computeIfAbsent(from, k -> new long[1])[0]++;
                Map<String, long[]> byProp = stateProps.computeIfAbsent(from, k -> new HashMap<>());
                for (String prop : differing(want, now)) byProp.computeIfAbsent(prop, k -> new long[1])[0]++;
            } else {
                List<String> key = List.of(from, to);
                subs.computeIfAbsent(key, k -> new long[1])[0]++;
                List<Vec3> at = subsAt.computeIfAbsent(key, k -> new ArrayList<>());
                if (at.size() < MAX_AT) at.add(p);
            }
        }

        long placed = entry.size();
        double share = placed == 0 ? 0 : (double) cells / placed;
        if (cells == 0 || (cells < threshold.minCells() && share < threshold.minShare())) {
            return new Result(BlockDiff.empty(), 0, placed, 0, List.of(), List.of(), List.of());
        }
        List<Substitution> subList = new ArrayList<>();
        subs.forEach((k, n) -> subList.add(new Substitution(k.get(0), k.get(1), n[0], List.copyOf(subsAt.get(k)))));
        subList.sort(Comparator.comparingLong((Substitution s) -> -s.count()).thenComparing(Substitution::from)
                .thenComparing(Substitution::to));
        List<StateChange> stateList = new ArrayList<>();
        for (TypeCount t : counts(states)) {
            List<String> props = new ArrayList<>();
            for (TypeCount p : counts(stateProps.getOrDefault(t.type(), Map.of()))) {
                if (props.size() < MAX_PROPS) props.add(p.type());
            }
            stateList.add(new StateChange(t.type(), t.count(), List.copyOf(props)));
        }
        return new Result(diff.build(), cells, placed, share, subList, counts(removed), stateList);
    }

    /** The properties whose values differ between two states of one type, by name. */
    private static List<String> differing(String a, String b) {
        Map<String, String> pa = props(a), pb = props(b);
        Set<String> keys = new java.util.TreeSet<>(pa.keySet());
        keys.addAll(pb.keySet());
        List<String> out = new ArrayList<>();
        for (String k : keys) if (!Objects.equals(pa.get(k), pb.get(k))) out.add(k);
        return out;
    }

    private static List<TypeCount> counts(Map<String, long[]> map) {
        List<TypeCount> out = new ArrayList<>();
        map.forEach((type, n) -> out.add(new TypeCount(type, n[0])));
        out.sort(Comparator.comparingLong((TypeCount t) -> -t.count()).thenComparing(TypeCount::type));
        return out;
    }

    private static boolean inside(List<Box> boxes, Vec3 p) {
        for (Box b : boxes) {
            if (p.x() >= b.min().x() && p.x() <= b.max().x() && p.y() >= b.min().y() && p.y() <= b.max().y()
                    && p.z() >= b.min().z() && p.z() <= b.max().z()) return true;
        }
        return false;
    }

    private static String air(String state) {
        return RegionCodec.isAir(state) ? "air" : state;
    }

    // ---- changes the world makes by itself ----

    /** Properties that change without anyone editing the block: growth, liquids, power, neighbours, use. */
    private static final Set<String> DYNAMIC = Set.of(
            "age", "stage", "distance", "persistent", "snowy", "level", "layers", "moisture", "power", "powered", "lit",
            "open", "in_wall", "triggered", "enabled", "extended", "honey_level", "occupied", "has_book", "has_record",
            "note", "waterlogged", "berries", "bites", "charges", "hatch", "eggs", "attached", "disarmed", "shape",
            "signal_fire", "has_bottle_0", "has_bottle_1", "has_bottle_2", "dusted", "thickness", "bloom", "can_summon",
            "shrieking", "sculk_sensor_phase", "trial_spawner_state", "vault_state", "ominous", "crafting", "tip",
            "inverted", "drag", "flower_amount", "slot_0_occupied", "slot_1_occupied", "slot_2_occupied",
            "slot_3_occupied", "slot_4_occupied", "slot_5_occupied", "tilt");

    private static final Set<String> SIDES = Set.of("north", "south", "east", "west", "up", "down");

    /** Blocks whose side properties follow their neighbours or grow. */
    private static boolean connects(String type) {
        return type.endsWith("fence") || type.endsWith("_wall") || type.contains("pane") || type.endsWith("bars")
                || type.equals("redstone_wire") || type.equals("tripwire") || type.equals("chorus_plant")
                || type.equals("vine") || type.equals("glow_lichen") || type.equals("sculk_vein") || type.equals("fire")
                || type.endsWith("mushroom_block") || type.equals("mushroom_stem") || type.equals("resin_clump");
    }

    private static final Set<String> LIQUIDS = Set.of("water", "lava", "bubble_column");
    private static final Set<String> ICE = Set.of("water", "ice", "frosted_ice");
    private static final Set<String> LAVA_PRODUCTS = Set.of("obsidian", "cobblestone", "stone", "basalt");
    private static final Set<String> FIRE = Set.of("fire", "soul_fire");
    private static final Set<String> HATCHES = Set.of("turtle_egg", "sniffer_egg", "frogspawn");

    /** Blocks that grow into air by themselves. */
    private static final Set<String> GROWS = Set.of(
            "vine", "cave_vines", "weeping_vines", "twisting_vines", "kelp", "sugar_cane", "cactus", "cactus_flower",
            "bamboo", "chorus_flower", "chorus_plant", "pointed_dripstone", "pumpkin", "melon", "small_amethyst_bud");

    /** One block turning into the next as it grows, or back when the part above it is gone. */
    private static final Set<String> GROWS_INTO = Set.of(
            "kelp>kelp_plant", "cave_vines>cave_vines_plant", "weeping_vines>weeping_vines_plant",
            "twisting_vines>twisting_vines_plant", "bamboo_sapling>bamboo", "chorus_flower>chorus_plant",
            "small_amethyst_bud>medium_amethyst_bud", "medium_amethyst_bud>large_amethyst_bud",
            "large_amethyst_bud>amethyst_cluster", "torchflower_crop>torchflower", "pitcher_crop>pitcher_plant",
            "melon_stem>attached_melon_stem", "pumpkin_stem>attached_pumpkin_stem");

    /** A change from {@code want} to {@code now} that the world makes on its own (both already normalised air). */
    static boolean natural(String want, String now) {
        String a = RegionCodec.stripProperties(want), b = RegionCodec.stripProperties(now);
        if (a.equals(b)) return onlyDynamic(a, props(want), props(now));
        boolean airA = a.equals("air"), airB = b.equals("air");
        if ((FIRE.contains(a) && airB) || (airA && FIRE.contains(b))) return true;
        if (LIQUIDS.contains(a) && LIQUIDS.contains(b)) return true;
        if ((LIQUIDS.contains(a) && airB && flowing(want)) || (airA && LIQUIDS.contains(b) && flowing(now))) return true;
        if ((ICE.contains(a) && ICE.contains(b)) || ((a.equals("ice") || a.equals("frosted_ice")) && airB)) return true;
        if (a.equals("lava") && LAVA_PRODUCTS.contains(b)) return true;
        if (a.endsWith("leaves") && airB) return true;
        if (spreads(a, b) || spreads(b, a) || (a.equals("farmland") && b.equals("dirt"))) return true;
        if ((a.equals("snow") && airB) || (airA && b.equals("snow"))) return true;
        if (grew(a, b)) return true;
        if (HATCHES.contains(a) && airB) return true;
        if (!a.startsWith("waxed_") && !b.startsWith("waxed_") && a.contains("copper") && unweathered(a).equals(unweathered(b))) return true;
        if (b.equals("dead_" + a)) return true; // coral out of water
        if (a.endsWith("_concrete_powder") && b.equals(a.substring(0, a.length() - "_powder".length()))) return true;
        return a.equals("sponge") && b.equals("wet_sponge");
    }

    private static boolean onlyDynamic(String type, Map<String, String> a, Map<String, String> b) {
        Set<String> keys = new HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        boolean sides = connects(type);
        for (String k : keys) {
            if (Objects.equals(a.get(k), b.get(k))) continue;
            if (!DYNAMIC.contains(k) && !(sides && SIDES.contains(k))) return false;
        }
        return true;
    }

    /** Grass and mycelium spreading onto dirt, nylium onto netherrack, and both dying back when covered. */
    private static boolean spreads(String from, String to) {
        return (from.equals("dirt") && (to.equals("grass_block") || to.equals("mycelium")))
                || (from.equals("netherrack") && to.endsWith("_nylium"));
    }

    private static boolean grew(String a, String b) {
        if ((a.endsWith("_sapling") || a.endsWith("_propagule") || a.equals("azalea") || a.equals("flowering_azalea"))
                && (b.endsWith("_log") || b.endsWith("_leaves") || b.endsWith("_wood") || b.endsWith("_stem"))) return true;
        if (a.equals("air") && GROWS.contains(b)) return true;
        return GROWS_INTO.contains(a + ">" + b) || GROWS_INTO.contains(b + ">" + a);
    }

    private static boolean flowing(String state) {
        String level = props(state).get("level");
        return level != null && !level.equals("0");
    }

    private static String unweathered(String type) {
        for (String p : new String[]{"exposed_", "weathered_", "oxidized_"}) {
            if (type.startsWith(p)) {
                type = type.substring(p.length());
                break;
            }
        }
        return type.equals("copper_block") ? "copper" : type;
    }

    private static Map<String, String> props(String state) {
        if (state.indexOf('[') < 0) return Map.of();
        try {
            return BlockState.parse(state).props();
        } catch (EditException e) {
            return Map.of();
        }
    }
}
