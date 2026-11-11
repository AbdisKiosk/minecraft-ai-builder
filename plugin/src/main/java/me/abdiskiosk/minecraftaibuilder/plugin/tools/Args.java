package me.abdiskiosk.minecraftaibuilder.plugin.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.SelectionInfo;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import me.abdiskiosk.minecraftaibuilder.plugin.BuildService;
import me.abdiskiosk.minecraftaibuilder.plugin.Markers;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Typed access to tool arguments. Coordinates are {@code [x,y,z]}; each part may be a number or
 * {@code "~n"}, relative to the caller's feet; {@code "@name"} is a marker. A region is {@code "selection"}, {@code [x1,y1,z1,x2,y2,z2]} or
 * {@code {"min":[..],"max":[..]}}. Inside a batch with an origin, plain numbers are offsets from that origin.
 */
public final class Args {

    private static final Set<String> COMMON = Set.of("player", "world", "op");

    private final JsonObject json;
    private final BuildService.Caller caller;
    private final BuildService service;
    private final Vec3 offset;

    public Args(JsonObject json, BuildService.Caller caller, BuildService service) {
        this(json, caller, service, null);
    }

    private Args(JsonObject json, BuildService.Caller caller, BuildService service, Vec3 offset) {
        this.json = json == null ? new JsonObject() : json;
        this.caller = caller;
        this.service = service;
        this.offset = offset;
    }

    /** Rejects misspelled or unsupported arguments instead of silently ignoring them. */
    public void only(String what, String... allowed) {
        Set<String> ok = new TreeSet<>(List.of(allowed));
        for (String key : json.keySet()) {
            if (!ok.contains(key) && !COMMON.contains(key)) {
                throw new EditException("unknown argument \"" + key + "\" for " + what + "; allowed: " + String.join(", ", ok));
            }
        }
    }

    /** Arguments of one batch op; with an {@code origin}, its plain-number coordinates count from there. */
    public Args with(JsonObject other, Vec3 origin) {
        return new Args(other, caller, service, origin);
    }

    public JsonObject json() {
        return json;
    }

    public BuildService.Caller caller() {
        return caller;
    }

    public BuildService service() {
        return service;
    }

    public java.util.List<java.nio.file.Path> blueprintDirs() {
        return service.config().blueprintDirs();
    }

    public boolean has(String name) {
        return json.has(name) && !json.get(name).isJsonNull();
    }

    public String str(String name) {
        if (!has(name)) throw new EditException("missing \"" + name + "\"");
        return json.get(name).getAsString();
    }

    public String str(String name, String def) {
        return has(name) ? json.get(name).getAsString() : def;
    }

    public int integer(String name, int def) {
        return has(name) ? (int) Math.floor(number(name)) : def;
    }

    public int integer(String name) {
        return (int) Math.floor(number(name));
    }

    public double dbl(String name, double def) {
        return has(name) ? number(name) : def;
    }

    public double dbl(String name) {
        return number(name);
    }

    public boolean bool(String name, boolean def) {
        if (!has(name)) return def;
        JsonElement e = json.get(name);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
        if (e.isJsonPrimitive() && ("true".equals(e.getAsString()) || "false".equals(e.getAsString()))) return e.getAsBoolean();
        throw new EditException("\"" + name + "\" must be true or false, got " + e);
    }

    private double number(String name) {
        if (!has(name)) throw new EditException("missing \"" + name + "\"");
        JsonElement e = json.get(name);
        try {
            if (e.isJsonPrimitive()) return e.getAsDouble();
        } catch (NumberFormatException ignored) {
            // fall through to the message below
        }
        throw new EditException("\"" + name + "\" must be a number, got " + e);
    }

    public JsonArray array(String name) {
        if (!has(name) || !json.get(name).isJsonArray()) throw new EditException("\"" + name + "\" must be an array");
        return json.getAsJsonArray(name);
    }

    public JsonObject object(String name) {
        if (!has(name) || !json.get(name).isJsonObject()) throw new EditException("\"" + name + "\" must be an object");
        return json.getAsJsonObject(name);
    }

    public Vec3 vec(String name) {
        if (!has(name)) throw new EditException("missing \"" + name + "\"");
        return vec(json.get(name), name);
    }

    public Vec3 vec(JsonElement element, String what) {
        if (element.isJsonPrimitive() && element.getAsString().startsWith("@")) return marker(element.getAsString().substring(1));
        if (!element.isJsonArray() || element.getAsJsonArray().size() != 3) {
            throw new EditException("\"" + what + "\" must be [x,y,z] or \"@marker\"");
        }
        JsonArray a = element.getAsJsonArray();
        return new Vec3(coord(a.get(0), 0, what), coord(a.get(1), 1, what), coord(a.get(2), 2, what));
    }

    /** A marker is an absolute position, so a batch origin does not shift it. */
    private Vec3 marker(String name) {
        Markers markers = service.markers();
        return markers.get(caller.world(), name).orElseThrow(() -> {
            Set<String> known = markers.list(caller.world()).keySet();
            return new EditException("unknown marker \"@" + name + "\" in world '" + caller.world() + "'; "
                    + (known.isEmpty() ? "none set yet" : "markers: " + String.join(", ", known))
                    + ". A player sets one with /aibuilder mark <name>.");
        });
    }

    private int coord(JsonElement e, int axis, String what) {
        JsonPrimitive p = e.getAsJsonPrimitive();
        int shift = offset == null ? 0 : axis == 0 ? offset.x() : axis == 1 ? offset.y() : offset.z();
        if (p.isNumber()) return shift + (int) Math.floor(p.getAsDouble());
        String s = p.getAsString().trim();
        if (!s.startsWith("~")) {
            try {
                return shift + Integer.parseInt(s);
            } catch (NumberFormatException ex) {
                throw new EditException("bad coordinate '" + s + "' in \"" + what + "\"");
            }
        }
        caller.requirePlayer("relative coordinates (~)");
        int base = axis == 0 ? caller.feet().x() : axis == 1 ? caller.feet().y() : caller.feet().z();
        String rest = s.substring(1);
        try {
            return base + (rest.isEmpty() ? 0 : Integer.parseInt(rest.startsWith("+") ? rest.substring(1) : rest));
        } catch (NumberFormatException ex) {
            throw new EditException("bad coordinate '" + s + "' in \"" + what + "\"");
        }
    }

    public Box region(String name) {
        if (!has(name)) throw new EditException("missing \"" + name + "\"");
        JsonElement e = json.get(name);
        if (e.isJsonPrimitive() && "selection".equals(e.getAsString())) {
            SelectionInfo sel = service.selection(caller)
                    .orElseThrow(() -> new EditException(caller.name() + " has no complete WorldEdit selection"));
            if (!sel.world().equals(caller.world())) {
                throw new EditException("selection is in world '" + sel.world() + "' but the call targets '" + caller.world() + "'");
            }
            return sel.bounds();
        }
        if (e.isJsonArray() && e.getAsJsonArray().size() == 6) {
            JsonArray six = e.getAsJsonArray(), min = new JsonArray(), max = new JsonArray();
            for (int i = 0; i < 3; i++) {
                min.add(six.get(i));
                max.add(six.get(i + 3));
            }
            return Box.of(vec(min, name), vec(max, name));
        }
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (!o.has("min") || !o.has("max")) throw new EditException("\"" + name + "\" needs min and max");
            return Box.of(vec(o.get("min"), name + ".min"), vec(o.get("max"), name + ".max"));
        }
        throw new EditException("\"" + name + "\" must be \"selection\" or [x1,y1,z1,x2,y2,z2]");
    }
}
