package me.abdiskiosk.minecraftaibuilder.plugin.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import me.abdiskiosk.minecraftaibuilder.common.BlockCatalog;
import me.abdiskiosk.minecraftaibuilder.common.BlockInfo;
import me.abdiskiosk.minecraftaibuilder.common.BlockState;
import me.abdiskiosk.minecraftaibuilder.common.Blueprint;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.IsoRenderer;
import me.abdiskiosk.minecraftaibuilder.common.Op;
import me.abdiskiosk.minecraftaibuilder.common.RegionCodec;
import me.abdiskiosk.minecraftaibuilder.common.RegionLint;
import me.abdiskiosk.minecraftaibuilder.common.Schematic;
import me.abdiskiosk.minecraftaibuilder.common.SelectionInfo;
import me.abdiskiosk.minecraftaibuilder.common.Terrain;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import me.abdiskiosk.minecraftaibuilder.plugin.BuildService;
import me.abdiskiosk.minecraftaibuilder.plugin.Decorator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * All tool definitions. The listed tools and their schemas are sent to Claude every session and results stay in
 * its context, so descriptions, schemas and results are kept as short as they can be; what applies to every tool
 * (coordinates, regions, player/world) is explained once in the MCP instructions. McpEndpointTest holds the budget.
 */
public final class Tools {

    private static final String VEC = """
            {"type":"array","description":"[x,y,z]; \\"~n\\" = relative to the player"}""";
    private static final String REGION = """
            {"description":"\\"selection\\" or [x1,y1,z1,x2,y2,z2]"}""";
    private static final String PATTERN = """
            {"type":"string","description":"e.g. 70%stone_bricks,30%andesite"}""";
    private static final String COMMON = """
            "player":{"type":"string"},"world":{"type":"string"}""";
    private static final long MAX_BLUEPRINT_BYTES = 5L * 1024 * 1024;
    private static final Pattern VAR = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern SAVED_NAME = Pattern.compile("[a-z0-9_-]{1,40}");

    private Tools() {
    }

