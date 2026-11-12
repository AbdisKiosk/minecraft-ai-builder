package me.abdiskiosk.minecraftaibuilder.bridge.we6;

import com.sk89q.worldedit.BlockVector2D;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.inventory.BlockBag;
import com.sk89q.worldedit.util.eventbus.EventBus;
import com.sk89q.worldedit.bukkit.BukkitUtil;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.extension.input.InputParseException;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.transform.BlockTransformExtent;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.pattern.Patterns;
import com.sk89q.worldedit.math.convolution.HeightMap;
import com.sk89q.worldedit.math.convolution.HeightMapFilter;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.patterns.Pattern;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Polygonal2DRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.request.Request;
import com.sk89q.worldedit.util.TreeGenerator;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.registry.BlockRegistry;
import com.sk89q.worldedit.world.registry.LegacyWorldData;
import me.abdiskiosk.minecraftaibuilder.common.BlockCatalog;
import me.abdiskiosk.minecraftaibuilder.common.BlockDiff;
import me.abdiskiosk.minecraftaibuilder.common.Blueprint;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.DryRunStore;
import me.abdiskiosk.minecraftaibuilder.common.EditHandle;
import me.abdiskiosk.minecraftaibuilder.common.Op;
import me.abdiskiosk.minecraftaibuilder.common.PreviewHandle;
import me.abdiskiosk.minecraftaibuilder.common.Schematic;
import me.abdiskiosk.minecraftaibuilder.common.SelectionInfo;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import me.abdiskiosk.minecraftaibuilder.common.WorldEditBridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** WorldEdit 6.x (Minecraft 1.8 - 1.12). Block names are translated with {@link LegacyNames}. */
public final class We6Bridge implements WorldEditBridge {

    private final WorldEditPlugin plugin;
    private final boolean fawe;
    private final BlockCatalog catalog;

    public We6Bridge(boolean fawe) {
        // FAWE for 1.8 implements the same API; it handles its own queueing.
        this.plugin = (WorldEditPlugin) Bukkit.getPluginManager().getPlugin("WorldEdit");
        this.fawe = fawe;
        this.catalog = new We6BlockCatalog(LegacyNames.table(), "Minecraft " + Bukkit.getBukkitVersion().split("-")[0]);
    }

    @Override
    public BlockCatalog catalog() {
        return catalog;
    }

    @Override
    public String defaultState(String type) {
        return LegacyNames.table().defaultState(type);
    }

    @Override
    public String name() {
        return (fawe ? "FastAsyncWorldEdit (WE6 API) " : "WorldEdit ") + plugin.getDescription().getVersion();
    }

    @Override
    public boolean async() {
        return fawe;
    }

    /** Player actor and session, or the console's when the player is offline or not given. */
    private record Owner(Actor actor, com.sk89q.worldedit.entity.Player player, LocalSession session) {
    }

    private Owner owner(UUID id) {
        Player player = id == null ? null : Bukkit.getPlayer(id);
        if (player != null) {
            com.sk89q.worldedit.entity.Player wrapped = plugin.wrapPlayer(player);
            return new Owner(wrapped, wrapped, plugin.getSession(player));
        }
        Actor console = plugin.wrapCommandSender(Bukkit.getConsoleSender());
        return new Owner(console, null, WorldEdit.getInstance().getSessionManager().get(console));
    }

    private static World world(String name) {
        org.bukkit.World world = Bukkit.getWorld(name);
        if (world == null) throw new EditException("unknown world '" + name + "'");
        return BukkitUtil.getLocalWorld(world);
    }

    @Override
    public Optional<SelectionInfo> selection(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return Optional.empty();
        LocalSession session = plugin.getSession(player);
        World world = session.getSelectionWorld();
        if (world == null) return Optional.empty();
        try {
            Region region = session.getSelection(world);
            List<int[]> points = new ArrayList<>();
            if (region instanceof Polygonal2DRegion poly) {
                for (BlockVector2D p : poly.getPoints()) points.add(new int[]{p.getBlockX(), p.getBlockZ()});
            }
            return Optional.of(new SelectionInfo(world.getName(), session.getRegionSelector(world).getTypeName(),
                    Box.of(vec(region.getMinimumPoint()), vec(region.getMaximumPoint())), region.getArea(), points));
        } catch (IncompleteRegionException e) {
            return Optional.empty();
        }
    }

