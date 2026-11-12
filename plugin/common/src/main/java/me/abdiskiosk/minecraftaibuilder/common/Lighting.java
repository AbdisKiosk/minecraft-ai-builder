package me.abdiskiosk.minecraftaibuilder.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks where to put light sources so that the "dark" check of {@link RegionLint} comes back clean, which saves a
 * builder the check, place, re-check loop. Uses the lint's own light propagation and dark mask.
 */
public final class Lighting {

    public static final int MAX_LIGHTS = 5000;
    /** Side of the squares that count dark cells per layer, so the nearest-cell search skips lit areas. */
    private static final int TILE = 16;

    private Lighting() {
    }

    /**
     * Where to put light sources so that no cell the "dark" check of RegionLint would report is left. Each light goes
     * at the cell nearest the centroid of the first remaining dark patch (4-neighbour, one layer, as the lint groups
     * them), so the result is deterministic.
     *
     * @param states indexed with {@link RegionCodec#index}, as read from the world
     * @param emit   block light the placed block gives off, 1..15 (a torch is 14)
     * @return positions (world coordinates), each an air cell with a solid block below it, in placement order
     */
    public static List<Vec3> place(Box box, String[] states, int emit) {
        return place(box, states, emit, box.max().y());
    }

    /**
     * As {@link #place(Box, String[], int)}, but only floors up to {@code topY} are lit. The layers above it are
     * there to be looked at: a room is dark because of the roof over it, which often lies above the room itself.
     */
    public static List<Vec3> place(Box box, String[] states, int emit, int topY) {
        if (emit < 1 || emit > 15) throw new IllegalArgumentException("emit must be 1..15: " + emit);
        int layers = Math.max(0, Math.min(box.sizeY(), topY - box.min().y() + 1));
        return new Run(new RegionLint.Grid(box, states), emit, layers).place();
    }

    /** Cells within {@code r} steps of one cell, the most a light that reaches {@code r} steps can light. */
    private static long reach(int r) {
        return (2L * r + 1) * (2L * r * r + 2L * r + 3) / 3;
    }

    private static EditException tooMany() {
        return new EditException("the region needs too many lights (more than " + MAX_LIGHTS + "); use a smaller region");
    }

    /** A dark patch: its cells are the dark cells labelled with its id in {@code Run.comp}. */
    private static final class Cluster {
        long count, sumX, sumZ;
    }

    /**
     * Patches are flooded once and then kept up to date: a light removes cells from its patch, and only a search
     * from the edge of the removed cells tells whether the patch split, so a large floor is not re-flooded per light.
     */
    private static final class Run {
        final RegionLint.Grid g;
        final int emit, sx, sz, layer, tilesX, tilesZ;
        final int[] light;
        final boolean[] dark;
        final int[] comp;
        final int[] tileDark;
        final int[] queue;
        final int[] mark;
        final Map<Integer, Cluster> clusters = new HashMap<>();
        int remaining, lastId, epoch;

        /** @param layers how many layers, from the bottom, may hold dark cells; the rest only cast shade */
        Run(RegionLint.Grid g, int emit, int layers) {
            this.g = g;
            this.emit = emit;
            sx = g.sx;
            sz = g.sz;
            layer = sx * sz;
            tilesX = (sx + TILE - 1) / TILE;
            tilesZ = (sz + TILE - 1) / TILE;
            light = RegionLint.light(g);
            dark = RegionLint.darkMask(g, light);
            Arrays.fill(dark, Math.min(g.n, layers * layer), g.n, false);
            comp = new int[g.n];
            tileDark = new int[g.sy * tilesX * tilesZ];
            queue = new int[g.n];
            mark = new int[g.n];
            for (int i = 0; i < g.n; i++) {
                if (!dark[i]) continue;
                remaining++;
                tileDark[tile(i)]++;
            }
        }

        int tile(int i) {
            int p = i % layer;
            return (i / layer * tilesZ + p / sx / TILE) * tilesX + p % sx / TILE;
        }

        List<Vec3> place() {
            if (remaining > (long) MAX_LIGHTS * reach(emit - 1)) throw tooMany();
            List<Vec3> out = new ArrayList<>();
            int next = 0;
            while (remaining > 0) {
                if (out.size() == MAX_LIGHTS) throw tooMany();
                while (!dark[next]) next++;
                Cluster c = clusters.get(comp[next]);
                if (c == null) c = flood(next);
                int best = nearest(comp[next], next / layer, (double) c.sumX / c.count, (double) c.sumZ / c.count);
                out.add(g.pos(best));
                light(best);
            }
            return out;
        }

