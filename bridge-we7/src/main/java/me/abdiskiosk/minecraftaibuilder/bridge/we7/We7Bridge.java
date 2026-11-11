package me.abdiskiosk.minecraftaibuilder.bridge.we7;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.EditSessionBuilder;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extension.input.InputParseException;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.transform.BlockTransformExtent;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.math.convolution.GaussianKernel;
import com.sk89q.worldedit.math.convolution.HeightMap;
import com.sk89q.worldedit.math.convolution.HeightMapFilter;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Polygonal2DRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.util.eventbus.EventBus;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import me.abdiskiosk.minecraftaibuilder.common.BlockCatalog;
import me.abdiskiosk.minecraftaibuilder.common.BlockDiff;
import me.abdiskiosk.minecraftaibuilder.common.Blueprint;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.DryRunStore;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** WorldEdit 7.2+ (Minecraft 1.13 and newer), including FastAsyncWorldEdit. Loaded reflectively by the plugin. */
public final class We7Bridge implements WorldEditBridge {

    private final boolean fawe;
    private final BlockCatalog catalog = new We7BlockCatalog("Minecraft " + Bukkit.getBukkitVersion().split("-")[0]);

    public We7Bridge(boolean fawe) {
        this.fawe = fawe;
    }

    @Override
    public BlockCatalog catalog() {
        return catalog;
    }

    @Override
    public String name() {
        return (fawe ? "FastAsyncWorldEdit " : "WorldEdit ") + WorldEdit.getVersion();
    }

    @Override
    public boolean async() {
        return fawe;
    }

    private static Actor actor(UUID id) {
        Player player = id == null ? null : Bukkit.getPlayer(id);
        return player != null ? BukkitAdapter.adapt(player) : BukkitAdapter.adapt(Bukkit.getConsoleSender());
    }

    private static LocalSession session(Actor actor) {
        return WorldEdit.getInstance().getSessionManager().get(actor);
    }

    private static World world(String name) {
        org.bukkit.World world = Bukkit.getWorld(name);
        if (world == null) throw new EditException("unknown world '" + name + "'");
        return BukkitAdapter.adapt(world);
    }

    @Override
    public Optional<SelectionInfo> selection(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return Optional.empty();
        LocalSession session = session(BukkitAdapter.adapt(player));
        World world = session.getSelectionWorld();
        if (world == null) return Optional.empty();
        try {
            Region region = session.getSelection(world);
            List<int[]> points = new ArrayList<>();
            if (region instanceof Polygonal2DRegion poly) {
                for (BlockVector2 p : poly.getPoints()) points.add(new int[]{p.getBlockX(), p.getBlockZ()});
            }
            String type = session.getRegionSelector(world).getTypeName();
            return Optional.of(new SelectionInfo(world.getName(), type,
                    Box.of(vec(region.getMinimumPoint()), vec(region.getMaximumPoint())), region.getVolume(), points));
        } catch (IncompleteRegionException e) {
            return Optional.empty();
        }
    }

    @Override
    public EditHandle begin(UUID actorId, String worldName) {
        World world = world(worldName);
        Actor actor = actor(actorId);
        LocalSession session = session(actor);
        EditSession edit = WorldEdit.getInstance().newEditSessionBuilder()
                .world(world).actor(actor).maxBlocks(-1).build();
        return new Handle(world, actor, session, edit, false);
    }

