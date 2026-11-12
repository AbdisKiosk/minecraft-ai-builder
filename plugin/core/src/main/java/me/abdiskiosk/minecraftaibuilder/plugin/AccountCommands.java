package me.abdiskiosk.minecraftaibuilder.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.SelectionInfo;
import me.abdiskiosk.minecraftaibuilder.plugin.relay.RelayClient;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.Tools;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

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
 * {@code /aibuilder link <code>}, {@code unlink} and {@code asset save <name> [description]}: a player links their
 * Minecraft account to their panel account, then saves WorldEdit selections into that account's asset library. The
 * panel decides whose library from the link and checks the builder role on this server; the plugin sends only the
 * player's UUID and name. Every request waits on the panel, so it runs off the server thread and answers when done.
 */
public final class AccountCommands {

    /** Players need it for all three; the panel also wants the builder role on this server. */
    public static final String PERMISSION = "aibuilder.asset";
    public static final Set<String> COMMANDS = Set.of("link", "unlink", "asset");
    private static final String SAVE_USAGE = "Usage: /aibuilder asset save <name> [description]";

    /** What a player asked for. */
    public sealed interface Request permits Link, Unlink, Save {
    }

    public record Link(String code) implements Request {
    }

    public record Unlink() implements Request {
    }

    public record Save(String name, String description) implements Request {
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
                if (args.length < 3 || !args[1].equalsIgnoreCase("save")) throw new EditException(SAVE_USAGE);
                String name = Tools.savedName(args[2]);
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
            sender.sendMessage("Only players can link accounts and save assets in game.");
            return;
        }
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage("You need the " + PERMISSION + " permission to link your account or save assets.");
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
            sender.sendMessage("This server is not connected to a panel, so there is no account to link or library to save to.");
            return;
        }
        UUID id = player.getUniqueId();
        String name = player.getName();
        if (!busy.add(id)) {
            sender.sendMessage("Your last request to the panel is still running; wait for it to finish.");
            return;
        }
        if (request instanceof Save) sender.sendMessage("Saving your selection to your asset library…");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String message;
            try {
                message = perform(request, panel, service, id, name);
            } catch (EditException e) {
                message = e.getMessage();
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "/aibuilder " + args[0] + " failed for " + name, e);
                message = "That failed; see the server log.";
            } finally {
                busy.remove(id);
            }
            String m = message;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player online = plugin.getServer().getPlayer(id);
                if (online != null) online.sendMessage(m);
            });
        });
    }

    /**
     * Does what the player asked and answers what to tell them; throws {@link EditException} with the reason when it
     * cannot. Blocks on the panel and on the world read, so never call it on the server thread.
     */
    public static String perform(Request request, RelayClient panel, BuildService service, UUID id, String name) {
        if (request instanceof Link link) {
            JsonObject r = panel.link(id, name, link.code());
            return (bool(r, "already") ? "This Minecraft account was already linked to that panel account." : "Linked to your panel account.")
                    + " Save a WorldEdit selection to your library with /aibuilder asset save <name> [description].";
        }
        if (request instanceof Unlink) {
            panel.unlink(id, name);
            return "Unlinked from your panel account. /aibuilder asset save no longer works for you until you link again.";
        }
        Save save = (Save) request;
        var library = panel.playerLibrary(id, name); // an older panel or one without storage says so first
        BuildService.Caller caller = new BuildService.Caller(id, name, null, null, 0, List.of(), 0, 0);
        SelectionInfo selection = service.selection(caller)
                .orElseThrow(() -> new EditException("Make a WorldEdit selection first (//wand, or //pos1 and //pos2)."));
        JsonObject r = AssetLibrary.saveRegion(library, service, selection.world(), selection.bounds(), save.name(), save.description());
        String size = selection.bounds().sizeX() + "x" + selection.bounds().sizeY() + "x" + selection.bounds().sizeZ();
        JsonElement blocks = r.get("blocks");
        return "Saved " + save.name() + " (" + size + (blocks != null && blocks.isJsonPrimitive() ? ", " + blocks.getAsLong() + " blocks" : "")
                + ") to your asset library on the panel."
                + ("cuboid".equalsIgnoreCase(selection.type()) ? "" : " A " + selection.type() + " selection saves its bounding box.");
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }
}
