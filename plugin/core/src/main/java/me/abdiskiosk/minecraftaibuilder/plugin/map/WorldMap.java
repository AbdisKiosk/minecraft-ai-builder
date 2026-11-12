package me.abdiskiosk.minecraftaibuilder.plugin.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.RelayProtocol;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import me.abdiskiosk.minecraftaibuilder.common.map.ChunkColumns;
import me.abdiskiosk.minecraftaibuilder.common.map.MapRaster;
import me.abdiskiosk.minecraftaibuilder.common.map.RegionFile;
import me.abdiskiosk.minecraftaibuilder.plugin.BuildService;
import me.abdiskiosk.minecraftaibuilder.plugin.Markers;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The panel's map: top-down tiles of a world, drawn from its region files on disk, so viewing the map never loads a
 * chunk into the server. A zoom-0 tile is one region file (512x512 blocks, one pixel each); each zoom level above
 * halves four tiles of the one below into one. Tiles are cached in {@code plugins/AIBuilder/map/} under a version
 * made from the region file's time stamp, and drawn again only when that changes.
 *
 * <p>Region files lag behind the world by up to an autosave. Chunks that an edit job changed are drawn from a
 * snapshot of the loaded chunk until the chunk unloads (and so is saved), so Claude's builds show up at once.
 *
 * <p>Drawing runs on its own low-priority threads, newest request first, so the part of the map someone is looking
 * at comes before tiles they panned away from.
 */
public final class WorldMap implements BuildService.JobListener {

    public record Settings(boolean enabled, int threads) {
    }

    public static final int TILE = 512;
    public static final int MAX_ZOOM = 6;
    /** Longest side of an area marked on the map, in blocks. */
    static final int MAX_AREA_SIDE = 2048;
    private static final int SNAPSHOTS_PER_TICK = 16;
    /** An edit touching more chunks than this only bumps its regions' versions; they show once saved. */
    private static final int MAX_DIRTY_CHUNKS = 16_384;
    private static final long LISTING_MS = 2000;
    /** A region file with nothing past its 8 KiB header holds no chunks. */
    private static final long HEADER_BYTES = 8192;
    private static final int DEFAULT_GROUND = 63;
    private static final int DEFAULT_HEADROOM = 32;

    /** One world as the map sees it. */
    record Dim(String name, Path regionDir, int ceiling, int minY, int maxY) {
    }

    /** The region files of a world, as (rx, rz) to version, read at most every {@link #LISTING_MS}. */
    private record Listing(long at, Map<Long, String> versions) {
    }

    /** Edits to one region since the panel last saw it: a count, and chunks that may not be saved yet. */
    private static final class Dirty {
        final AtomicInteger gen = new AtomicInteger();
        final Set<Long> chunks = ConcurrentHashMap.newKeySet();
    }

    private final BuildService service;
    private final Path cacheDir;
    private final Logger logger;
    private final ThreadPoolExecutor pool;
    private final Executor newestFirst;
    private final AtomicLong order = new AtomicLong();
    private final Map<String, CompletableFuture<byte[]>> inflight = new ConcurrentHashMap<>();
    private final Map<String, Dim> dims = new ConcurrentHashMap<>();
    private final Map<String, Listing> listings = new ConcurrentHashMap<>();
    private final Map<String, Dirty> dirty = new ConcurrentHashMap<>();

    public WorldMap(BuildService service, Settings settings, Logger logger) {
        this.service = service;
        this.cacheDir = service.dataDir().resolve("map");
        this.logger = logger;
        int threads = Math.max(1, settings.threads());
        AtomicInteger n = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new PriorityBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "AIBuilder-Map-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        this.pool.allowCoreThreadTimeOut(true);
        this.newestFirst = r -> pool.execute(new Ordered(r, order.incrementAndGet()));
    }

    /** A task in the pool's queue; the most recently queued runs first. */
    private record Ordered(Runnable task, long order) implements Runnable, Comparable<Ordered> {
        @Override
        public void run() {
            task.run();
        }

