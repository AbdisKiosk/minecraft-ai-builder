package me.abdiskiosk.minecraftaibuilder.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.SelectionInfo;
import me.abdiskiosk.minecraftaibuilder.plugin.relay.RelayClient;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.Tools;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code /aibuilder link <code>}, {@code unlink}, {@code asset save <name> [description]} and {@code asset list}: a
 * player links their Minecraft account to their panel account, then saves WorldEdit selections into that account's
 * asset library and lists it. The panel decides whose library from the link and checks the builder role on this server;
 * the plugin sends only the player's UUID and name. Every request waits on the panel, so it runs off the server thread
 * and answers when done.
 */
public final class AccountCommands {

    /** Players need it for all of these; the panel also wants the builder role on this server. */
    public static final String PERMISSION = "aibuilder.asset";
    public static final Set<String> COMMANDS = Set.of("link", "unlink", "asset");
    private static final String ASSET_USAGE = "Usage: /aibuilder asset save <name> [description], or /aibuilder asset list";
    /** Assets an in-game list shows; the rest are on the panel. Chat shows about ten lines before it scrolls. */
    static final int LIST_LINES = 15;

    /** What a player asked for. */
    public sealed interface Request permits Link, Unlink, Save, ListAssets {
    }

    public record Link(String code) implements Request {
    }

    public record Unlink() implements Request {
    }

    public record Save(String name, String description) implements Request {
    }

    public record ListAssets() implements Request {
    }

    private final Plugin plugin;
    private final BuildService service;
    private final Supplier<RelayClient> relay;
    private final Logger logger;
    /** Players with a request in flight: one at a time each, so a held-down key cannot queue saves. */
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();

    public AccountCommands(Plugin plugin, BuildService service, Supplier<RelayClient> relay, Logger logger) {
        this.plugin = plugin;
        this.service = service;
        this.relay = relay;
        this.logger = logger;
    }