    public static void registerAll(ToolRegistry registry, BuildService service) {
        registry.register(new Tool("get_context",
                "Players (position, facing, looked-at block), the caller's WorldEdit selection and the server version. Call first.",
                schema(""), a -> context(a, service)));

        registry.register(new Tool("block_info",
                "Check blocks on THIS server: name gives valid?, properties and values (facing, half, ...), closest names if unknown; search=words lists matching blocks.",
                schema("\"name\":{\"type\":\"string\"},\"search\":{\"type\":\"string\"}"),
                a -> blockInfo(a, service)));

        // Covered by get_context and read_region "selection"; kept for scripts.
        registry.register(new Tool("get_selection",
                "The player's WorldEdit selection (type, min, max, size, volume). summary=true adds block counts inside.",
                schema("\"summary\":{\"type\":\"boolean\"}"), a -> selection(a, service)).unlisted());

        registry.register(new Tool("read_region",
                "Inspect blocks. mode=summary (default): counts, non-air bounds, surface heights. mode=full: palette+layers in place_blueprint format (costly; small regions only).",
                schema("\"region\":" + REGION + ",\"mode\":{\"type\":\"string\",\"enum\":[\"summary\",\"full\"]},\"heightmap\":{\"type\":\"boolean\"}", "region"),
                a -> read(a, service)));

        registry.register(new Tool("check_region",
                "Finds build mistakes: " + String.join(", ", RegionLint.KINDS) + ". checks=comma list (default all).",
                schema("\"region\":" + REGION + ",\"checks\":{\"type\":\"string\"}", "region"),
                a -> check(a, service)));

        registry.register(new Tool("render_region",
                "Region PNG cropped to its blocks. view: both (default) = top map (north up) + iso; north/south/east/west = that face seen from outside. corner = where the iso camera stands. size: small for checks, medium (default), large for final review.",
                schema("\"region\":" + REGION + ",\"view\":{\"type\":\"string\",\"enum\":[\"both\",\"top\",\"iso\",\"north\",\"south\",\"east\",\"west\"]},\"corner\":{\"type\":\"string\",\"enum\":[\"se\",\"sw\",\"ne\",\"nw\"]},\"size\":{\"type\":\"string\",\"enum\":[\"small\",\"medium\",\"large\"]}", "region"),
                a -> render(a, service)));

        registry.register(new Tool("fill",
                "Fill a box with a pattern. mode: solid (default), walls (the four vertical sides) or outline (all six faces). from=mask (e.g. stone,dirt) replaces only matching blocks.",
                schema("\"region\":" + REGION + ",\"pattern\":" + PATTERN + ",\"mode\":{\"type\":\"string\",\"enum\":[\"solid\",\"walls\",\"outline\"]},\"from\":{\"type\":\"string\"}", "region", "pattern"),
                a -> edit(a, service, parseOp("fill", a))));

        // Older names for the fill modes: still valid as batch ops and over REST.
        for (String type : List.of("walls", "outline")) {
            registry.register(new Tool(type, (type.equals("walls") ? "Four vertical sides of a box" : "All six faces of a box") + " with a pattern.",
                    schema("\"region\":" + REGION + ",\"pattern\":" + PATTERN, "region", "pattern"),
                    a -> edit(a, service, parseOp(type, a))).unlisted());
        }
        registry.register(new Tool("replace", "Replace blocks matching the 'from' mask (e.g. stone,dirt) inside a box.",
                schema("\"region\":" + REGION + ",\"from\":{\"type\":\"string\"},\"pattern\":" + PATTERN, "region", "from", "pattern"),
                a -> edit(a, service, parseOp("replace", a))).unlisted());
        // Meant as a batch op; standalone for REST scripts.
        registry.register(new Tool("clone", "Copy a region to 'to', optionally rotated, flipped and repeated.",
                schema("\"region\":" + REGION + ",\"to\":" + VEC, "region", "to"),
                a -> edit(a, service, parseOp("clone", a))).unlisted());
        registry.register(new Tool("foundation", "Fill from the ground up to the region's top.",
                schema("\"region\":" + REGION + ",\"pattern\":" + PATTERN, "region", "pattern"),
                a -> edit(a, service, parseOp("foundation", a))).unlisted());
        registry.register(new Tool("overlay", "Replace the top 'depth' ground blocks, or add 'raise' blocks on top.",
                schema("\"region\":" + REGION + ",\"pattern\":" + PATTERN + ",\"depth\":{\"type\":\"integer\"},\"raise\":{\"type\":\"integer\"}", "region", "pattern"),
                a -> edit(a, service, parseOp("overlay", a))).unlisted());
        registry.register(new Tool("smooth", "Smooth the terrain like //smooth.",
                schema("\"region\":" + REGION + ",\"iterations\":{\"type\":\"integer\"}", "region"),
                a -> edit(a, service, parseOp("smooth", a))).unlisted());

        registry.register(new Tool("cylinder", "Vertical cylinder standing on 'base', growing up 'height' blocks.",
                schema("\"base\":" + VEC + ",\"radius\":{\"type\":\"number\"},\"radius_z\":{\"type\":\"number\"},\"height\":{\"type\":\"integer\"},\"pattern\":" + PATTERN + ",\"hollow\":{\"type\":\"boolean\"}",
                        "base", "radius", "height", "pattern"),
                a -> edit(a, service, parseOp("cylinder", a))));

        registry.register(new Tool("sphere", "Sphere or ellipsoid around 'center'.",
                schema("\"center\":" + VEC + ",\"radius\":{\"type\":\"number\"},\"radius_y\":{\"type\":\"number\"},\"radius_z\":{\"type\":\"number\"},\"pattern\":" + PATTERN + ",\"hollow\":{\"type\":\"boolean\"}",
                        "center", "radius", "pattern"),
                a -> edit(a, service, parseOp("sphere", a))));

        registry.register(new Tool("line", "Straight line between two points; thickness is a radius.",
                schema("\"from\":" + VEC + ",\"to\":" + VEC + ",\"pattern\":" + PATTERN + ",\"thickness\":{\"type\":\"number\"}", "from", "to", "pattern"),
                a -> edit(a, service, parseOp("line", a))));

        registry.register(new Tool("set_blocks",
                "Place individual blocks: {\"state\":[[x,y,z],...]} or [[x,y,z,\"state\"],...]. With 'origin', x/y/z are offsets from it. For details only; use shapes or scripts for bulk.",
                schema("\"origin\":" + VEC + ",\"blocks\":{\"type\":[\"object\",\"array\"]}", "blocks"),
                a -> edit(a, service, parseOp("blocks", a))));

        registry.register(new Tool("batch",
                "Run several ops as ONE undo step: ops=[{\"op\":\"fill|cylinder|sphere|pyramid|line|blocks|paste|clone\", ...the matching tool's args}]; blocks=set_blocks, paste=place_blueprint, pyramid takes base,size,pattern,hollow, clone copies a built region: region,to,rotation,flip=x|z,repeat,step=[dx,dy,dz],air. Terrain ops see the ground as before the batch: foundation (region,pattern) fills up to region top; overlay (region,pattern,depth=1|raise) covers it; smooth (region,iterations=2). origin=[x,y,z] makes every coordinate in ops an offset from it. vars={\"W\":\"70%stone_bricks,30%andesite\"} lets patterns and block states say $W.",
                schema("\"ops\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"origin\":" + VEC + ",\"vars\":{\"type\":\"object\"}", "ops"),
                a -> edit(a, service, batchOps(a))));

        registry.register(new Tool("place_blueprint",
                "Paste a structure. blueprint={palette:{\"S\":\"stone_bricks\",\"_\":\"air\"},layers:[[row z0,row z1,..] per y]}; row chars run along +x, \"4S\" repeats a block, '.' keeps what is there, a row \"^\" or \"3^\" repeats the row before it, a layer [\"^\"] the layer below. Or path=absolute .json/.schem file, or name=saved. origin=min corner, rotation=0/90/180/270.",
                schema("\"blueprint\":{\"type\":\"object\"},\"path\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"origin\":" + VEC + ",\"rotation\":{\"type\":\"integer\"}", "origin"),
                a -> edit(a, service, parseOp("paste", a))));

        registry.register(new Tool("save_blueprint", "Save a region for place_blueprint name=.",
                schema("\"name\":{\"type\":\"string\"},\"region\":" + REGION, "name", "region"),
                a -> saveBlueprint(a, service)));

        registry.register(new Tool("decorate",
                "Sign text, chest items, item frames, armor stands; no undo. items:[{at,text:[lines]|contents:{item:n}|frame:item,facing|stand:{yaw,head,chest,legs,feet,hand,arms,small,base}}]; remove=region clears frames/stands first.",
                schema("\"items\":{\"type\":\"array\"},\"origin\":" + VEC + ",\"remove\":" + REGION),
                a -> decorate(a, service)));

        registry.register(new Tool("undo", "Undo the caller's last edits (same history as //undo).",
                schema("\"steps\":{\"type\":\"integer\"}"),
                a -> {
                    a.only("undo", "steps");
                    int steps = a.integer("steps", 1);
                    if (steps < 1 || steps > 50) throw new EditException("steps must be between 1 and 50");
                    JsonObject o = new JsonObject();
                    o.addProperty("undone", service.undo(a.caller(), steps));
                    if (!soleOnlinePlayer(a.caller())) o.addProperty("as", a.caller().name());
                    return ToolResult.of(o);
                }));

        registry.register(new Tool("job_status", "Status of an edit that answered \"running\".",
                schema("\"job\":{\"type\":\"string\"}", "job"),
                a -> service.job(a.str("job")).map(r -> jobJson(r, false))
                        .orElseThrow(() -> new EditException("unknown job " + a.str("job")))));
    }