        Cluster flood(int start) {
            int id = ++lastId;
            Cluster c = new Cluster();
            int h = 0, t = 0;
            queue[t++] = start;
            comp[start] = id;
            while (h < t) {
                int i = queue[h++];
                int p = i % layer, x = p % sx, z = p / sx;
                c.count++;
                c.sumX += x;
                c.sumZ += z;
                if (x > 0 && dark[i - 1] && comp[i - 1] != id) { comp[i - 1] = id; queue[t++] = i - 1; }
                if (x < sx - 1 && dark[i + 1] && comp[i + 1] != id) { comp[i + 1] = id; queue[t++] = i + 1; }
                if (z > 0 && dark[i - sx] && comp[i - sx] != id) { comp[i - sx] = id; queue[t++] = i - sx; }
                if (z < sz - 1 && dark[i + sx] && comp[i + sx] != id) { comp[i + sx] = id; queue[t++] = i + sx; }
            }
            clusters.put(id, c);
            return c;
        }

        /** The cell of patch {@code id} nearest {@code (cx, cz)}, ties to the lowest index, searched in rings of tiles. */
        int nearest(int id, int y, double cx, double cz) {
            int tx0 = (int) cx / TILE, tz0 = (int) cz / TILE;
            int best = Integer.MAX_VALUE;
            double bestDist = Double.MAX_VALUE;
            int rings = Math.max(Math.max(tx0, tilesX - 1 - tx0), Math.max(tz0, tilesZ - 1 - tz0));
            for (int r = 0; r <= rings; r++) {
                double gap = Math.max(0, r - 1) * (double) TILE;
                if (r > 0 && gap * gap >= bestDist) break;
                for (int tz = tz0 - r; tz <= tz0 + r; tz++) {
                    if (tz < 0 || tz >= tilesZ) continue;
                    boolean edge = tz == tz0 - r || tz == tz0 + r;
                    for (int tx = tx0 - r; tx <= tx0 + r; tx += edge ? 1 : 2 * r) {
                        if (tx < 0 || tx >= tilesX || tileDark[(y * tilesZ + tz) * tilesX + tx] == 0) continue;
                        for (int z = tz * TILE; z < Math.min(sz, tz * TILE + TILE); z++) {
                            for (int x = tx * TILE; x < Math.min(sx, tx * TILE + TILE); x++) {
                                int i = y * layer + z * sx + x;
                                if (!dark[i] || comp[i] != id) continue;
                                double dx = x - cx, dz = z - cz, dist = dx * dx + dz * dz;
                                if (dist < bestDist || (dist == bestDist && i < best)) {
                                    bestDist = dist;
                                    best = i;
                                }
                            }
                        }
                    }
                }
            }
            return best;
        }

        /** Places a light at {@code at}, spreads it breadth first and updates the patches it lit. */
        void light(int at) {
            light[at] = Math.max(light[at], emit);
            int h = 0, t = 0;
            queue[t++] = at;
            List<Integer> cleared = new ArrayList<>();
            while (h < t) {
                int i = queue[h++];
                int l = light[i];
                if (dark[i]) {
                    dark[i] = false;
                    remaining--;
                    tileDark[tile(i)]--;
                    cleared.add(i);
                }
                if (l <= 1) continue;
                int p = i % layer, x = p % sx, z = p / sx, y = i / layer;
                if (x > 0) t = spread(t, i - 1, l - 1);
                if (x < sx - 1) t = spread(t, i + 1, l - 1);
                if (z > 0) t = spread(t, i - sx, l - 1);
                if (z < sz - 1) t = spread(t, i + sx, l - 1);
                if (y > 0) t = spread(t, i - layer, l - 1);
                if (y < g.sy - 1) t = spread(t, i + layer, l - 1);
            }
            Map<Integer, List<Integer>> byCluster = new HashMap<>();
            for (int i : cleared) {
                Cluster c = clusters.get(comp[i]);
                if (c == null) continue;
                int p = i % layer;
                c.count--;
                c.sumX -= p % sx;
                c.sumZ -= p / sx;
                byCluster.computeIfAbsent(comp[i], k -> new ArrayList<>()).add(i);
            }
            for (Map.Entry<Integer, List<Integer>> e : byCluster.entrySet()) {
                if (clusters.get(e.getKey()).count == 0) clusters.remove(e.getKey());
                else split(e.getKey(), e.getValue());
            }
        }