    @Override
    public EditHandle begin(UUID actorId, String worldName) {
        World world = world(worldName);
        Owner owner = owner(actorId);
        EditSession edit = owner.player() != null
                ? WorldEdit.getInstance().getEditSessionFactory().getEditSession(world, -1, owner.player())
                : WorldEdit.getInstance().getEditSessionFactory().getEditSession(world, -1);
        edit.enableQueue();
        return new Handle(world, owner, edit, false);
    }

    @Override
    public PreviewHandle preview(UUID actorId, String worldName, Function<Vec3, String> overlay, long maxBlocks) {
        if (fawe) throw new EditException("drafts need WorldEdit: FastAsyncWorldEdit writes around the world API, so a dry run cannot be guaranteed");
        World world = world(worldName);
        Owner owner = owner(actorId);
        ParserContext ctx = new ParserContext();
        ctx.setActor(owner.actor());
        ctx.setWorld(world);
        ctx.setSession(owner.session());
        ctx.setRestricted(false);
        DryRunStore<BaseBlock> store = new DryRunStore<>(overlay, state -> {
            try {
                return WorldEdit.getInstance().getBlockFactory().parseFromInput(LegacyNames.translate(state, false), ctx);
            } catch (InputParseException | EditException e) {
                throw new EditException("draft holds a block this server does not know: " + state);
            }
        }, b -> LegacyNames.modern(b.getId(), b.getData()), maxBlocks);
        World dry = DryWorld.wrap(world, store);
        EditSession edit = privateSession(dry);
        edit.enableQueue();
        Handle handle = new Handle(world, owner, edit, true);
        return new PreviewHandle() {
            @Override public void prepare(Op op) { handle.prepare(op); }
            @Override public int apply(Op op) { return handle.apply(op); }
            @Override public int paste(Blueprint blueprint, Vec3 origin, int rotation) { return handle.paste(blueprint, origin, rotation); }
            @Override public void flush() { handle.flush(); }
            @Override public void close() { handle.close(); }
            @Override public BlockDiff changes() { return store.diff(); }
        };
    }

    /** An edit session on a private event bus, so other plugins never see a dry run. */
    private static EditSession privateSession(World dry) {
        try {
            Constructor<EditSession> c = EditSession.class.getDeclaredConstructor(EventBus.class, World.class, int.class,
                    BlockBag.class, EditSessionEvent.class);
            c.setAccessible(true);
            return c.newInstance(new EventBus(), dry, -1, null, new EditSessionEvent(dry, null, -1, null));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return WorldEdit.getInstance().getEditSessionFactory().getEditSession(dry, -1);
        }
    }

    @Override
    public int undo(UUID actorId, int steps) {
        Owner owner = owner(actorId);
        if (owner.player() == null) {
            throw new EditException("WorldEdit 6 can only undo for a player; pass \"player\" with an online player's name");
        }
        int done = 0;
        while (done < steps && owner.session().undo(null, owner.player()) != null) {
            done++;
        }
        return done;
    }