    @Override
    public PreviewHandle preview(UUID actorId, String worldName, Function<Vec3, String> overlay, long maxBlocks) {
        if (fawe) throw new EditException("drafts need WorldEdit: FastAsyncWorldEdit writes around the world API, so a dry run cannot be guaranteed");
        World world = world(worldName);
        Actor actor = actor(actorId);
        LocalSession session = session(actor);
        ParserContext ctx = new ParserContext();
        ctx.setActor(actor);
        ctx.setWorld(world);
        ctx.setRestricted(false);
        DryRunStore<BlockState> store = new DryRunStore<>(overlay, state -> {
            try {
                return WorldEdit.getInstance().getBlockFactory().parseFromInput(state, ctx).toImmutableState();
            } catch (InputParseException e) {
                throw new EditException("draft holds a block this server does not know: " + state);
            }
        }, We7Bridge::compact, maxBlocks);
        EditSession edit = privateBuilder().world(DryWorld.wrap(world, store)).actor(actor).maxBlocks(-1).build();
        Handle handle = new Handle(world, actor, session, edit, true);
        return new PreviewHandle() {
            @Override public void prepare(Op op) { handle.prepare(op); }
            @Override public int apply(Op op) { return handle.apply(op); }
            @Override public int paste(Blueprint blueprint, Vec3 origin, int rotation) { return handle.paste(blueprint, origin, rotation); }
            @Override public void flush() { handle.flush(); }
            @Override public void close() { handle.close(); }
            @Override public BlockDiff changes() { return store.diff(); }
        };
    }

    /**
     * An edit session builder on a private event bus, so other plugins (loggers like CoreProtect, region guards)
     * never see a dry run. Falls back to WorldEdit's own bus if the constructor is not reachable.
     */
    private static EditSessionBuilder privateBuilder() {
        try {
            Constructor<EditSessionBuilder> c = EditSessionBuilder.class.getDeclaredConstructor(EventBus.class);
            c.setAccessible(true);
            return c.newInstance(new EventBus());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return WorldEdit.getInstance().newEditSessionBuilder();
        }
    }