        /** Breadth first from one source, so a cell is first reached at its brightest level from that source. */
        int spread(int t, int j, int level) {
            if (light[j] >= level || g.cells[j].opaque()) return t;
            light[j] = level;
            queue[t] = j;
            return t + 1;
        }

        /**
         * Whether patch {@code id} fell apart when {@code removed} went dark-free. It stays whole if every remaining
         * cell next to a removed one reaches the others, so searches start from those cells at once and stop as soon
         * as all of them meet or all but one have run out; the pieces that ran out become patches of their own.
         */
        void split(int id, List<Integer> removed) {
            epoch++;
            List<Integer> seeds = new ArrayList<>();
            for (int i : removed) {
                int p = i % layer, x = p % sx, z = p / sx;
                if (x > 0) seed(seeds, id, i - 1);
                if (x < sx - 1) seed(seeds, id, i + 1);
                if (z > 0) seed(seeds, id, i - sx);
                if (z < sz - 1) seed(seeds, id, i + sx);
            }
            int k = seeds.size();
            if (k <= 1) return;
            int[] parent = new int[k];
            int[] pending = new int[k];
            Arrays.fill(pending, 1);
            for (int s = 0; s < k; s++) parent[s] = s;
            int t = 0;
            for (int s = 0; s < k; s++) queue[t++] = seeds.get(s);
            // mark[i] - base is the search that reached cell i; base moves past every search id of earlier calls.
            int base = epoch;
            for (int s = 0; s < k; s++) mark[seeds.get(s)] = base + s;
            epoch += k;
            int roots = k, active = k, h = 0;
            while (roots > 1 && active > 1 && h < t) {
                int i = queue[h++];
                int r = find(parent, mark[i] - base);
                int p = i % layer, x = p % sx, z = p / sx;
                for (int d = 0; d < 4; d++) {
                    int j;
                    if (d == 0) { if (x == 0) continue; j = i - 1; }
                    else if (d == 1) { if (x == sx - 1) continue; j = i + 1; }
                    else if (d == 2) { if (z == 0) continue; j = i - sx; }
                    else { if (z == sz - 1) continue; j = i + sx; }
                    if (!dark[j] || comp[j] != id) continue;
                    r = find(parent, r);
                    if (mark[j] < base) {
                        mark[j] = base + r;
                        queue[t++] = j;
                        pending[r]++;
                        continue;
                    }
                    int r2 = find(parent, mark[j] - base);
                    if (r2 == r) continue;
                    if (pending[r] > 0 && pending[r2] > 0) active--;
                    parent[r2] = r;
                    pending[r] += pending[r2];
                    roots--;
                }
                r = find(parent, r);
                if (--pending[r] == 0) active--;
            }
            if (roots == 1) return;
            int keep = -1;
            for (int s = 0; s < k; s++) if (parent[s] == s && pending[s] > 0) keep = s;
            Cluster whole = clusters.get(id);
            Map<Integer, Integer> newIds = new HashMap<>();
            for (int q = 0; q < t; q++) {
                int i = queue[q];
                int r = find(parent, mark[i] - base);
                if (r == keep) continue;
                int nid = newIds.computeIfAbsent(r, x -> ++lastId);
                Cluster piece = clusters.computeIfAbsent(nid, x -> new Cluster());
                int p = i % layer;
                comp[i] = nid;
                piece.count++;
                piece.sumX += p % sx;
                piece.sumZ += p / sx;
                whole.count--;
                whole.sumX -= p % sx;
                whole.sumZ -= p / sx;
            }
            if (whole.count == 0) clusters.remove(id);
        }

        void seed(List<Integer> seeds, int id, int j) {
            if (!dark[j] || comp[j] != id || mark[j] == epoch) return;
            mark[j] = epoch;
            seeds.add(j);
        }

        static int find(int[] parent, int s) {
            while (parent[s] != s) {
                parent[s] = parent[parent[s]];
                s = parent[s];
            }
            return s;
        }
    }
}