    // ---- handlers ----

    private static ToolResult context(Args a, BuildService service) {
        BuildService.Caller caller = a.caller();
        JsonObject out = new JsonObject();
        out.addProperty("bridge", service.bridge().name());
        JsonObject callerJson = new JsonObject();
        callerJson.addProperty("name", caller.name());
        callerJson.addProperty("world", caller.world());
        if (caller.feet() != null) {
            callerJson.add("pos", vec(caller.feet()));
            callerJson.addProperty("facing", facing(caller.yaw()));
        }
        if (!caller.isPlayer() && caller.online().size() > 1) {
            callerJson.addProperty("note", caller.online().size() + " players are online (" + String.join(", ", caller.online())
                    + "), so calls act as the console. Pass \"player\" for ~ coordinates, selections and undo.");
        }
        out.add("caller", callerJson);
        BlockCatalog catalog = service.bridge().catalog();
        JsonObject server = new JsonObject();
        server.addProperty("minecraft", BuildService.minecraftVersion());
        server.addProperty("legacy_blocks", catalog.legacy());
        JsonObject height = new JsonObject();
        height.addProperty("min", caller.minY());
        height.addProperty("max", caller.maxY());
        server.add("height", height);
        if (catalog.legacy()) {
            server.addProperty("note", "Block set is 1.8-1.12 (no lanterns, concrete, deepslate, new woods). Blocks that do not exist are rejected; check with block_info.");
        }
        out.add("server", server);
        // The caller is described once, in "caller"; "players" lists everyone else.
        JsonArray others = service.sync(() -> {
            JsonArray players = new JsonArray();
            for (Player p : Bukkit.getOnlinePlayers()) {
                Block target = p.getTargetBlock((Set<Material>) null, 64);
                JsonArray lookingAt = target != null && target.getType() != Material.AIR
                        ? vec(new Vec3(target.getX(), target.getY(), target.getZ())) : null;
                if (p.getUniqueId().equals(caller.id())) {
                    if (lookingAt != null) callerJson.add("looking_at", lookingAt);
                    continue;
                }
                Location l = p.getLocation();
                JsonObject pj = new JsonObject();
                pj.addProperty("name", p.getName());
                pj.addProperty("world", l.getWorld().getName());
                pj.add("pos", vec(new Vec3(l.getBlockX(), l.getBlockY(), l.getBlockZ())));
                pj.addProperty("facing", facing(l.getYaw()));
                if (lookingAt != null) pj.add("looking_at", lookingAt);
                players.add(pj);
            }
            return players;
        });
        if (!others.isEmpty()) out.add("players", others);
        if (caller.isPlayer()) {
            service.selection(caller).ifPresent(sel -> out.add("selection", selectionJson(sel)));
        }
        List<String> worldNames = service.worlds();
        if (worldNames.size() > 1) {
            JsonArray worlds = new JsonArray();
            worldNames.forEach(worlds::add);
            out.add("worlds", worlds);
        }
        List<String> saved = savedBlueprints(service);
        if (!saved.isEmpty()) {
            JsonArray blueprints = new JsonArray();
            saved.stream().limit(50).forEach(blueprints::add);
            out.add("blueprints", blueprints);
        }
        return ToolResult.of(out);
    }

    private static ToolResult selection(Args a, BuildService service) {
        Optional<SelectionInfo> sel = service.selection(a.caller());
        if (sel.isEmpty()) throw new EditException(a.caller().name() + " has no complete WorldEdit selection");
        JsonObject out = selectionJson(sel.get());
        if (a.bool("summary", false)) {
            Box box = sel.get().bounds();
            if (box.volume() <= service.config().maxReadVolume()) {
                out.add("summary", summaryJson(RegionCodec.summarize(box, service.read(sel.get().world(), box), 12, false)));
            } else {
                out.addProperty("summary", "selection too large to summarise; use read_region on part of it");
            }
        }
        return ToolResult.of(out);
    }

    private static ToolResult read(Args a, BuildService service) {
        a.only("read_region", "region", "mode", "heightmap");
        Box box = a.region("region");
        String mode = a.str("mode", "summary");
        if (!mode.equals("summary") && !mode.equals("full")) throw new EditException("mode must be summary or full");
        String[] states = service.read(a.caller().world(), box);
        if (mode.equals("full")) {
            RegionCodec.Encoded enc = RegionCodec.encode(box, states);
            JsonObject out = new JsonObject();
            out.add("origin", vec(box.min()));
            JsonObject palette = new JsonObject();
            enc.palette().forEach((k, v) -> palette.addProperty(String.valueOf(k), v));
            out.add("palette", palette);
            JsonArray layers = new JsonArray();
            for (List<String> layer : enc.layers()) {
                JsonArray rows = new JsonArray();
                layer.forEach(rows::add);
                layers.add(rows);
            }
            out.add("layers", layers);
            return ToolResult.of(out);
        }
        return ToolResult.of(summaryJson(RegionCodec.summarize(box, states, 15, a.bool("heightmap", false))));
    }