    @Override
    public String[] read(String worldName, Box box) {
        World world = world(worldName);
        String[] out = new String[(int) box.volume()];
        int i = 0;
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    BaseBlock block = world.getBlock(new Vector(x, y, z));
                    out[i++] = LegacyNames.modern(block.getId(), block.getData());
                }
            }
        }
        return out;
    }

    @Override
    public Schematic readSchematic(Path file, long maxVolume) {
        ClipboardFormat format = ClipboardFormat.findByFile(file.toFile());
        if (format == null) throw new EditException(file.getFileName() + " is not a schematic format WorldEdit knows");
        Clipboard clipboard;
        try (InputStream in = Files.newInputStream(file)) {
            clipboard = format.getReader(in).read(LegacyWorldData.getInstance());
        } catch (IOException e) {
            throw new EditException("cannot read " + file.getFileName() + ": " + e.getMessage());
        }
        Vector min = clipboard.getRegion().getMinimumPoint(), max = clipboard.getRegion().getMaximumPoint();
        int sx = max.getBlockX() - min.getBlockX() + 1, sy = max.getBlockY() - min.getBlockY() + 1, sz = max.getBlockZ() - min.getBlockZ() + 1;
        long volume = (long) sx * sy * sz;
        if (volume > maxVolume) throw new EditException(file.getFileName() + " has " + volume + " blocks; max per op is " + maxVolume);
        String[] states = new String[(int) volume];
        int i = 0;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    BaseBlock block = clipboard.getBlock(min.add(x, y, z));
                    states[i++] = LegacyNames.modern(block.getId(), block.getData());
                }
            }
        }
        return new Schematic(sx, sy, sz, states);
    }

    /** WorldEdit 6 knows the modern "oak" and "spruce" as "tree" and "redwood", and writes names without separators. */
    private static TreeGenerator.TreeType tree(String name) {
        String key = name.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace(" ", "");
        key = switch (key) {
            case "oak" -> "tree";
            case "largeoak", "bigoak" -> "bigtree";
            case "spruce" -> "redwood";
            case "tallspruce" -> "tallredwood";
            default -> key;
        };
        TreeGenerator.TreeType type = TreeGenerator.TreeType.lookup(key);
        if (type == null) {
            throw new EditException("unknown tree '" + name + "'; use oak, largeoak, birch, tallbirch, spruce, tallspruce, pine, jungle, smalljungle, acacia, darkoak, swamp");
        }
        return type;
    }

    private static Vector vec(Vec3 v) {
        return new Vector(v.x(), v.y(), v.z());
    }

    private static Vec3 vec(Vector v) {
        return new Vec3(v.getBlockX(), v.getBlockY(), v.getBlockZ());
    }

    private static Region region(World world, Box box) {
        return new CuboidRegion(world, vec(box.min()), vec(box.max()));
    }

    private static final class Handle implements EditHandle {

        private final World world;
        private final Owner owner;
        private final EditSession edit;
        /** Built on a {@link DryWorld}: close must not remember the session for undo. */
        private final boolean dryRun;
        private final Map<String, com.sk89q.worldedit.function.pattern.Pattern> patterns = new HashMap<>();
        private final Map<String, BaseBlock> blocks = new HashMap<>();

        Handle(World world, Owner owner, EditSession edit, boolean dryRun) {
            this.world = world;
            this.owner = owner;
            this.edit = edit;
            this.dryRun = dryRun;
        }

        private ParserContext context() {
            ParserContext ctx = new ParserContext();
            ctx.setActor(owner.actor());
            ctx.setWorld(world);
            ctx.setExtent(edit);
            ctx.setSession(owner.session());
            ctx.setRestricted(false);
            ctx.setPreferringWildcard(false);
            return ctx;
        }

        /**
         * WorldEdit 6 parsers (masks in particular) read the edit session from a thread-local Request instead of
         * the ParserContext, so it is set for the duration of every parse.
         */
        private <T> T parsing(Supplier<T> parse) {
            Request request = Request.request();
            request.setWorld(world);
            request.setSession(owner.session());
            request.setEditSession(edit);
            try {
                return parse.get();
            } finally {
                Request.reset();
            }
        }

        private com.sk89q.worldedit.function.pattern.Pattern newPattern(String input) {
            return patterns.computeIfAbsent(input, in -> parsing(() -> {
                try {
                    return WorldEdit.getInstance().getPatternFactory().parseFromInput(LegacyNames.translate(in, false), context());
                } catch (InputParseException e) {
                    throw new EditException("bad pattern '" + in + "': " + e.getMessage());
                }
            }));
        }

        private Pattern pattern(String input) {
            return Patterns.wrap(newPattern(input));
        }

        private Mask mask(String input) {
            return parsing(() -> {
                try {
                    ParserContext ctx = context();
                    ctx.setPreferringWildcard(true);
                    return WorldEdit.getInstance().getMaskFactory().parseFromInput(LegacyNames.translate(input, true), ctx);
                } catch (InputParseException e) {
                    throw new EditException("bad mask '" + input + "': " + e.getMessage());
                }
            });
        }

        private BaseBlock block(String input) {
            return blocks.computeIfAbsent(input, in -> parsing(() -> {
                try {
                    return WorldEdit.getInstance().getBlockFactory().parseFromInput(LegacyNames.translate(in, false), context());
                } catch (InputParseException e) {
                    throw new EditException("bad block '" + in + "': " + e.getMessage());
                }
            }));
        }

        @Override
        public void prepare(Op op) {
            if (op instanceof Op.Tree o) tree(o.type());
            if (op instanceof Op.Blocks) {
                op.patterns().forEach(this::block);
            } else {
                op.patterns().forEach(this::newPattern);
            }
            op.masks().forEach(this::mask);
        }

        @Override
        public int apply(Op op) {
            try {
                if (op instanceof Op.Fill o) return edit.setBlocks(region(world, o.box()), pattern(o.pattern()));
                if (op instanceof Op.Walls o) return edit.makeWalls(region(world, o.box()), pattern(o.pattern()));
                if (op instanceof Op.Outline o) return edit.makeCuboidFaces(region(world, o.box()), pattern(o.pattern()));
                if (op instanceof Op.Replace o) return edit.replaceBlocks(region(world, o.box()), mask(o.from()), pattern(o.pattern()));
                if (op instanceof Op.Cylinder o) return edit.makeCylinder(vec(o.base()), pattern(o.pattern()), o.radiusX(), o.radiusZ(), o.height(), !o.hollow());
                if (op instanceof Op.Sphere o) return edit.makeSphere(vec(o.center()), pattern(o.pattern()), o.radiusX(), o.radiusY(), o.radiusZ(), !o.hollow());
                if (op instanceof Op.Pyramid o) return edit.makePyramid(vec(o.base()), pattern(o.pattern()), o.size(), !o.hollow());
                if (op instanceof Op.Line o) return edit.drawLine(pattern(o.pattern()), vec(o.from()), vec(o.to()), o.thickness(), true);
                if (op instanceof Op.Blocks o) {
                    int changed = 0;
                    for (Op.BlockAt b : o.blocks()) {
                        if (edit.setBlock(vec(b.pos()), block(b.state()))) changed++;
                    }
                    return changed;
                }
                if (op instanceof Op.Smooth o) {
                    // As //smooth without -n.
                    return smooth(new HeightMap(edit, region(world, o.box())), o.iterations());
                }
                if (op instanceof Op.Paste o) return paste(o.blueprint(), o.origin(), o.rotation());
                if (op instanceof Op.Tree o) {
                    // WorldEdit grows a tree anywhere, in mid-air too; a tree stands on something.
                    if (edit.getBlock(vec(o.at().add(0, -1, 0))).isAir()) return 0;
                    int before = edit.getBlockChangeCount();
                    return tree(o.type()).generate(edit, vec(o.at())) ? Math.max(1, edit.getBlockChangeCount() - before) : 0;
                }
                throw new EditException("unsupported op " + op.getClass().getSimpleName());
            } catch (MaxChangedBlocksException e) {
                throw new EditException("WorldEdit block limit reached: " + e.getMessage());
            }
        }

        /**
         * The Gaussian kernel is built reflectively: some WorldEdit 6 builds do not derive it from
         * java.awt.image.Kernel, and a direct constructor call then fails the verification of this whole
         * class, so that no op could run at all.
         */
        private int smooth(HeightMap map, int iterations) throws MaxChangedBlocksException {
            try {
                Object kernel = Class.forName("com.sk89q.worldedit.math.convolution.GaussianKernel", true,
                        HeightMap.class.getClassLoader()).getConstructor(int.class, double.class).newInstance(5, 1.0);
                for (Constructor<?> c : HeightMapFilter.class.getConstructors()) {
                    if (c.getParameterCount() == 1 && c.getParameterTypes()[0].isInstance(kernel)) {
                        return map.applyFilter((HeightMapFilter) c.newInstance(kernel), iterations);
                    }
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                throw new EditException("smooth is not supported by this WorldEdit: " + e);
            }
            throw new EditException("smooth is not supported by this WorldEdit");
        }

        @Override
        public int paste(Blueprint bp, Vec3 origin, int rotation) {
            AffineTransform transform = new AffineTransform().rotateY(-rotation);
            BlockRegistry registry = world.getWorldData().getBlockRegistry();
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            for (int[] corner : new int[][]{{0, 0}, {bp.sizeX() - 1, 0}, {0, bp.sizeZ() - 1}, {bp.sizeX() - 1, bp.sizeZ() - 1}}) {
                Vector r = transform.apply(new Vector(corner[0], 0, corner[1]));
                minX = Math.min(minX, (int) Math.round(r.getX()));
                minZ = Math.min(minZ, (int) Math.round(r.getZ()));
            }
            int offX = origin.x() - minX, offZ = origin.z() - minZ;
            int[] changed = {0};
            bp.forEachCell((x, y, z, input) -> {
                Vector r = transform.apply(new Vector(x, 0, z));
                Vector pos = new Vector((int) Math.round(r.getX()) + offX, origin.y() + y, (int) Math.round(r.getZ()) + offZ);
                // transform() changes the block it is given, and the pattern hands out the same instance for every cell
                BaseBlock block = BlockTransformExtent.transform(new BaseBlock(newPattern(input).apply(pos)), transform, registry);
                try {
                    if (edit.setBlock(pos, block)) changed[0]++;
                } catch (MaxChangedBlocksException e) {
                    throw new EditException("WorldEdit block limit reached: " + e.getMessage());
                }
            });
            return changed[0];
        }

        @Override
        public void flush() {
            edit.flushQueue();
        }

        @Override
        public void close() {
            edit.flushQueue();
            if (!dryRun) owner.session().remember(edit);
        }
    }
}
