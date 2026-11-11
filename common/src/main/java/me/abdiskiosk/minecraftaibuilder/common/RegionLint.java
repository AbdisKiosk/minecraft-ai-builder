package me.abdiskiosk.minecraftaibuilder.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds common building mistakes in a read region and reports them as a few coordinates, which costs far fewer
 * tokens than a render. Blocks outside the box are unknown, so nothing is reported that depends on one.
 */
public final class RegionLint {

    public record Issue(String kind, int count, List<Vec3> at, String hint) {
    }

    public static final List<String> KINDS = List.of("floating", "unsupported", "halves", "stairs", "unconnected", "dark");
    private static final int MAX_AT = 5;
    private static final int MAX_AT_LIMIT = 100;

    private RegionLint() {
    }

    /** What the checks need to know about one distinct state, computed once per state. */
    record Traits(String type, Map<String, String> props, boolean air, boolean solid, boolean opaque, int light,
                          boolean needsFloor, boolean wallAttached) {

        static Traits of(String state) {
            String type = RegionCodec.stripProperties(state);
            if (RegionCodec.isAir(type)) return new Traits("air", Map.of(), true, false, false, 0, false, false);
            Map<String, String> props = state.indexOf('[') < 0 ? Map.of() : BlockState.parse(state).props();
            return new Traits(type, props, false, BlockTraits.solid(state), BlockTraits.opaque(state), BlockTraits.light(state),
                    BlockTraits.gravity(type) || BlockTraits.needsFloor(state), BlockTraits.wallAttached(state));
        }

        String prop(String name, String def) {
            return props.getOrDefault(name, def);
        }
    }

    /** The region in local coordinates; index order is y, z, x like {@link RegionCodec#index}. */
    static final class Grid {
        final Box box;
        final int sx, sy, sz, n;
        final Traits[] cells;

        Grid(Box box, String[] states) {
            this.box = box;
            sx = box.sizeX();
            sy = box.sizeY();
            sz = box.sizeZ();
            n = sx * sy * sz;
            cells = new Traits[n];
            Map<String, Traits> cache = new HashMap<>();
            for (int i = 0; i < n; i++) {
                String s = states[i] == null ? "air" : states[i];
                cells[i] = cache.computeIfAbsent(s, Traits::of);
            }
        }

        int x(int i) { return i % sx; }
        int z(int i) { return (i / sx) % sz; }
        int y(int i) { return i / (sx * sz); }

        /** Index of the cell at an offset from {@code i}, or -1 when it is outside the box. */
        int at(int i, int dx, int dy, int dz) {
            int x = x(i) + dx, y = y(i) + dy, z = z(i) + dz;
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return -1;
            return (y * sz + z) * sx + x;
        }

        boolean air(int i) {
            return cells[i].air;
        }

        Vec3 pos(int i) {
            return box.min().add(x(i), y(i), z(i));
        }
    }

    static final int[][] SIX = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    static final int[][] FLAT = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
    /** Blocks that share a face or an edge: the steps of a staircase or a stair roof only touch along edges. */
    private static final int[][] TOUCHING = touching();