        @Override
        public int compareTo(Ordered o) {
            return Long.compare(o.order, order);
        }
    }

    public void close() {
        pool.shutdownNow();
    }

    /**
     * Answers the panel's map requests: {@code index}, {@code tile}, {@code area} and {@code unarea}. Fails with
     * {@link EditException} for a bad request.
     */
    public CompletableFuture<JsonElement> handle(String op, JsonObject args) {
        return switch (op) {
            case "index" -> CompletableFuture.completedFuture(index(world(args)));
            case "tile" -> tile(args);
            case "area" -> area(args);
            case "unarea" -> {
                String world = world(args);
                JsonObject out = new JsonObject();
                out.addProperty("removed", service.markers().removeArea(world, str(args, "name")));
                yield CompletableFuture.completedFuture(out);
            }
            default -> throw new EditException("unknown map op '" + op + "'");
        };
    }

    // ---- index ----

    private JsonObject index(String world) {
        Dim d = dim(world);
        Listing l = listing(d, true);
        JsonObject out = new JsonObject();
        out.addProperty("world", world);
        JsonArray worlds = new JsonArray();
        service.worlds().forEach(worlds::add);
        out.add("worlds", worlds);
        out.addProperty("tile", TILE);
        out.addProperty("maxZoom", MAX_ZOOM);
        out.addProperty("minY", d.minY());
        out.addProperty("maxY", d.maxY() - 1);
        JsonArray regions = new JsonArray();
        l.versions().forEach((key, v) -> {
            JsonArray r = new JsonArray();
            r.add(regionX(key));
            r.add(regionZ(key));
            r.add(v);
            regions.add(r);
        });
        out.add("regions", regions);
        service.sync(() -> {
            World w = Bukkit.getWorld(world);
            if (w == null) return null;
            Location spawn = w.getSpawnLocation();
            out.add("spawn", xz(spawn.getBlockX(), spawn.getBlockZ()));
            JsonArray players = new JsonArray();
            for (Player p : w.getPlayers()) {
                Location at = p.getLocation();
                JsonObject o = new JsonObject();
                o.addProperty("name", p.getName());
                o.addProperty("x", at.getBlockX());
                o.addProperty("y", at.getBlockY());
                o.addProperty("z", at.getBlockZ());
                players.add(o);
            }
            out.add("players", players);
            return null;
        });
        JsonObject markers = new JsonObject();
        service.markers().list(world).forEach((name, v) -> {
            JsonArray a = new JsonArray();
            a.add(v.x());
            a.add(v.y());
            a.add(v.z());
            markers.add(name, a);
        });
        out.add("markers", markers);
        JsonObject areas = new JsonObject();
        service.markers().areas(world).forEach((name, a) -> areas.add(name, Markers.json(a)));
        out.add("areas", areas);
        return out;
    }