    private static ToolResult check(Args a, BuildService service) {
        a.only("check_region", "region", "checks");
        Box box = a.region("region");
        Set<String> kinds = null;
        if (a.has("checks")) {
            kinds = new LinkedHashSet<>();
            for (String kind : a.str("checks").split(",")) {
                String k = kind.trim();
                if (k.isEmpty()) continue;
                if (!RegionLint.KINDS.contains(k)) {
                    throw new EditException("unknown check '" + k + "'; use " + String.join(", ", RegionLint.KINDS));
                }
                kinds.add(k);
            }
            if (kinds.isEmpty()) kinds = null;
        }
        String[] states = service.read(a.caller().world(), box);
        JsonArray issues = new JsonArray();
        for (RegionLint.Issue issue : RegionLint.check(box, states, kinds, service.bridge().catalog().legacy())) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", issue.kind());
            o.addProperty("count", issue.count());
            JsonArray at = new JsonArray();
            issue.at().forEach(v -> at.add(vec(v)));
            o.add("at", at);
            o.addProperty("hint", issue.hint());
            issues.add(o);
        }
        JsonObject out = new JsonObject();
        out.add("issues", issues);
        return ToolResult.of(out);
    }

    private static ToolResult render(Args a, BuildService service) {
        a.only("render_region", "region", "view", "corner", "size");
        Box box = a.region("region");
        IsoRenderer.View view = renderView(a.str("view", "both"));
        IsoRenderer.Corner corner = renderCorner(a.str("corner", null), view);
        IsoRenderer.Size size = switch (a.str("size", "medium")) {
            case "small" -> IsoRenderer.Size.SMALL;
            case "medium" -> IsoRenderer.Size.MEDIUM;
            case "large" -> IsoRenderer.Size.LARGE;
            default -> throw new EditException("size must be small, medium or large");
        };
        IsoRenderer.Rendered rendered = IsoRenderer.render(box, service.read(a.caller().world(), box), view, corner, size);
        JsonObject meta = new JsonObject();
        meta.add("box", region(rendered.box())); // what the image shows: the region cropped to its blocks
        return ToolResult.image(rendered.png(), meta);
    }

    static IsoRenderer.View renderView(String view) {
        return switch (view) {
            case "both" -> IsoRenderer.View.BOTH;
            case "top" -> IsoRenderer.View.TOP;
            case "iso" -> IsoRenderer.View.ISO;
            case "north" -> IsoRenderer.View.NORTH;
            case "south" -> IsoRenderer.View.SOUTH;
            case "east" -> IsoRenderer.View.EAST;
            case "west" -> IsoRenderer.View.WEST;
            default -> throw new EditException("view must be both, top, iso, north, south, east or west");
        };
    }

    /** @param corner null when not given */
    static IsoRenderer.Corner renderCorner(String corner, IsoRenderer.View view) {
        if (corner == null) return IsoRenderer.Corner.SE;
        if (view != IsoRenderer.View.ISO && view != IsoRenderer.View.BOTH) {
            throw new EditException("corner only applies to view iso or both; leave it out for view " + view.name().toLowerCase(java.util.Locale.ROOT));
        }
        return switch (corner) {
            case "se" -> IsoRenderer.Corner.SE;
            case "sw" -> IsoRenderer.Corner.SW;
            case "ne" -> IsoRenderer.Corner.NE;
            case "nw" -> IsoRenderer.Corner.NW;
            default -> throw new EditException("corner must be se, sw, ne or nw");
        };
    }

    private static ToolResult edit(Args a, BuildService service, List<Op> ops) {
        if (ops.isEmpty()) {
            // Only foundation or overlay can leave nothing to do, when the ground already is as asked.
            JsonObject o = new JsonObject();
            o.addProperty("changed", 0);
            o.addProperty("note", "nothing to change: the terrain already fits");
            return ToolResult.of(o);
        }
        BuildService.JobResult result = service.await(service.submit(a.caller(), ops));
        ToolResult out = jobJson(result, true);
        if (!soleOnlinePlayer(a.caller())) out.data().addProperty("as", a.caller().name());
        return out;
    }

    private static ToolResult decorate(Args a, BuildService service) {
        a.only("decorate", "items", "origin", "remove");
        if (!a.has("items") && !a.has("remove")) throw new EditException("give \"items\", \"remove\" or both");
        List<DecorSpec.Item> items = a.has("items") ? DecorSpec.parse(a) : List.of();
        Decorator.Result r = Decorator.apply(service, a.caller(), items, a.has("remove") ? a.region("remove") : null);
        JsonObject o = new JsonObject();
        if (r.set() > 0) o.addProperty("set", r.set());
        if (r.spawned() > 0) o.addProperty("spawned", r.spawned());
        if (r.removed() > 0) o.addProperty("removed", r.removed());
        return ToolResult.of(o);
    }

    /** Nobody needs to be told who acted when there is only one player it could have been. */
    private static boolean soleOnlinePlayer(BuildService.Caller caller) {
        return caller.isPlayer() && caller.online().size() == 1;
    }

    /**
     * @param brief for edit results: a finished edit is just {@code {"changed":n}}, and the job id only appears
     *              while the job is still running (when it is needed for job_status)
     */
    private static ToolResult jobJson(BuildService.JobResult r, boolean brief) {
        JsonObject o = new JsonObject();
        boolean done = "done".equals(r.status());
        if (!brief || "running".equals(r.status())) o.addProperty("job", r.job());
        if (!brief || !done) o.addProperty("status", r.status());
        o.addProperty("changed", r.changed());
        if (!brief) o.addProperty("ms", r.ms());
        if (r.error() != null) o.addProperty("error", r.error());
        if (!r.warnings().isEmpty()) {
            JsonArray warnings = new JsonArray();
            r.warnings().forEach(warnings::add);
            o.add("warnings", warnings);
        }
        return new ToolResult(o, null, "failed".equals(r.status()));
    }

    private static ToolResult blockInfo(Args a, BuildService service) {
        a.only("block_info", "name", "search");
        BlockCatalog catalog = service.bridge().catalog();
        JsonObject out = new JsonObject();
        if (a.has("search")) {
            JsonArray matches = new JsonArray();
            catalog.search(a.str("search"), 40).forEach(matches::add);
            out.add("matches", matches);
            return ToolResult.of(out);
        }
        BlockState state = BlockState.parse(a.str("name"));
        out.addProperty("name", state.type());
        Optional<BlockInfo> info = catalog.info(state.type());
        out.addProperty("valid", info.isPresent());
        if (info.isEmpty()) {
            JsonArray closest = new JsonArray();
            catalog.suggest(state.type(), 6).forEach(closest::add);
            out.add("closest", closest);
            out.addProperty("server", catalog.describe());
            return ToolResult.of(out);
        }
        BlockInfo b = info.get();
        if (!b.type().equals(state.type())) out.addProperty("name", b.type());
        JsonObject properties = new JsonObject();
        b.properties().forEach((name, values) -> {
            JsonArray list = new JsonArray();
            values.forEach(list::add);
            properties.add(name, list);
        });
        out.add("properties", properties);
        if (b.blockEntity() != null) out.addProperty("block_entity", b.blockEntity());
        return ToolResult.of(out);
    }

    // ---- op parsing (shared by single tools and batch) ----

    static List<Op> batchOps(Args a) {
        a.only("batch", "ops", "origin", "vars");
        Vec3 origin = a.has("origin") ? a.vec("origin") : null;
        Map<String, String> vars = vars(a);
        List<Op> ops = new ArrayList<>();
        JsonArray list = a.array("ops");
        if (list.isEmpty()) throw new EditException("ops is empty");
        for (int i = 0; i < list.size(); i++) {
            String type = "?";
            try {
                if (!list.get(i).isJsonObject()) throw new EditException("must be an object");
                JsonObject op = list.get(i).getAsJsonObject();
                if (op.has("op") && op.get("op").isJsonPrimitive()) type = op.get("op").getAsString();
                Args opArgs = a.with(vars.isEmpty() ? op : withVars(op, vars), origin);
                type = opArgs.str("op");
                ops.addAll(parseOp(type, opArgs));
            } catch (EditException e) {
                throw new EditException("ops[" + i + "] (" + type + "): " + e.getMessage(), e);
            } catch (RuntimeException e) {
                throw new EditException("ops[" + i + "] (" + type + "): bad arguments: " + e.getMessage(), e);
            }
        }
        return ops;
    }

    private static Map<String, String> vars(Args a) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (!a.has("vars")) return vars;
        for (Map.Entry<String, JsonElement> e : a.object("vars").entrySet()) {
            if (!VAR.matcher("$" + e.getKey()).matches()) {
                throw new EditException("var name '" + e.getKey() + "' must be letters, digits or _");
            }
            if (!e.getValue().isJsonPrimitive()) throw new EditException("var '" + e.getKey() + "' must be a string");
            vars.put(e.getKey(), e.getValue().getAsString());
        }
        return vars;
    }

    /**
     * A copy of a batch op with {@code $name} replaced where blocks are named: pattern, from, blueprint palette
     * values and block states. Blueprint rows are left alone, where {@code $} may be a palette key.
     */
    private static JsonObject withVars(JsonObject op, Map<String, String> vars) {
        JsonObject out = op.deepCopy();
        for (String field : List.of("pattern", "from")) {
            if (isString(out.get(field))) out.addProperty(field, expand(out.get(field).getAsString(), vars));
        }
        if (out.has("blueprint") && out.get("blueprint").isJsonObject()) {
            JsonElement palette = out.getAsJsonObject("blueprint").get("palette");
            if (palette != null && palette.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : palette.getAsJsonObject().entrySet()) {
                    if (isString(e.getValue())) e.setValue(new JsonPrimitive(expand(e.getValue().getAsString(), vars)));
                }
            }
        }
        JsonElement blocks = out.get("blocks");
        if (blocks != null && blocks.isJsonObject()) {
            JsonObject grouped = new JsonObject();
            blocks.getAsJsonObject().entrySet().forEach(e -> grouped.add(expand(e.getKey(), vars), e.getValue()));
            out.add("blocks", grouped);
        } else if (blocks != null && blocks.isJsonArray()) {
            for (JsonElement e : blocks.getAsJsonArray()) {
                if (e.isJsonArray() && e.getAsJsonArray().size() == 4 && isString(e.getAsJsonArray().get(3))) {
                    e.getAsJsonArray().set(3, new JsonPrimitive(expand(e.getAsJsonArray().get(3).getAsString(), vars)));
                }
            }
        }
        return out;
    }

    private static boolean isString(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    private static String expand(String text, Map<String, String> vars) {
        if (text.indexOf('$') < 0) return text;
        Matcher m = VAR.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = vars.get(m.group(1));
            if (value == null) throw new EditException("unknown var $" + m.group(1) + "; vars: " + String.join(", ", vars.keySet()));
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        return m.appendTail(out).toString();
    }

    /** One op, one per copy for {@code clone}, or any number (none included) for the terrain ops. */
    static List<Op> parseOp(String type, Args a) {
        return switch (type) {
            case "clone" -> cloneOps(a);
            case "foundation", "overlay" -> terrainOps(type, a);
            default -> List.of(parseOne(type, a));
        };
    }

    private static Op parseOne(String type, Args a) {
        return switch (type) {
            case "fill" -> {
                a.only("fill", "region", "pattern", "mode", "from");
                Box box = a.region("region");
                String pattern = a.str("pattern");
                String mode = a.str("mode", "solid");
                if (a.has("from")) {
                    if (!mode.equals("solid")) throw new EditException("\"from\" only works with mode solid");
                    yield new Op.Replace(box, a.str("from"), pattern);
                }
                yield switch (mode) {
                    case "solid" -> new Op.Fill(box, pattern);
                    case "walls" -> new Op.Walls(box, pattern);
                    case "outline" -> new Op.Outline(box, pattern);
                    default -> throw new EditException("mode must be solid, walls or outline");
                };
            }
            case "walls" -> {
                a.only("walls", "region", "pattern");
                yield new Op.Walls(a.region("region"), a.str("pattern"));
            }
            case "outline" -> {
                a.only("outline", "region", "pattern");
                yield new Op.Outline(a.region("region"), a.str("pattern"));
            }
            case "replace" -> {
                a.only("replace", "region", "from", "pattern");
                yield new Op.Replace(a.region("region"), a.str("from"), a.str("pattern"));
            }
            case "cylinder" -> {
                a.only("cylinder", "base", "radius", "radius_z", "height", "pattern", "hollow");
                double r = a.dbl("radius");
                yield new Op.Cylinder(a.vec("base"), a.str("pattern"), r, a.dbl("radius_z", r),
                        Math.max(1, a.integer("height")), a.bool("hollow", false));
            }
            case "sphere" -> {
                a.only("sphere", "center", "radius", "radius_y", "radius_z", "pattern", "hollow");
                double r = a.dbl("radius");
                yield new Op.Sphere(a.vec("center"), a.str("pattern"), r, a.dbl("radius_y", r), a.dbl("radius_z", r), a.bool("hollow", false));
            }
            case "pyramid" -> {
                a.only("pyramid", "base", "size", "pattern", "hollow");
                yield new Op.Pyramid(a.vec("base"), a.str("pattern"), Math.max(1, a.integer("size")), a.bool("hollow", false));
            }
            case "line" -> {
                a.only("line", "from", "to", "pattern", "thickness");
                yield new Op.Line(a.vec("from"), a.vec("to"), a.str("pattern"), Math.max(0, a.dbl("thickness", 0)));
            }
            case "blocks", "set_blocks" -> {
                a.only("set_blocks", "origin", "blocks");
                yield blocks(a);
            }
            case "paste", "place_blueprint" -> {
                a.only("place_blueprint", "blueprint", "path", "name", "origin", "rotation");
                int rotation = a.integer("rotation", 0);
                if (rotation % 90 != 0) throw new EditException("rotation must be a multiple of 90");
                yield new Op.Paste(blueprint(a), a.vec("origin"), rotation);
            }
            case "smooth" -> {
                a.only("smooth", "region", "iterations");
                yield new Op.Smooth(a.region("region"), range(a, "iterations", 2, 1, 10));
            }
            default -> throw new EditException("unknown op '" + type + "'; use fill, cylinder, sphere, pyramid, line, blocks, paste, clone, "
                    + "foundation (region,pattern), overlay (region,pattern,depth|raise) or smooth (region,iterations)");
        };
    }

    private static List<Op> cloneOps(Args a) {
        a.only("clone", "region", "to", "rotation", "flip", "repeat", "step", "air");
        Box box = a.region("region");
        Vec3 to = a.vec("to");
        int rotation = a.integer("rotation", 0);
        if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
            throw new EditException("rotation must be 0, 90, 180 or 270");
        }
        String flip = a.str("flip", null);
        if (flip != null && !flip.equals("x") && !flip.equals("z")) throw new EditException("flip must be \"x\" or \"z\"");
        int repeat = a.integer("repeat", 1);
        if (repeat < 1 || repeat > 64) throw new EditException("repeat must be 1..64");
        int[] step = {0, 0, 0};
        if (a.has("step")) {
            // Plain offsets between copies, so a batch origin does not shift them.
            JsonElement s = a.json().get("step");
            if (!s.isJsonArray() || s.getAsJsonArray().size() != 3) throw new EditException("\"step\" must be [dx,dy,dz]");
            for (int i = 0; i < 3; i++) {
                JsonElement d = s.getAsJsonArray().get(i);
                if (!d.isJsonPrimitive() || !d.getAsJsonPrimitive().isNumber()) throw new EditException("\"step\" must be [dx,dy,dz] numbers");
                step[i] = d.getAsInt();
            }
        } else if (repeat > 1) {
            throw new EditException("repeat needs \"step\": [dx,dy,dz] between copies");
        }
        // Read now, before the batch places anything, so the source and the copies may overlap.
        // Only block states are copied: sign text and container contents are not.
        Blueprint bp = Blueprint.ofStates(box.sizeX(), box.sizeY(), box.sizeZ(),
                a.service().read(a.caller().world(), box), !a.bool("air", false));
        if (flip != null) bp = bp.flipped(flip.charAt(0));
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < repeat; i++) ops.add(new Op.Paste(bp, to.add(step[0] * i, step[1] * i, step[2] * i), rotation));
        return ops;
    }

    private static List<Op> terrainOps(String type, Args a) {
        if (type.equals("foundation")) {
            a.only("foundation", "region", "pattern");
        } else {
            a.only("overlay", "region", "pattern", "depth", "raise");
            if (a.has("depth") && a.has("raise")) throw new EditException("give \"depth\" or \"raise\", not both");
        }
        Box box = a.region("region");
        String pattern = a.str("pattern");
        int depth = type.equals("overlay") ? range(a, "depth", 1, 1, 16) : 0;
        int raise = type.equals("overlay") ? range(a, "raise", 0, 0, 16) : 0;
        // Read now, while the batch is parsed: the ops follow the terrain as it is before the batch runs.
        String[] states = a.service().read(a.caller().world(), box);
        return type.equals("foundation") ? Terrain.foundation(box, states, pattern) : Terrain.overlay(box, states, pattern, depth, raise);
    }

    private static int range(Args a, String name, int def, int min, int max) {
        int v = a.integer(name, def);
        if (v < min || v > max) throw new EditException(name + " must be " + min + ".." + max);
        return v;
    }

    /** Blocks grouped by state, {@code {"state":[[x,y,z],...]}}, or one by one, {@code [[x,y,z,"state"],...]}. */
    private static Op blocks(Args a) {
        Vec3 origin = a.has("origin") ? a.vec("origin") : null;
        List<Op.BlockAt> list = new ArrayList<>();
        JsonElement blocks = a.json().get("blocks");
        if (blocks != null && blocks.isJsonObject()) {
            for (Map.Entry<String, JsonElement> group : blocks.getAsJsonObject().entrySet()) {
                String at = "blocks[\"" + group.getKey() + "\"]";
                if (!group.getValue().isJsonArray()) throw new EditException(at + " must be [[x,y,z],...], got " + group.getValue());
                JsonArray positions = group.getValue().getAsJsonArray();
                for (int n = 0; n < positions.size(); n++) {
                    JsonElement e = positions.get(n);
                    if (!e.isJsonArray() || e.getAsJsonArray().size() != 3) {
                        throw new EditException(at + "[" + n + "] must be [x,y,z], got " + e);
                    }
                    list.add(new Op.BlockAt(position(a, origin, e.getAsJsonArray(), at + "[" + n + "]"), group.getKey()));
                }
            }
        } else if (blocks != null && blocks.isJsonArray()) {
            JsonArray entries = blocks.getAsJsonArray();
            for (int n = 0; n < entries.size(); n++) {
                JsonElement e = entries.get(n);
                String at = "blocks[" + n + "]";
                if (!e.isJsonArray() || e.getAsJsonArray().size() != 4 || !e.getAsJsonArray().get(3).isJsonPrimitive()) {
                    throw new EditException(at + " must be [x,y,z,\"state\"], got " + e);
                }
                list.add(new Op.BlockAt(position(a, origin, e.getAsJsonArray(), at), e.getAsJsonArray().get(3).getAsString()));
            }
        } else {
            throw new EditException("\"blocks\" must be {\"state\":[[x,y,z],...]} or [[x,y,z,\"state\"],...]");
        }
        if (list.isEmpty()) throw new EditException("no blocks");
        return new Op.Blocks(list);
    }

    /** The first three elements of {@code b} as a position: coordinates, or offsets when the op has an origin. */
    private static Vec3 position(Args a, Vec3 origin, JsonArray b, String at) {
        if (origin == null) {
            JsonArray xyz = new JsonArray();
            for (int i = 0; i < 3; i++) xyz.add(b.get(i));
            return a.vec(xyz, at);
        }
        for (int i = 0; i < 3; i++) {
            if (!b.get(i).isJsonPrimitive() || !b.get(i).getAsJsonPrimitive().isNumber()) {
                throw new EditException(at + ": with origin, x/y/z must be numbers (offsets), got " + b.get(i));
            }
        }
        return origin.add(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt());
    }

    private static Blueprint blueprint(Args a) {
        int given = (a.has("blueprint") ? 1 : 0) + (a.has("path") ? 1 : 0) + (a.has("name") ? 1 : 0);
        if (given != 1) throw new EditException("give exactly one of \"blueprint\", \"path\" or \"name\"");
        JsonObject json;
        if (a.has("blueprint")) {
            json = a.object("blueprint");
        } else if (a.has("name")) {
            json = readSavedBlueprint(a.str("name"), a.service());
        } else {
            Path path = blueprintPath(a.str("path"), a);
            if (path.getFileName().toString().endsWith(".json")) {
                json = readBlueprintFile(path);
            } else {
                Schematic s = a.service().bridge().readSchematic(path, a.service().config().maxVolumePerOp());
                return Blueprint.ofStates(s.sizeX(), s.sizeY(), s.sizeZ(), s.states(), true);
            }
        }
        if (!json.has("palette") || !json.has("layers")) throw new EditException("blueprint needs palette and layers");
        Map<Character, String> palette = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("palette").entrySet()) {
            if (e.getKey().length() != 1) throw new EditException("palette keys must be one character: '" + e.getKey() + "'");
            palette.put(e.getKey().charAt(0), e.getValue().getAsString());
        }
        List<List<String>> layers = new ArrayList<>();
        for (JsonElement layer : json.getAsJsonArray("layers")) {
            List<String> rows = new ArrayList<>();
            for (JsonElement row : layer.getAsJsonArray()) rows.add(row.getAsString());
            layers.add(rows);
        }
        return Blueprint.of(palette, layers);
    }

    /** An absolute .json, .schem or .schematic path inside {@code safety.blueprint-dirs}, at most 5 MB. */
    private static Path blueprintPath(String pathString, Args a) {
        Path path = Path.of(pathString);
        if (!path.isAbsolute()) throw new EditException("path must be absolute");
        path = path.normalize();
        String file = path.getFileName().toString();
        if (!file.endsWith(".json") && !file.endsWith(".schem") && !file.endsWith(".schematic")) {
            throw new EditException("path must be a .json, .schem or .schematic file");
        }
        List<Path> allowed = a.blueprintDirs();
        if (!allowed.isEmpty() && allowed.stream().noneMatch(path::startsWith)) {
            throw new EditException("path is outside safety.blueprint-dirs");
        }
        try {
            if (Files.size(path) > MAX_BLUEPRINT_BYTES) throw new EditException("blueprint file is larger than 5 MB");
        } catch (IOException e) {
            throw new EditException("cannot read " + path.getFileName() + ": " + e.getClass().getSimpleName());
        }
        return path;
    }

    private static Path savedDir(BuildService service) {
        return service.dataDir().resolve("blueprints");
    }

    private static String savedName(String name) {
        if (!SAVED_NAME.matcher(name).matches()) throw new EditException("name must be 1-40 of a-z, 0-9, _ and -");
        return name;
    }

    /** Names of saved blueprints, sorted. */
    private static List<String> savedBlueprints(BuildService service) {
        try (var files = Files.list(savedDir(service))) {
            return files.map(p -> p.getFileName().toString()).filter(f -> f.endsWith(".json"))
                    .map(f -> f.substring(0, f.length() - 5)).filter(n -> SAVED_NAME.matcher(n).matches()).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static JsonObject readSavedBlueprint(String name, BuildService service) {
        Path path = savedDir(service).resolve(savedName(name) + ".json");
        if (!Files.isRegularFile(path)) {
            List<String> saved = savedBlueprints(service);
            throw new EditException("no saved blueprint '" + name + "'; " + (saved.isEmpty()
                    ? "none saved yet (use save_blueprint)" : "saved: " + String.join(", ", saved)));
        }
        return readBlueprintFile(path);
    }

    private static ToolResult saveBlueprint(Args a, BuildService service) {
        a.only("save_blueprint", "name", "region");
        String name = savedName(a.str("name"));
        Box box = a.region("region");
        String[] states = service.read(a.caller().world(), box);
        RegionCodec.Encoded enc;
        try {
            enc = RegionCodec.encode(box, states);
        } catch (EditException e) {
            throw new EditException(e.getMessage().replace("read a smaller region", "save a smaller region"), e);
        }
        JsonObject json = new JsonObject();
        JsonObject palette = new JsonObject();
        enc.palette().forEach((k, v) -> palette.addProperty(String.valueOf(k), v));
        json.add("palette", palette);
        JsonArray layers = new JsonArray();
        for (List<String> layer : enc.layers()) {
            JsonArray rows = new JsonArray();
            layer.forEach(rows::add);
            layers.add(rows);
        }
        json.add("layers", layers);
        try {
            Files.createDirectories(savedDir(service));
            Files.writeString(savedDir(service).resolve(name + ".json"), json.toString());
        } catch (IOException e) {
            throw new EditException("cannot save " + name + ": " + e.getClass().getSimpleName());
        }
        JsonObject out = new JsonObject();
        out.addProperty("saved", name);
        out.addProperty("size", box.sizeX() + "x" + box.sizeY() + "x" + box.sizeZ());
        out.addProperty("blocks", java.util.Arrays.stream(states).filter(s -> !RegionCodec.isAir(s)).count());
        return ToolResult.of(out);
    }

    private static JsonObject readBlueprintFile(Path path) {
        try {
            if (Files.size(path) > MAX_BLUEPRINT_BYTES) throw new EditException("blueprint file is larger than 5 MB");
            return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        } catch (IOException e) {
            throw new EditException("cannot read " + path.getFileName() + ": " + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            if (e instanceof EditException) throw e;
            throw new EditException(path.getFileName() + " is not a valid blueprint JSON object");
        }
    }

    // ---- JSON helpers ----

    private static JsonObject schema(String props, String... required) {
        String properties = props.isEmpty() ? COMMON : props + "," + COMMON;
        JsonObject s = JsonParser.parseString("{\"type\":\"object\",\"properties\":{" + properties + "}}").getAsJsonObject();
        if (required.length > 0) {
            JsonArray req = new JsonArray();
            for (String r : required) req.add(r);
            s.add("required", req);
        }
        return s;
    }

    static JsonArray vec(Vec3 v) {
        JsonArray a = new JsonArray();
        a.add(v.x());
        a.add(v.y());
        a.add(v.z());
        return a;
    }

    /** A box in the form the region argument takes: {@code [x1,y1,z1,x2,y2,z2]}. */
    static JsonArray region(Box b) {
        JsonArray a = vec(b.min());
        a.addAll(vec(b.max()));
        return a;
    }

    static JsonObject boxJson(Box b) {
        JsonObject o = new JsonObject();
        o.add("min", vec(b.min()));
        o.add("max", vec(b.max()));
        o.addProperty("size", b.sizeX() + "x" + b.sizeY() + "x" + b.sizeZ());
        return o;
    }

    private static JsonObject selectionJson(SelectionInfo sel) {
        JsonObject o = new JsonObject();
        o.addProperty("world", sel.world());
        o.addProperty("type", sel.type());
        JsonObject box = boxJson(sel.bounds());
        box.entrySet().forEach(e -> o.add(e.getKey(), e.getValue()));
        o.addProperty("volume", sel.volume());
        if (!sel.points().isEmpty()) {
            JsonArray pts = new JsonArray();
            for (int[] p : sel.points()) {
                JsonArray pt = new JsonArray();
                pt.add(p[0]);
                pt.add(p[1]);
                pts.add(pt);
            }
            o.add("points_xz", pts);
        }
        return o;
    }

    private static JsonObject summaryJson(RegionCodec.Summary s) {
        JsonObject o = new JsonObject();
        o.add("box", boxJson(s.box()));
        o.addProperty("non_air", s.nonAir());
        if (s.nonAirBounds() != null) o.add("non_air_bounds", boxJson(s.nonAirBounds()));
        JsonObject counts = new JsonObject();
        s.counts().forEach(counts::addProperty);
        o.add("counts", counts);
        if (s.surfaceMin() != null) {
            JsonArray surface = new JsonArray();
            surface.add(s.surfaceMin());
            surface.add(s.surfaceMax());
            o.add("surface_y", surface);
        }
        if (s.heightmap() != null) {
            JsonArray rows = new JsonArray();
            s.heightmap().forEach(rows::add);
            o.add("heightmap_rows_z", rows);
            o.addProperty("heightmap_note", "per z row, values per x: top non-air y minus box min y, -1 = empty");
        }
        return o;
    }

    /** Minecraft yaw: 0 = south (+z), 90 = west, 180 = north, 270 = east. */
    static String facing(float yaw) {
        float y = ((yaw % 360) + 360) % 360;
        if (y >= 315 || y < 45) return "south";
        if (y < 135) return "west";
        if (y < 225) return "north";
        return "east";
    }
}
