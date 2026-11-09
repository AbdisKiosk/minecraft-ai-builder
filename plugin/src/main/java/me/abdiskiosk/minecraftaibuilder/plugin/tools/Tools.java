package me.abdiskiosk.minecraftaibuilder.plugin.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.abdiskiosk.minecraftaibuilder.common.BlockCatalog;
import me.abdiskiosk.minecraftaibuilder.common.BlockInfo;
import me.abdiskiosk.minecraftaibuilder.common.BlockState;
import me.abdiskiosk.minecraftaibuilder.common.Blueprint;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.IsoRenderer;
import me.abdiskiosk.minecraftaibuilder.common.Op;
import me.abdiskiosk.minecraftaibuilder.common.RegionCodec;
import me.abdiskiosk.minecraftaibuilder.common.SelectionInfo;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import me.abdiskiosk.minecraftaibuilder.plugin.BuildService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** All tool definitions. Descriptions are kept short because they are sent to Claude every session. */
public final class Tools {

    private static final String VEC = """
            {"type":"array","items":{"type":["integer","string"]},"minItems":3,"maxItems":3,"description":"[x,y,z]; \\"~n\\" is relative to the player"}""";
    private static final String REGION = """
            {"description":"\\"selection\\" (player's WorldEdit selection) or {\\"min\\":[x,y,z],\\"max\\":[x,y,z]}","anyOf":[{"type":"string","enum":["selection"]},{"type":"object","properties":{"min":%s,"max":%s},"required":["min","max"]}]}"""
            .formatted(VEC, VEC);
    private static final String PATTERN = """
            {"type":"string","description":"WorldEdit pattern: stone_bricks, oak_stairs[facing=east], 70%stone_bricks,30%cracked_stone_bricks"}""";
    private static final String COMMON = """
            "player":{"type":"string","description":"acting player; default: the only online player"},"world":{"type":"string"}""";
    private static final long MAX_BLUEPRINT_BYTES = 5L * 1024 * 1024;

    private Tools() {
    }