    private static JsonArray xz(int x, int z) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(z);
        return a;
    }

    // ---- tiles ----

    private CompletableFuture<JsonElement> tile(JsonObject args) {
        Dim d = dim(world(args));
        int zoom = integer(args, "zoom");
        if (zoom < 0 || zoom > MAX_ZOOM) throw new EditException("zoom must be 0-" + MAX_ZOOM);
        int x = integer(args, "x"), z = integer(args, "z");
        String v = version(listing(d, false), zoom, x, z);
        return tile(d, zoom, x, z).thenApply(png -> {
            JsonObject out = new JsonObject();
            out.addProperty("v", v == null ? "" : v);
            if (png == null) out.addProperty("empty", true);
            else out.addProperty("png", Base64.getEncoder().encodeToString(png));
            return out;
        });
    }

    /** The tile's PNG, or null when no region file lies under it. */
    CompletableFuture<byte[]> tile(Dim d, int zoom, int x, int z) {
        String v = version(listing(d, false), zoom, x, z);
        if (v == null) return CompletableFuture.completedFuture(null);
        String key = d.name() + "|" + zoom + "|" + x + "|" + z + "|" + v;
        CompletableFuture<byte[]> f = new CompletableFuture<>();
        CompletableFuture<byte[]> running = inflight.putIfAbsent(key, f);
        if (running != null) return running;
        f.whenComplete((png, e) -> inflight.remove(key, f));
        Path png = cacheFile(d, zoom, x, z, ".png");
        Path ver = cacheFile(d, zoom, x, z, ".v");
        try {
            newestFirst.execute(() -> {
                try {
                    byte[] cached = cached(png, ver, v);
                    if (cached != null) {
                        f.complete(cached);
                    } else if (zoom == 0) {
                        f.complete(store(drawRegion(d, x, z), png, ver, v));
                    } else {
                        compose(d, zoom, x, z, png, ver, v, f);
                    }
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            f.completeExceptionally(new EditException("the map is shutting down"));
        }
        return f;
    }

    /** Halves the four tiles one zoom level down into this one, once they are ready. */
    private void compose(Dim d, int zoom, int x, int z, Path png, Path ver, String v, CompletableFuture<byte[]> f) {
        List<CompletableFuture<byte[]>> quads = new ArrayList<>(4);
        for (int q = 0; q < 4; q++) quads.add(tile(d, zoom - 1, x * 2 + (q & 1), z * 2 + (q >> 1)));
        CompletableFuture.allOf(quads.toArray(CompletableFuture[]::new)).thenRunAsync(() -> {
            int[][] pixels = new int[4][];
            boolean any = false;
            for (int q = 0; q < 4; q++) {
                byte[] b = quads.get(q).join();
                if (b != null) {
                    pixels[q] = MapRaster.decode(b, TILE);
                    any |= pixels[q] != null;
                }
            }
            f.complete(any ? store(MapRaster.png(MapRaster.downsample(pixels, TILE), TILE), png, ver, v) : null);
        }, newestFirst).exceptionally(e -> {
            f.completeExceptionally(e);
            return null;
        });
    }

    /** Draws one region from its file, with the chunks edits changed since it was saved drawn from the world. */
    private byte[] drawRegion(Dim d, int rx, int rz) throws IOException {
        MapRaster raster = new MapRaster(TILE);
        Path file = d.regionDir().resolve("r." + rx + "." + rz + ".mca");
        if (Files.isRegularFile(file)) {
            try (RegionFile region = RegionFile.open(file)) {
                for (int cz = 0; cz < 32; cz++) {
                    for (int cx = 0; cx < 32; cx++) {
                        if (!region.has(cx, cz)) continue;
                        Map<String, Object> chunk = region.chunk(cx, cz, ChunkColumns.SKIP);
                        if (chunk != null) ChunkColumns.draw(chunk, raster, cx * 16, cz * 16, d.ceiling());
                    }
                }
            }
        }
        overlay(d, rx, rz, raster);
        return MapRaster.png(raster.argb(), TILE);
    }

    /** Redraws the region's edited chunks that are still loaded from snapshots, a few per tick. */
    private void overlay(Dim d, int rx, int rz, MapRaster raster) {
        Dirty dd = dirty.get(regionKey(d.name(), rx, rz));
        if (dd == null || dd.chunks.isEmpty()) return;
        List<Long> chunks = new ArrayList<>(dd.chunks);
        for (int i = 0; i < chunks.size(); i += SNAPSHOTS_PER_TICK) {
            List<Long> batch = chunks.subList(i, Math.min(chunks.size(), i + SNAPSHOTS_PER_TICK));
            Map<Long, ChunkSnapshot> snaps = service.sync(() -> {
                Map<Long, ChunkSnapshot> out = new HashMap<>();
                World w = Bukkit.getWorld(d.name());
                for (long c : batch) {
                    int cx = (int) (c >> 32), cz = (int) c;
                    // Only chunks that are loaded anyway: the map never loads one.
                    if (w != null && w.isChunkLoaded(cx, cz)) out.put(c, w.getChunkAt(cx, cz).getChunkSnapshot(true, false, false));
                    else dd.chunks.remove(c); // unloading saved it, so the region file has it now
                }
                return out;
            });
            snaps.forEach((c, snap) -> {
                int cx = regionX(c), cz = regionZ(c);
                Snapshots.draw(snap, raster, (cx - rx * 32) * 16, (cz - rz * 32) * 16, d.minY(), d.maxY(), d.ceiling());
            });
        }
    }

    private static byte[] cached(Path png, Path ver, String v) {
        try {
            if (!Files.isRegularFile(ver) || !Files.readString(ver).equals(v)) return null;
            return Files.readAllBytes(png);
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] store(byte[] data, Path png, Path ver, String v) {
        try {
            Files.createDirectories(png.getParent());
            Path tmp = png.resolveSibling(png.getFileName() + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, png, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(ver, v);
        } catch (IOException e) {
            logger.log(Level.FINE, "map: could not cache " + png, e);
        }
        return data;
    }

    private Path cacheFile(Dim d, int zoom, int x, int z, String ext) {
        return cacheDir.resolve(URLEncoder.encode(d.name(), StandardCharsets.UTF_8)).resolve(String.valueOf(zoom))
                .resolve(x + "_" + z + ext);
    }

    // ---- versions ----

    /** The version of a tile: its region file's for zoom 0, a hash of those under it above; null when there are none. */
    static String version(Map<Long, String> versions, int zoom, int x, int z) {
        if (zoom == 0) return versions.get(key(x, z));
        TreeMap<Long, String> under = new TreeMap<>();
        versions.forEach((k, v) -> {
            if ((regionX(k) >> zoom) == x && (regionZ(k) >> zoom) == z) under.put(k, v);
        });
        if (under.isEmpty()) return null;
        long h = 0xcbf29ce484222325L;
        for (Map.Entry<Long, String> e : under.entrySet()) {
            h = (h ^ e.getKey()) * 0x100000001b3L;
            for (int i = 0; i < e.getValue().length(); i++) h = (h ^ e.getValue().charAt(i)) * 0x100000001b3L;
        }
        return Long.toString(h & Long.MAX_VALUE, 36);
    }

    private String version(Listing l, int zoom, int x, int z) {
        return version(l.versions(), zoom, x, z);
    }

    private Listing listing(Dim d, boolean fresh) {
        long now = System.currentTimeMillis();
        Listing l = listings.get(d.name());
        if (l != null && now - l.at() < (fresh ? 500 : LISTING_MS)) return l;
        Map<Long, String> versions = new HashMap<>();
        if (Files.isDirectory(d.regionDir())) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(d.regionDir(), "r.*.mca")) {
                for (Path file : files) {
                    int[] c = RegionFile.coords(file.getFileName().toString());
                    if (c == null) continue;
                    BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
                    if (a.size() <= HEADER_BYTES) continue;
                    Dirty dd = dirty.get(regionKey(d.name(), c[0], c[1]));
                    int gen = dd == null ? 0 : dd.gen.get();
                    versions.put(key(c[0], c[1]), Long.toString(a.lastModifiedTime().toMillis(), 36) + (gen > 0 ? "-" + gen : ""));
                }
            } catch (IOException e) {
                throw new EditException("could not list the region files of '" + d.name() + "': " + e.getMessage());
            }
        }
        l = new Listing(now, versions);
        listings.put(d.name(), l);
        return l;
    }

    static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    static int regionX(long key) {
        return (int) (key >> 32);
    }

    static int regionZ(long key) {
        return (int) key;
    }

    private static String regionKey(String world, int rx, int rz) {
        return world + "|" + rx + "|" + rz;
    }

    // ---- edits ----

    @Override
    public void onJob(String event, BuildService.JobInfo job) {
        if (!RelayProtocol.JOB_END.equals(event) || job.world() == null || job.bounds() == null || job.changed() <= 0) return;
        Box b = job.bounds();
        int cx1 = b.min().x() >> 4, cz1 = b.min().z() >> 4, cx2 = b.max().x() >> 4, cz2 = b.max().z() >> 4;
        long chunks = (long) (cx2 - cx1 + 1) * (cz2 - cz1 + 1);
        for (int rx = cx1 >> 5; rx <= cx2 >> 5; rx++) {
            for (int rz = cz1 >> 5; rz <= cz2 >> 5; rz++) {
                dirty.computeIfAbsent(regionKey(job.world(), rx, rz), k -> new Dirty()).gen.incrementAndGet();
            }
        }
        if (chunks <= MAX_DIRTY_CHUNKS) {
            for (int cx = cx1; cx <= cx2; cx++) {
                for (int cz = cz1; cz <= cz2; cz++) {
                    dirty.get(regionKey(job.world(), cx >> 5, cz >> 5)).chunks.add(key(cx, cz));
                }
            }
        }
        listings.remove(job.world());
    }

    // ---- areas ----

    private CompletableFuture<JsonElement> area(JsonObject args) {
        String world = world(args);
        String name = Markers.name(str(args, "name"));
        JsonArray rect = args.has("rect") && args.get("rect").isJsonArray() ? args.getAsJsonArray("rect") : null;
        if (rect == null || rect.size() != 4) throw new EditException("rect must be [x1,z1,x2,z2]");
        int x1 = rect.get(0).getAsInt(), z1 = rect.get(1).getAsInt(), x2 = rect.get(2).getAsInt(), z2 = rect.get(3).getAsInt();
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2), minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        if (maxX - minX >= MAX_AREA_SIDE || maxZ - minZ >= MAX_AREA_SIDE) {
            throw new EditException("an area is at most " + MAX_AREA_SIDE + " blocks on a side");
        }
        String note = args.has("note") && !args.get("note").isJsonNull() ? args.get("note").getAsString() : "";
        Dim d = dim(world);
        JsonArray y = args.has("y") && args.get("y").isJsonArray() ? args.getAsJsonArray("y") : null;
        CompletableFuture<int[]> heights;
        if (y != null && y.size() == 2) {
            int a = y.get(0).getAsInt(), b = y.get(1).getAsInt();
            heights = CompletableFuture.completedFuture(new int[]{Math.min(a, b), Math.max(a, b)});
        } else {
            heights = new CompletableFuture<>();
            CompletableFuture<int[]> h = heights;
            newestFirst.execute(() -> {
                try {
                    h.complete(surfaceRange(d, minX, minZ, maxX, maxZ));
                } catch (Throwable t) {
                    h.completeExceptionally(t);
                }
            });
        }
        return heights.thenApply(range -> {
            int lo = Math.max(d.minY(), range[0]), hi = Math.min(d.maxY() - 1, range[1]);
            Box box = Box.of(new Vec3(minX, lo, minZ), new Vec3(maxX, Math.max(lo, hi), maxZ));
            boolean replaced = service.markers().setArea(world, name, box, note);
            JsonObject out = Markers.json(service.markers().area(world, name).orElseThrow());
            out.addProperty("name", name);
            out.addProperty("replaced", replaced);
            return out;
        });
    }

    /**
     * From the lowest surface in the rectangle to {@link #DEFAULT_HEADROOM} blocks above the highest, read from the
     * region files; sea level and up where nothing is generated yet.
     */
    private int[] surfaceRange(Dim d, int minX, int minZ, int maxX, int maxZ) throws IOException {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        MapRaster chunkRaster = new MapRaster(16);
        for (int rx = minX >> 9; rx <= maxX >> 9; rx++) {
            for (int rz = minZ >> 9; rz <= maxZ >> 9; rz++) {
                Path file = d.regionDir().resolve("r." + rx + "." + rz + ".mca");
                if (!Files.isRegularFile(file)) continue;
                try (RegionFile region = RegionFile.open(file)) {
                    for (int cx = Math.max(minX >> 4, rx * 32); cx <= Math.min(maxX >> 4, rx * 32 + 31); cx++) {
                        for (int cz = Math.max(minZ >> 4, rz * 32); cz <= Math.min(maxZ >> 4, rz * 32 + 31); cz++) {
                            Map<String, Object> chunk = region.chunk(cx & 31, cz & 31, ChunkColumns.SKIP);
                            if (chunk == null) continue;
                            for (int i = 0; i < 16; i++) for (int j = 0; j < 16; j++) chunkRaster.clear(i, j);
                            if (!ChunkColumns.draw(chunk, chunkRaster, 0, 0, d.ceiling())) continue;
                            for (int bx = Math.max(minX, cx * 16); bx <= Math.min(maxX, cx * 16 + 15); bx++) {
                                for (int bz = Math.max(minZ, cz * 16); bz <= Math.min(maxZ, cz * 16 + 15); bz++) {
                                    if (!chunkRaster.isSet(bx & 15, bz & 15)) continue;
                                    int h = chunkRaster.height(bx & 15, bz & 15);
                                    lo = Math.min(lo, h);
                                    hi = Math.max(hi, h);
                                }
                            }
                        }
                    }
                }
            }
        }
        if (lo == Integer.MAX_VALUE) return new int[]{DEFAULT_GROUND, DEFAULT_GROUND + DEFAULT_HEADROOM};
        return new int[]{lo, hi + DEFAULT_HEADROOM};
    }

    // ---- worlds ----

    private String world(JsonObject args) {
        if (args.has("world") && !args.get("world").isJsonNull() && !args.get("world").getAsString().isEmpty()) {
            return args.get("world").getAsString();
        }
        List<String> worlds = service.worlds();
        if (worlds.isEmpty()) throw new EditException("the server has no worlds loaded");
        return worlds.get(0);
    }

    Dim dim(String world) {
        Dim d = dims.get(world);
        if (d != null) return d;
        d = service.sync(() -> {
            World w = Bukkit.getWorld(world);
            if (w == null) throw new EditException("unknown world '" + world + "'; worlds: " + String.join(", ", service.worlds()));
            World.Environment env = w.getEnvironment();
            return new Dim(world, regionDir(w.getWorldFolder().toPath(), env.name()),
                    env == World.Environment.NETHER ? ChunkColumns.NETHER_CEILING : ChunkColumns.OPEN_SKY,
                    minHeight(w), w.getMaxHeight());
        });
        dims.put(world, d);
        return d;
    }

    /**
     * Where a world keeps its region files: {@code region} for the overworld, {@code DIM-1/region} and
     * {@code DIM1/region} for the nether and the end (Bukkit gives each its own folder), or the
     * {@code dimensions/<namespace>/<name>/region} layout.
     */
    static Path regionDir(Path folder, String environment) {
        List<String> candidates = switch (environment) {
            case "NETHER" -> List.of("DIM-1/region", "dimensions/minecraft/the_nether/region", "region");
            case "THE_END" -> List.of("DIM1/region", "dimensions/minecraft/the_end/region", "region");
            default -> List.of("region", "dimensions/minecraft/overworld/region");
        };
        for (String c : candidates) {
            Path p = folder.resolve(c);
            if (Files.isDirectory(p)) return p;
        }
        return folder.resolve(candidates.get(0));
    }

    private static int minHeight(World w) {
        try {
            return (Integer) World.class.getMethod("getMinHeight").invoke(w);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return 0; // before 1.17 every world starts at 0
        }
    }

    private static String str(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) throw new EditException("missing '" + key + "'");
        return o.get(key).getAsString();
    }

    private static int integer(JsonObject o, String key) {
        try {
            return o.get(key).getAsInt();
        } catch (RuntimeException e) {
            throw new EditException("'" + key + "' must be a whole number");
        }
    }
}