    /** The arguments after {@code /aibuilder}; throws with the usage when they do not fit. */
    public static Request parse(String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "link" -> {
                // A code may be typed with a space in the middle; the panel ignores case, spaces and dashes.
                String code = String.join("", Arrays.asList(args).subList(1, args.length)).trim();
                if (code.isEmpty() || code.length() > 32 || !code.matches("[A-Za-z0-9-]+")) {
                    throw new EditException("Usage: /aibuilder link <code>, with the code from Minecraft account on the panel's Access tokens page");
                }
                return new Link(code);
            }
            case "unlink" -> {
                if (args.length != 1) throw new EditException("Usage: /aibuilder unlink");
                return new Unlink();
            }
            case "asset" -> {
                String op = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
                if (op.equals("list") && args.length == 2) return new ListAssets();
                if (!op.equals("save") || args.length < 3) throw new EditException(ASSET_USAGE);
                String name;
                try {
                    name = Tools.savedName(args[2]);
                } catch (EditException e) {
                    throw new EditException("An asset name is 1-40 of a-z, 0-9, _ and -, such as oak-window.");
                }
                String description = String.join(" ", Arrays.asList(args).subList(3, args.length)).trim();
                if (description.length() > AssetLibrary.MAX_DESCRIPTION) {
                    throw new EditException("The description is longer than " + AssetLibrary.MAX_DESCRIPTION + " characters.");
                }
                return new Save(name, description);
            }
            default -> throw new IllegalArgumentException(sub);
        }
    }

    /** Runs a player's command: checks it on the server thread, then waits for the panel on another. */
    public void run(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can link accounts and use the asset library in game.");
            return;
        }
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage("You need the " + PERMISSION + " permission for this; ask a server admin.");
            return;
        }
        Request request;
        try {
            request = parse(args);
        } catch (EditException e) {
            sender.sendMessage(e.getMessage());
            return;
        }
        RelayClient panel = relay.get();
        if (panel == null) {
            sender.sendMessage("This server is not connected to a panel, so there is no account or asset library here; ask a server admin.");
            return;
        }
        UUID id = player.getUniqueId();
        String name = player.getName();
        if (!busy.add(id)) {
            sender.sendMessage("Your last request to the panel is still running; wait for it to finish.");
            return;
        }
        if (request instanceof Save) sender.sendMessage("Saving your selection to your asset library...");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String message;
            try {
                message = perform(request, panel, service, id, name);
            } catch (EditException e) {
                message = sentence(e.getMessage());
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "/aibuilder " + args[0] + " failed for " + name, e);
                message = "That failed; ask a server admin to check the server log.";
            } finally {
                busy.remove(id);
            }
            String[] lines = message.split("\n");
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player online = plugin.getServer().getPlayer(id);
                if (online != null) online.sendMessage(lines);
            });
        });
    }

    /**
     * Does what the player asked and answers what to tell them (lines split by '\n'); throws {@link EditException}
     * with the reason when it cannot. Blocks on the panel and on the world read, so never call it on the server thread.
     */
    public static String perform(Request request, RelayClient panel, BuildService service, UUID id, String name) {
        if (request instanceof Link link) {
            JsonObject r = panel.link(id, name, link.code());
            return (bool(r, "already") ? "This Minecraft account was already linked to that panel account." : "Linked to your panel account.")
                    + " Save a WorldEdit selection to your library with /aibuilder asset save <name> [description].";
        }
        if (request instanceof Unlink) {
            panel.unlink(id, name);
            return "Unlinked from your panel account. /aibuilder asset no longer works for you until you link again.";
        }
        var library = panel.playerLibrary(id, name); // an older panel or one without storage says so first
        if (request instanceof ListAssets) {
            JsonObject r;
            try {
                r = library.apply("list", new JsonObject());
            } catch (EditException e) {
                // A panel from before in-game lists takes saves but not this.
                if (String.valueOf(e.getMessage()).startsWith("unknown in-game asset operation")) {
                    throw new EditException("This panel cannot list assets in game yet; see your library on the panel, or ask its owner to update it.");
                }
                throw e;
            }
            return listing(r);
        }
        Save save = (Save) request;
        BuildService.Caller caller = new BuildService.Caller(id, name, null, null, 0, List.of(), 0, 0);
        SelectionInfo selection = service.selection(caller)
                .orElseThrow(() -> new EditException("Make a WorldEdit selection first (//wand, or //pos1 and //pos2)."));
        Box box = selection.bounds();
        // Said here in the player's terms, before the panel is asked; saveRegion would refuse the same in Claude's.
        if (box.volume() > AssetLibrary.MAX_VOLUME) {
            throw new EditException("Your selection is " + count(box.volume()) + " blocks; an asset holds at most "
                    + count(AssetLibrary.MAX_VOLUME) + ". Select a smaller region.");
        }
        long maxRead = service.config() == null ? Long.MAX_VALUE : service.config().maxReadVolume();
        if (box.volume() > maxRead) {
            throw new EditException("Your selection is " + count(box.volume()) + " blocks; this server saves at most "
                    + count(maxRead) + " at once (limits.max-read-volume). Select a smaller region.");
        }
        // The world as it is, as the player sees it and as the panel's Save as asset reads it: never an open draft.
        JsonObject r = AssetLibrary.saveRegion(library, service, selection.world(), box, save.name(), save.description(), false);
        String size = box.sizeX() + "x" + box.sizeY() + "x" + box.sizeZ();
        JsonElement blocks = r.get("blocks");
        return "Saved " + save.name() + " (" + size + (blocks != null && blocks.isJsonPrimitive() ? ", " + blocks.getAsLong() + " blocks" : "")
                + ") to your asset library on the panel."
                + ("cuboid".equalsIgnoreCase(selection.type()) ? "" : " A " + selection.type() + " selection saves its bounding box.");
    }

    /**
     * The panel's list answer as chat lines: own assets first, then those shared with this server ("by" names the
     * owner). At most {@link #LIST_LINES} assets; the panel sends at most {@link AssetLibrary#MAX_LISTED}.
     */
    static String listing(JsonObject answer) {
        JsonElement assets = answer.get("assets");
        if (assets == null || !assets.isJsonArray()) throw new EditException("The panel sent an invalid asset list; try again later.");
        JsonArray all = assets.getAsJsonArray();
        if (all.isEmpty()) return "Your asset library is empty. Save a WorldEdit selection with /aibuilder asset save <name> [description].";
        List<String> lines = new ArrayList<>();
        lines.add(all.size() >= AssetLibrary.MAX_LISTED ? "Your asset library (first " + all.size() + "):" : "Your asset library:");
        for (int i = 0; i < Math.min(all.size(), LIST_LINES); i++) {
            JsonObject a = all.get(i).isJsonObject() ? all.get(i).getAsJsonObject() : new JsonObject();
            StringBuilder line = new StringBuilder("  ").append(clip(text(a, "name"), 40));
            String size = size(a.get("size"));
            if (!size.isEmpty()) line.append("  ").append(size);
            String by = text(a, "by");
            if (!by.isEmpty()) line.append("  shared by ").append(clip(by, 16));
            String description = text(a, "description");
            if (!description.isEmpty()) line.append(" - ").append(clip(description, 40));
            lines.add(line.toString());
        }
        if (all.size() > LIST_LINES) {
            // The panel stops at MAX_LISTED, so a full answer may have more behind it.
            lines.add("  ...and " + (all.size() >= AssetLibrary.MAX_LISTED ? "more" : (all.size() - LIST_LINES) + " more")
                    + "; the panel's Assets page has them all.");
        }
        return String.join("\n", lines);
    }

    /** One short line for chat: a capital first letter and a full stop, whoever wrote the reason. */
    static String sentence(String message) {
        String m = message == null ? "" : message.strip();
        if (m.isEmpty()) return "That failed; try again in a moment.";
        m = Character.toUpperCase(m.charAt(0)) + m.substring(1);
        return m.endsWith(".") || m.endsWith("!") || m.endsWith("?") ? m : m + ".";
    }

    private static String size(JsonElement size) {
        if (size == null || !size.isJsonArray() || size.getAsJsonArray().size() != 3) return "";
        List<String> parts = new ArrayList<>();
        for (JsonElement n : size.getAsJsonArray()) {
            if (!n.isJsonPrimitive() || !n.getAsJsonPrimitive().isNumber()) return "";
            parts.add(Long.toString(n.getAsLong()));
        }
        return String.join("x", parts);
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        // Chat colour codes and line breaks from the panel are shown as plain text, on one line.
        return e != null && e.isJsonPrimitive() ? e.getAsString().replaceAll("[\\s§]+", " ").strip() : "";
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }
}