    @Override
    public int undo(UUID actorId, int steps) {
        Actor actor = actor(actorId);
        LocalSession session = session(actor);
        int done = 0;
        while (done < steps && session.undo(null, actor) != null) {
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
                    out[i++] = compact(world.getBlock(BlockVector3.at(x, y, z)));
                }
            }
        }
        return out;
    }

    @Override
    public Schematic readSchematic(Path file, long maxVolume) {
        ClipboardFormat format = ClipboardFormats.findByFile(file.toFile());
        if (format == null) throw new EditException(file.getFileName() + " is not a schematic format WorldEdit knows");
        Clipboard clipboard;
        try (InputStream in = Files.newInputStream(file); ClipboardReader reader = format.getReader(in)) {
            clipboard = reader.read();
        } catch (IOException e) {
            throw new EditException("cannot read " + file.getFileName() + ": " + e.getMessage());
        }
        BlockVector3 min = clipboard.getRegion().getMinimumPoint(), max = clipboard.getRegion().getMaximumPoint();
        int sx = max.getBlockX() - min.getBlockX() + 1, sy = max.getBlockY() - min.getBlockY() + 1, sz = max.getBlockZ() - min.getBlockZ() + 1;
        long volume = (long) sx * sy * sz;
        if (volume > maxVolume) throw new EditException(file.getFileName() + " has " + volume + " blocks; max per op is " + maxVolume);
        String[] states = new String[(int) volume];
        int i = 0;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    states[i++] = compact(clipboard.getBlock(min.add(x, y, z)));
                }
            }
        }
        return new Schematic(sx, sy, sz, states);
    }

    /** {@code oak_stairs[facing=east]}: namespace and properties that equal the block's default are left out. */
    private static String compact(BlockState state) {
        String id = state.getBlockType().getId();
        StringBuilder sb = new StringBuilder(id.startsWith("minecraft:") ? id.substring(10) : id);
        Map<Property<?>, Object> defaults = state.getBlockType().getDefaultState().getStates();
        boolean first = true;
        for (Map.Entry<Property<?>, Object> e : state.getStates().entrySet()) {
            if (Objects.equals(e.getValue(), defaults.get(e.getKey()))) continue;
            sb.append(first ? '[' : ',').append(e.getKey().getName()).append('=').append(String.valueOf(e.getValue()).toLowerCase(Locale.ROOT));
            first = false;
        }
        return first ? sb.toString() : sb.append(']').toString();
    }

    private static BlockVector3 vec(Vec3 v) {
        return BlockVector3.at(v.x(), v.y(), v.z());
    }

    private static Vec3 vec(BlockVector3 v) {
        return new Vec3(v.getBlockX(), v.getBlockY(), v.getBlockZ());
    }

    private static Region region(World world, Box box) {
        return new CuboidRegion(world, vec(box.min()), vec(box.max()));
    }

    private static final class Handle implements EditHandle {

        private final World world;
        private final Actor actor;
        private final LocalSession session;
        private final EditSession edit;
        /** Built on a {@link DryWorld}: close must not remember the session for undo. */
        private final boolean dryRun;
        private final Map<String, Pattern> patterns = new HashMap<>();
        private final Map<String, BaseBlock> blocks = new HashMap<>();

        Handle(World world, Actor actor, LocalSession session, EditSession edit, boolean dryRun) {
            this.world = world;
            this.actor = actor;
            this.session = session;
            this.edit = edit;
            this.dryRun = dryRun;
        }

        private ParserContext context() {
            ParserContext ctx = new ParserContext();
            ctx.setActor(actor);
            ctx.setWorld(world);
            ctx.setExtent(edit);
            ctx.setSession(session);
            ctx.setRestricted(false);
            ctx.setPreferringWildcard(false);
            return ctx;
        }

        private Pattern pattern(String input) {
            return patterns.computeIfAbsent(input, in -> {
                try {
                    return WorldEdit.getInstance().getPatternFactory().parseFromInput(in, context());
                } catch (InputParseException e) {
                    throw new EditException("bad pattern '" + in + "': " + e.getMessage());
                }
            });
        }

        private Mask mask(String input) {
            try {
                return WorldEdit.getInstance().getMaskFactory().parseFromInput(input, context());
            } catch (InputParseException e) {
                throw new EditException("bad mask '" + input + "': " + e.getMessage());
            }
        }

        private BaseBlock block(String input) {
            return blocks.computeIfAbsent(input, in -> {
                try {
                    return WorldEdit.getInstance().getBlockFactory().parseFromInput(in, context());
                } catch (InputParseException e) {
                    throw new EditException("bad block '" + in + "': " + e.getMessage());
                }
            });
        }

        @Override
        public void prepare(Op op) {
            if (op instanceof Op.Blocks) {
                op.patterns().forEach(this::block);
            } else {
                op.patterns().forEach(this::pattern);
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
                    // As //smooth without a mask: the heightmap follows blocks that stop movement.
                    HeightMap map = new HeightMap(edit, region(world, o.box()), (Mask) null);
                    return map.applyFilter(new HeightMapFilter(new GaussianKernel(5, 1.0)), o.iterations());
                }
                if (op instanceof Op.Paste o) return paste(o.blueprint(), o.origin(), o.rotation());
                throw new EditException("unsupported op " + op.getClass().getSimpleName());
            } catch (MaxChangedBlocksException e) {
                throw new EditException("WorldEdit block limit reached: " + e.getMessage());
            }
        }

        @Override
        public int paste(Blueprint bp, Vec3 origin, int rotation) {
            // Same convention as //rotate: rotateY(-degrees). Positions and block states use the same transform.
            AffineTransform transform = new AffineTransform().rotateY(-rotation);
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            for (int[] corner : new int[][]{{0, 0}, {bp.sizeX() - 1, 0}, {0, bp.sizeZ() - 1}, {bp.sizeX() - 1, bp.sizeZ() - 1}}) {
                Vector3 r = transform.apply(Vector3.at(corner[0], 0, corner[1]));
                minX = Math.min(minX, (int) Math.round(r.getX()));
                minZ = Math.min(minZ, (int) Math.round(r.getZ()));
            }
            int offX = origin.x() - minX, offZ = origin.z() - minZ;
            int[] changed = {0};
            bp.forEachCell((x, y, z, input) -> {
                Vector3 r = transform.apply(Vector3.at(x, 0, z));
                BlockVector3 pos = BlockVector3.at((int) Math.round(r.getX()) + offX, origin.y() + y, (int) Math.round(r.getZ()) + offZ);
                BaseBlock block = BlockTransformExtent.transform(pattern(input).applyBlock(pos), transform);
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
            edit.flushSession();
        }

        @Override
        public void close() {
            edit.close();
            if (!dryRun) session.remember(edit);
        }
    }
}