    private static int[][] touching() {
        List<int[]> out = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int axes = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (axes == 1 || axes == 2) out.add(new int[]{dx, dy, dz});
                }
            }
        }
        return out.toArray(new int[0][]);
    }

    /** {@link #check(Box, String[], Set, boolean, int)} listing up to {@value #MAX_AT} coordinates per issue. */
    public static List<Issue> check(Box box, String[] states, Set<String> kinds, boolean legacy) {
        return check(box, states, kinds, legacy, MAX_AT);
    }

    /**
     * @param states indexed with {@link RegionCodec#index}
     * @param kinds  which of {@link #KINDS} to run; null runs all
     * @param legacy a 1.8-1.12 server, which does not store fence and pane connections
     * @param maxAt  how many coordinates each issue lists, 1..100
     * @return issues with {@code count > 0}, in {@link #KINDS} order
     */
    public static List<Issue> check(Box box, String[] states, Set<String> kinds, boolean legacy, int maxAt) {
        if (maxAt < 1 || maxAt > MAX_AT_LIMIT) throw new IllegalArgumentException("maxAt must be 1.." + MAX_AT_LIMIT + ": " + maxAt);
        Grid g = new Grid(box, states);
        List<Issue> out = new ArrayList<>();
        for (String kind : KINDS) {
            if (kinds != null && !kinds.contains(kind)) continue;
            Issue issue = switch (kind) {
                case "floating" -> floating(g, maxAt);
                case "unsupported" -> issue(g, kind, unsupported(g), maxAt, "breaks or falls: needs a block below, or behind it for wall blocks");
                case "halves" -> issue(g, kind, halves(g), maxAt, "door or bed is missing its other half");
                case "stairs" -> issue(g, kind, stairs(g), maxAt, "faces down the slope; face it the way the slope rises");
                case "unconnected" -> issue(g, kind, legacy ? List.of() : unconnected(g), maxAt, "connection states unset; re-place it or set north/south/east/west");
                case "dark" -> dark(g, maxAt);
                default -> throw new IllegalStateException(kind);
            };
            if (issue.count() > 0) out.add(issue);
        }
        return out;
    }

    private static Issue issue(Grid g, String kind, List<Integer> cells, int maxAt, String hint) {
        return issue(g, kind, cells.size(), cells, maxAt, hint);
    }

    /** {@code at} is the first {@code maxAt} of {@code cells}; index order is already y, then z, then x. */
    private static Issue issue(Grid g, String kind, int count, List<Integer> cells, int maxAt, String hint) {
        List<Integer> sorted = new ArrayList<>(cells);
        sorted.sort(null);
        List<Vec3> at = new ArrayList<>();
        for (int i = 0; i < Math.min(maxAt, sorted.size()); i++) at.add(g.pos(sorted.get(i)));
        return new Issue(kind, count, at, hint);
    }

    /** Pieces (blocks joined by faces or edges) that touch neither the bottom layer nor a side face of the box. */
    private static Issue floating(Grid g, int maxAt) {
        boolean[] seen = new boolean[g.n];
        int[] queue = new int[g.n];
        List<Integer> lowest = new ArrayList<>();
        for (int start = 0; start < g.n; start++) {
            if (seen[start] || g.air(start)) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            boolean anchored = false;
            while (head < tail) {
                int i = queue[head++];
                int x = g.x(i), z = g.z(i);
                if (g.y(i) == 0 || x == 0 || z == 0 || x == g.sx - 1 || z == g.sz - 1) anchored = true;
                for (int[] d : TOUCHING) {
                    int j = g.at(i, d[0], d[1], d[2]);
                    if (j >= 0 && !seen[j] && !g.air(j)) {
                        seen[j] = true;
                        queue[tail++] = j;
                    }
                }
            }
            if (!anchored) lowest.add(start); // the scan meets each component first at its lowest block
        }
        return issue(g, "floating", lowest, maxAt, "not connected to the ground or the region's sides");
    }

    private static List<Integer> unsupported(Grid g) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < g.n; i++) {
            Traits t = g.cells[i];
            if (t.needsFloor) {
                int below = g.at(i, 0, -1, 0);
                if (below >= 0 && g.air(below)) out.add(i);
            } else if (t.wallAttached) {
                Vec3 f = facing(t);
                int behind = g.at(i, -f.x(), 0, -f.z());
                if (behind >= 0 && !g.cells[behind].solid) out.add(i);
            }
        }
        return out;
    }

    private static List<Integer> halves(Grid g) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < g.n; i++) {
            Traits t = g.cells[i];
            if (t.type.endsWith("_door")) {
                boolean lower = t.prop("half", "lower").equals("lower");
                int partner = g.at(i, 0, lower ? 1 : -1, 0);
                if (partner >= 0 && !partnerIs(g.cells[partner], t.type, "half", "lower", lower ? "upper" : "lower")) out.add(i);
            } else if (t.type.endsWith("_bed")) {
                boolean foot = t.prop("part", "foot").equals("foot");
                Vec3 f = facing(t);
                int partner = foot ? g.at(i, f.x(), 0, f.z()) : g.at(i, -f.x(), 0, -f.z());
                if (partner >= 0 && !partnerIs(g.cells[partner], t.type, "part", "foot", foot ? "head" : "foot")) out.add(i);
            }
        }
        return out;
    }

    private static boolean partnerIs(Traits partner, String type, String prop, String def, String value) {
        return partner.type.equals(type) && partner.prop(prop, def).equals(value);
    }

    /** A bottom stair whose surroundings rise behind it and fall in front of it: the slope goes the other way. */
    private static List<Integer> stairs(Grid g) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < g.n; i++) {
            Traits t = g.cells[i];
            if (!t.type.endsWith("_stairs") || !t.prop("half", "bottom").equals("bottom")) continue;
            Vec3 f = facing(t);
            int highBack = g.at(i, -f.x(), 1, -f.z());
            int highFront = g.at(i, f.x(), 1, f.z());
            int lowFront = g.at(i, f.x(), -1, f.z());
            if (highBack < 0 || highFront < 0 || lowFront < 0) continue;
            if (!g.air(highBack) && g.air(highFront) && !g.air(lowFront)) out.add(i);
        }
        return out;
    }

    private static List<Integer> unconnected(Grid g) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < g.n; i++) {
            Traits t = g.cells[i];
            String family = family(t.type);
            if (family == null || connected(t)) continue;
            for (int[] d : FLAT) {
                int j = g.at(i, d[0], d[1], d[2]);
                if (j < 0) continue;
                Traits n = g.cells[j];
                if ((n.solid && !neverJoined(n.type)) || family.equals(family(n.type)) || (family.equals("fence") && n.type.endsWith("fence_gate"))) {
                    out.add(i);
                    break;
                }
            }
        }
        return out;
    }

    /** Full blocks that fences, panes and walls do not attach to, so standing next to one is not a mistake. */
    private static boolean neverJoined(String type) {
        return type.endsWith("leaves") || type.endsWith("shulker_box") || type.equals("barrier") || type.equals("melon")
                || type.equals("pumpkin") || type.equals("carved_pumpkin") || type.equals("jack_o_lantern");
    }

    /** Blocks that connect to each other: fences, and panes with bars and walls. */
    private static String family(String type) {
        if (type.endsWith("fence")) return "fence";
        if (type.contains("pane") || type.endsWith("bars") || type.endsWith("_wall")) return "pane";
        return null;
    }

    private static boolean connected(Traits t) {
        for (String side : List.of("north", "south", "east", "west")) {
            String v = t.prop(side, "false");
            if (!v.equals("false") && !v.equals("none")) return true;
        }
        return false;
    }

    /** Roofed air with a floor to stand on and block light 0, where mobs can spawn. */
    private static Issue dark(Grid g, int maxAt) {
        boolean[] dark = darkMask(g, light(g));
        boolean[] seen = new boolean[g.n];
        int[] cluster = new int[g.n];
        int count = 0;
        List<Integer> first = new ArrayList<>();
        for (int start = 0; start < g.n; start++) {
            if (!dark[start] || seen[start]) continue;
            first.add(start);
            int h = 0, t = 0;
            cluster[t++] = start;
            seen[start] = true;
            while (h < t) {
                int i = cluster[h++];
                count++;
                for (int[] d : FLAT) {
                    int j = g.at(i, d[0], d[1], d[2]);
                    if (j >= 0 && dark[j] && !seen[j]) {
                        seen[j] = true;
                        cluster[t++] = j;
                    }
                }
            }
        }
        return issue(g, "dark", count, first, maxAt, "block light 0 under a roof: mobs can spawn; add a light, or the light op places them");
    }

    /** Block light of every cell, from the light sources inside the box; shared with {@link Lighting}. */
    static int[] light(Grid g) {
        int[] light = new int[g.n];
        // Brightest first, so every cell is final when its level is processed.
        List<List<Integer>> levels = new ArrayList<>();
        for (int l = 0; l <= 15; l++) levels.add(new ArrayList<>());
        for (int i = 0; i < g.n; i++) {
            light[i] = Math.min(15, g.cells[i].light);
            if (light[i] > 1) levels.get(light[i]).add(i);
        }
        for (int l = 15; l > 1; l--) {
            for (int k = 0; k < levels.get(l).size(); k++) {
                int i = levels.get(l).get(k);
                if (light[i] != l) continue;
                for (int[] d : SIX) {
                    int j = g.at(i, d[0], d[1], d[2]);
                    if (j < 0 || g.cells[j].opaque || light[j] >= l - 1) continue;
                    light[j] = l - 1;
                    levels.get(l - 1).add(j);
                }
            }
        }
        return light;
    }

    /**
     * Roofed air with air above it, a solid block below it and {@code light} 0: the cells the dark check reports.
     * A cell beside one that is open to the sky does not count: the strip under an eave is outdoors, like the yard
     * around it, and lighting it is not what makes a building safe.
     */
    static boolean[] darkMask(Grid g, int[] light) {
        int layer = g.sx * g.sz;
        boolean[] dark = new boolean[g.n];
        boolean[] sky = new boolean[g.n];
        for (int x = 0; x < g.sx; x++) {
            for (int z = 0; z < g.sz; z++) {
                boolean roofed = false;
                for (int y = g.sy - 1; y >= 0; y--) {
                    int i = (y * g.sz + z) * g.sx + x;
                    if (!g.air(i)) roofed = true;
                    sky[i] = !roofed;
                    if (y >= 1 && y <= g.sy - 2) {
                        dark[i] = roofed && g.air(i) && g.air(i + layer) && g.cells[i - layer].solid && light[i] == 0;
                    }
                }
            }
        }
        for (int i = 0; i < g.n; i++) {
            if (!dark[i]) continue;
            for (int[] d : FLAT) {
                int j = g.at(i, d[0], d[1], d[2]);
                if (j >= 0 && sky[j]) {
                    dark[i] = false;
                    break;
                }
            }
        }
        return dark;
    }

    private static Vec3 facing(Traits t) {
        return BlockTraits.facing(t.type + "[facing=" + t.prop("facing", "north") + "]");
    }
}