    public static void registerAll(ToolRegistry registry, BuildService service) {
        registry.register(new Tool("get_context",
                "Online players with position, facing and looked-at block, the caller's WorldEdit selection, and loaded worlds. Call first.",
                schema(""), a -> context(a, service)));

        registry.register(new Tool("block_info",
                "Check blocks on THIS server: name=valid?, properties and values (facing, half, ...), closest names if unknown; search=words lists matching blocks. Use before unfamiliar blocks.",
                schema("\"name\":{\"type\":\"string\",\"description\":\"e.g. oak_stairs\"},\"search\":{\"type\":\"string\",\"description\":\"words, e.g. oak stairs\"}"),
                a -> blockInfo(a, service)));

        registry.register(new Tool("get_selection",
                "The player's WorldEdit selection (type, min, max, size, volume). summary=true adds block counts inside.",
                schema("\"summary\":{\"type\":\"boolean\"}"), a -> selection(a, service)));

        registry.register(new Tool("read_region",
                "Inspect blocks. mode=summary (default): counts, non-air bounds, surface heights. mode=full: palette+layers in place_blueprint format (costly; keep regions small).",
                schema("\"region\":" + REGION + ",\"mode\":{\"type\":\"string\",\"enum\":[\"summary\",\"full\"]},\"heightmap\":{\"type\":\"boolean\"}", "region"),
                a -> read(a, service)));

        registry.register(new Tool("render_region",
                "PNG of a region: top-down map + isometric view from the south-east. Use to check builds visually.",
                schema("\"region\":" + REGION + ",\"view\":{\"type\":\"string\",\"enum\":[\"both\",\"top\",\"iso\"]}", "region"),
                a -> render(a, service)));

        for (String type : List.of("fill", "walls", "outline")) {
            String what = switch (type) {
                case "fill" -> "Fill a box";
                case "walls" -> "Four vertical sides of a box";
                default -> "All six faces of a box (hollow shell)";
            };
            registry.register(new Tool(type, what + " with a pattern.",
                    schema("\"region\":" + REGION + ",\"pattern\":" + PATTERN, "region", "pattern"),
                    a -> edit(a, service, List.of(parseOp(type, a)))));
        }

        registry.register(new Tool("replace", "Replace blocks matching the 'from' mask (e.g. stone,dirt) inside a box.",
                schema("\"region\":" + REGION + ",\"from\":{\"type\":\"string\"},\"pattern\":" + PATTERN, "region", "from", "pattern"),
                a -> edit(a, service, List.of(parseOp("replace", a)))));

        registry.register(new Tool("cylinder", "Vertical cylinder standing on 'base', growing up 'height' blocks.",
                schema("\"base\":" + VEC + ",\"radius\":{\"type\":\"number\"},\"radius_z\":{\"type\":\"number\"},\"height\":{\"type\":\"integer\"},\"pattern\":" + PATTERN + ",\"hollow\":{\"type\":\"boolean\"}",
                        "base", "radius", "height", "pattern"),
                a -> edit(a, service, List.of(parseOp("cylinder", a)))));

        registry.register(new Tool("sphere", "Sphere or ellipsoid around 'center'.",
                schema("\"center\":" + VEC + ",\"radius\":{\"type\":\"number\"},\"radius_y\":{\"type\":\"number\"},\"radius_z\":{\"type\":\"number\"},\"pattern\":" + PATTERN + ",\"hollow\":{\"type\":\"boolean\"}",
                        "center", "radius", "pattern"),
                a -> edit(a, service, List.of(parseOp("sphere", a)))));

        registry.register(new Tool("line", "Straight line between two points; thickness is a radius.",
                schema("\"from\":" + VEC + ",\"to\":" + VEC + ",\"pattern\":" + PATTERN + ",\"thickness\":{\"type\":\"number\"}", "from", "to", "pattern"),
                a -> edit(a, service, List.of(parseOp("line", a)))));

        registry.register(new Tool("set_blocks",
                "Place individual blocks: [[x,y,z,\"state\"],...]. With 'origin', x/y/z are offsets from it. For details only; use shapes or scripts for bulk.",
                schema("\"origin\":" + VEC + ",\"blocks\":{\"type\":\"array\",\"items\":{\"type\":\"array\"}}", "blocks"),
                a -> edit(a, service, List.of(parseOp("blocks", a)))));

        registry.register(new Tool("batch",
                "Run several ops as ONE undo step: ops=[{\"op\":\"fill|walls|outline|replace|cylinder|sphere|pyramid|line|blocks|paste\", ...same args as the matching tool}]. pyramid takes base,size,pattern,hollow.",
                schema("\"ops\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}", "ops"),
                a -> {
                    a.only("batch", "ops");
                    List<Op> ops = new ArrayList<>();
                    JsonArray list = a.array("ops");
                    for (int i = 0; i < list.size(); i++) {
                        String type = "?";
                        try {
                            if (!list.get(i).isJsonObject()) throw new EditException("must be an object");
                            Args opArgs = a.with(list.get(i).getAsJsonObject());
                            type = opArgs.str("op");
                            ops.add(parseOp(type, opArgs));
                        } catch (EditException e) {
                            throw new EditException("ops[" + i + "] (" + type + "): " + e.getMessage(), e);
                        } catch (RuntimeException e) {
                            throw new EditException("ops[" + i + "] (" + type + "): bad arguments: " + e.getMessage(), e);
                        }
                    }
                    return edit(a, service, ops);
                }));

        registry.register(new Tool("place_blueprint",
                "Paste a structure. blueprint={palette:{\"S\":\"stone_bricks\",\"_\":\"air\"},layers:[[row z0, row z1..] per y]}; row chars run along +x, \"4S\" repeats, '.' keeps existing. Or path=absolute .json file (saves tokens). origin=min corner, rotation=0/90/180/270.",
                schema("\"blueprint\":{\"type\":\"object\"},\"path\":{\"type\":\"string\"},\"origin\":" + VEC + ",\"rotation\":{\"type\":\"integer\"}", "origin"),
                a -> edit(a, service, List.of(parseOp("paste", a)))));

        registry.register(new Tool("undo", "Undo the caller's last edits (same history as //undo).",
                schema("\"steps\":{\"type\":\"integer\"}"),
                a -> {
                    a.only("undo", "steps");
                    int steps = a.integer("steps", 1);
                    if (steps < 1 || steps > 50) throw new EditException("steps must be between 1 and 50");
                    JsonObject o = new JsonObject();
                    o.addProperty("undone", service.undo(a.caller(), steps));
                    o.addProperty("as", a.caller().name());
                    return ToolResult.of(o);
                }));

        registry.register(new Tool("job_status", "Status of a long-running edit job.",
                schema("\"job\":{\"type\":\"string\"}", "job"),
                a -> service.job(a.str("job")).map(Tools::jobJson)
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
        out.add("players", service.sync(() -> {
            JsonArray players = new JsonArray();
            for (Player p : Bukkit.getOnlinePlayers()) {
                Location l = p.getLocation();
                JsonObject pj = new JsonObject();
                pj.addProperty("name", p.getName());
                pj.addProperty("world", l.getWorld().getName());
                pj.add("pos", vec(new Vec3(l.getBlockX(), l.getBlockY(), l.getBlockZ())));
                pj.addProperty("facing", facing(l.getYaw()));
                Block target = p.getTargetBlock((Set<Material>) null, 64);
                if (target != null && target.getType() != Material.AIR) {
                    pj.add("looking_at", vec(new Vec3(target.getX(), target.getY(), target.getZ())));
                }
                players.add(pj);
            }
            return players;
        }));
        if (caller.isPlayer()) {
            service.selection(caller).ifPresent(sel -> out.add("selection", selectionJson(sel)));
        }
        JsonArray worlds = new JsonArray();
        service.worlds().forEach(worlds::add);
        out.add("worlds", worlds);
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
        Box box = a.region("region");
        String[] states = service.read(a.caller().world(), box);
        if ("full".equals(a.str("mode", "summary"))) {
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

    private static ToolResult render(Args a, BuildService service) {
        Box box = a.region("region");
        String[] states = service.read(a.caller().world(), box);
        IsoRenderer.View view = switch (a.str("view", "both")) {
            case "top" -> IsoRenderer.View.TOP;
            case "iso" -> IsoRenderer.View.ISO;
            default -> IsoRenderer.View.BOTH;
        };
        JsonObject meta = new JsonObject();
        meta.add("box", boxJson(box));
        return ToolResult.image(IsoRenderer.renderPng(box, states, view), meta);
    }

    private static ToolResult edit(Args a, BuildService service, List<Op> ops) {
        BuildService.JobResult result = service.await(service.submit(a.caller(), ops));
        ToolResult out = jobJson(result);
        out.data().addProperty("as", a.caller().name());
        return out;
    }

    private static ToolResult jobJson(BuildService.JobResult r) {
        JsonObject o = new JsonObject();
        o.addProperty("job", r.job());
        o.addProperty("status", r.status());
        o.addProperty("changed", r.changed());
        o.addProperty("ms", r.ms());
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

    static Op parseOp(String type, Args a) {
        return switch (type) {
            case "fill" -> {
                a.only("fill", "region", "pattern");
                yield new Op.Fill(a.region("region"), a.str("pattern"));
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
                a.only("place_blueprint", "blueprint", "path", "origin", "rotation");
                int rotation = a.integer("rotation", 0);
                if (rotation % 90 != 0) throw new EditException("rotation must be a multiple of 90");
                yield new Op.Paste(blueprint(a), a.vec("origin"), rotation);
            }
            default -> throw new EditException("unknown op '" + type + "'; use fill, walls, outline, replace, cylinder, sphere, pyramid, line, blocks or paste");
        };
    }

    private static Op blocks(Args a) {
        Vec3 origin = a.has("origin") ? a.vec("origin") : null;
        List<Op.BlockAt> list = new ArrayList<>();
        JsonArray entries = a.array("blocks");
        for (int n = 0; n < entries.size(); n++) {
            JsonElement e = entries.get(n);
            String at = "blocks[" + n + "]";
            if (!e.isJsonArray() || e.getAsJsonArray().size() != 4 || !e.getAsJsonArray().get(3).isJsonPrimitive()) {
                throw new EditException(at + " must be [x,y,z,\"state\"], got " + e);
            }
            JsonArray b = e.getAsJsonArray();
            JsonArray xyz = new JsonArray();
            for (int i = 0; i < 3; i++) xyz.add(b.get(i));
            Vec3 pos;
            if (origin == null) {
                pos = a.vec(xyz, at);
            } else {
                for (int i = 0; i < 3; i++) {
                    if (!b.get(i).isJsonPrimitive() || !b.get(i).getAsJsonPrimitive().isNumber()) {
                        throw new EditException(at + ": with origin, x/y/z must be numbers (offsets), got " + b.get(i));
                    }
                }
                pos = origin.add(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt());
            }
            list.add(new Op.BlockAt(pos, b.get(3).getAsString()));
        }
        if (list.isEmpty()) throw new EditException("no blocks");
        return new Op.Blocks(list);
    }

    private static Blueprint blueprint(Args a) {
        JsonObject json;
        if (a.has("blueprint")) {
            json = a.object("blueprint");
        } else if (a.has("path")) {
            json = readBlueprintFile(a.str("path"), a);
        } else {
            throw new EditException("give either \"blueprint\" or \"path\"");
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

    private static JsonObject readBlueprintFile(String pathString, Args a) {
        Path path = Path.of(pathString);
        if (!path.isAbsolute()) throw new EditException("path must be absolute");
        path = path.normalize();
        if (!path.getFileName().toString().endsWith(".json")) throw new EditException("path must be a .json file");
        List<Path> allowed = a.blueprintDirs();
        if (!allowed.isEmpty() && allowed.stream().noneMatch(path::startsWith)) {
            throw new EditException("path is outside safety.blueprint-dirs");
        }
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
