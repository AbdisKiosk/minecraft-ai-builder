package me.abdiskiosk.minecraftaibuilder.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.RegionGuard;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import me.abdiskiosk.minecraftaibuilder.common.WorldEditBridge;
import me.abdiskiosk.minecraftaibuilder.plugin.map.WorldMap;
import me.abdiskiosk.minecraftaibuilder.plugin.mcp.McpEndpoint;
import me.abdiskiosk.minecraftaibuilder.plugin.mcp.ToolStats;
import me.abdiskiosk.minecraftaibuilder.plugin.relay.RelayClient;
import me.abdiskiosk.minecraftaibuilder.plugin.relay.RestApi;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.ToolRegistry;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.Tools;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * The plugin listens on no port. Claude Code and scripts reach it only through the panel, which it connects out to
 * ({@link RelayClient}); nothing here may open a server socket.
 */
public final class AIBuilderPlugin extends JavaPlugin {

    private static final Set<String> MARKER_COMMANDS = Set.of("mark", "unmark", "marks", "note");
    private static final String NOT_CONNECTED = "Not connected to a panel, so Claude cannot reach this server: add the server "
            + "on the panel and install the AIBuilder.jar it gives you (it comes set up), or put the relay.url and relay.token "
            + "from the server's page in plugins/AIBuilder/config.yml.";

    private RelayClient relay;
    private Drafts drafts;
    private WorldMap map;
    private WorldEditBridge bridge;
    private PluginConfig config;
    private McpEndpoint mcp;
    /** Runs the background correction checks, one at a time. */
    private java.util.concurrent.ExecutorService checks;
    private BuildService service;
    private AccountCommands accounts;

    @Override
    public void onEnable() {
        System.setProperty("java.awt.headless", "true");
        saveDefaultConfig();
        try {
            if (JarSetup.apply(getResource(JarSetup.ENTRY), getDataFolder().toPath())) {
                reloadConfig();
                getLogger().info("Wrote the panel connection that came with this jar into config.yml.");
            }
        } catch (java.io.IOException e) {
            getLogger().log(Level.WARNING, "Could not apply the panel connection that came with this jar", e);
        }
        if (getConfig().isConfigurationSection("http")) {
            getLogger().warning("config.yml still has an http: section. It is no longer used and can be deleted: "
                    + "the plugin opens no port, and Claude Code connects through the panel (relay:).");
        }
        config = PluginConfig.load(getConfig());

        try {
            bridge = loadBridge();
        } catch (ReflectiveOperationException | LinkageError e) {
            getLogger().log(Level.SEVERE, "Could not load a WorldEdit bridge; is WorldEdit 6.1+ or 7.2+ installed?", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        service = new BuildService(this, bridge, config, getDataFolder().toPath());
        accounts = new AccountCommands(this, service, () -> relay, getLogger());
        org.bukkit.command.PluginCommand command = getCommand("aibuilder");
        if (command != null) command.setTabCompleter(this);
        if (getServer().getPluginManager().isPluginEnabled("WorldGuard") && getConfig().getBoolean("worldguard.enabled", true)) {
            boolean protect = getConfig().getBoolean("worldguard.protect", false);
            try {
                RegionGuard guard = loadGuard();
                service.setGuard(guard, protect);
                getLogger().info("Claude sees the regions of " + guard.name()
                        + (protect ? " and keeps out of those the acting player may not build in." : "."));
            } catch (ReflectiveOperationException | LinkageError e) {
                if (protect) {
                    // Claude could build in protected regions, so stop rather than run unguarded.
                    getLogger().log(Level.SEVERE, "Could not hook into WorldGuard, so AIBuilder stays off while "
                            + "worldguard.protect is true.", e);
                    getServer().getPluginManager().disablePlugin(this);
                    return;
                }
                getLogger().log(Level.WARNING, "Could not hook into WorldGuard; Claude will not see its regions.", e);
            }
        }
        History history = config.history().enabled() ? new History(this, service, config.history()) : null;
        if (history != null) service.setRecorder(history);
        drafts = new Drafts(service, config.drafts(), config.history(), getLogger());
        service.setDrafts(drafts);
        if (config.drafts().review()) {
            getLogger().info("Review is on: Claude's edits go into drafts that are placed only once approved.");
            if (bridge.async()) getLogger().warning("Review needs WorldEdit; with FastAsyncWorldEdit every edit will be refused.");
        }
        ToolRegistry registry = new ToolRegistry(service, getLogger());
        Tools.registerAll(registry, service);
        mcp = new McpEndpoint(registry, getDescription().getVersion());
        if (history != null) {
            checks = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "AIBuilder correction check");
                t.setDaemon(true);
                return t;
            });
            mcp.onSession(CorrectionCheck.of(history, checks, getLogger())::sessionStarted);
        }
        getLogger().info("Using " + bridge.name() + ".");
        if (config.relayUrl().isEmpty() || config.relayToken().isEmpty()) {
            getLogger().warning(NOT_CONNECTED);
            return;
        }
        if (config.map().enabled()) {
            map = new WorldMap(service, config.map(), getLogger());
            service.addListener(map);
        }
        try {
            relay = new RelayClient(config.relayUrl(), config.relayToken(), mcp, new RestApi(registry), history, drafts,
                    map, service, this::relayHello, getLogger());
            relay.start();
            getLogger().info("Connecting to the panel at " + relay.uri());
        } catch (IllegalArgumentException e) {
            getLogger().warning("relay.url: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (relay != null) relay.stop();
        if (checks != null) checks.shutdownNow();
        if (map != null) map.close();
        if (drafts != null) drafts.close();
    }

    /** What the panel shows about this server. */
    private JsonObject relayHello() {
        JsonObject o = new JsonObject();
        o.addProperty("plugin", getDescription().getVersion());
        o.addProperty("mc", BuildService.minecraftVersion());
        o.addProperty("bridge", bridge.name());
        JsonArray worlds = new JsonArray();
        service.worlds().forEach(worlds::add);
        o.add("worlds", worlds);
        return o;
    }

    private WorldEditBridge loadBridge() throws ReflectiveOperationException {
        boolean fawe = getServer().getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
        String impl = classExists("com.sk89q.worldedit.math.BlockVector3")
                ? "me.abdiskiosk.minecraftaibuilder.bridge.we7.We7Bridge"
                : "me.abdiskiosk.minecraftaibuilder.bridge.we6.We6Bridge";
        // Loaded reflectively so the bridge for the other WorldEdit major version is never linked.
        return (WorldEditBridge) Class.forName(impl, true, getClassLoader()).getConstructor(boolean.class).newInstance(fawe);
    }

    /** The WorldGuard hook in the bridge for the same WorldEdit major version (WorldGuard 7 needs WorldEdit 7). */
    private RegionGuard loadGuard() throws ReflectiveOperationException {
        String impl = classExists("com.sk89q.worldedit.math.BlockVector3")
                ? "me.abdiskiosk.minecraftaibuilder.bridge.we7.WorldGuardGuard"
                : "me.abdiskiosk.minecraftaibuilder.bridge.we6.WorldGuardGuard";
        return (RegionGuard) Class.forName(impl, true, getClassLoader()).getConstructor().newInstance();
    }

    private boolean classExists(String name) {
        try {
            Class.forName(name, false, getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (bridge == null) {
            sender.sendMessage("AIBuilder is not running; check the server log.");
            return true;
        }
        // Players with aibuilder.asset may link and save without aibuilder.admin, which everything else needs.
        if (args.length > 0 && AccountCommands.COMMANDS.contains(args[0].toLowerCase(Locale.ROOT))) {
            accounts.run(sender, args);
            return true;
        }
        if (!sender.hasPermission("aibuilder.admin")) {
            sender.sendMessage(sender.hasPermission(AccountCommands.PERMISSION)
                    ? "You can use /aibuilder link <code>, /aibuilder unlink, /aibuilder asset save <name> [description] and /aibuilder asset list."
                    : "You need aibuilder.admin for this.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("stats")) {
            if (args.length > 1 && args[1].equalsIgnoreCase("reset")) {
                mcp.stats().reset();
                sender.sendMessage("AIBuilder stats cleared.");
            } else {
                stats(sender);
            }
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("draft")) {
            draftCommand(sender, args);
            return true;
        }
        if (args.length > 0 && MARKER_COMMANDS.contains(args[0].toLowerCase(Locale.ROOT))) {
            try {
                markerCommand(sender, args);
            } catch (EditException e) {
                sender.sendMessage(e.getMessage());
            }
            return true;
        }
        sender.sendMessage("AIBuilder " + getDescription().getVersion() + " using " + bridge.name());
        RegionGuard guard = service.guard();
        if (guard != null) sender.sendMessage("Regions: " + guard.name() + (service.protects() ? ", protected" : ""));
        if (relay == null) {
            sender.sendMessage(NOT_CONNECTED);
        } else {
            sender.sendMessage("Panel relay: " + relay.status());
            sender.sendMessage("Claude Code and scripts connect through the panel, with an access token from Connect Claude on this server's page.");
        }
        return true;
    }

    /** Only what the sender may run; see {@link Completions}. */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (service == null) return List.of();
        Player player = sender instanceof Player p ? p : null;
        return Completions.complete(args, player != null, sender::hasPermission, () -> {
            Markers markers = service.markers();
            List<String> worlds = player != null ? List.of(player.getWorld().getName()) : markers.worlds();
            return worlds.stream().flatMap(w -> markers.list(w).keySet().stream()).toList();
        }, () -> drafts == null ? List.of() : drafts.openSummaries().stream().map(d -> d.get("world").getAsString()).toList());
    }

    /** mark, unmark, marks and note: how a player points at places and leaves word for Claude. */
    private void markerCommand(CommandSender sender, String[] args) {
        Markers markers = service.markers();
        String sub = args[0].toLowerCase(Locale.ROOT);
        Player player = sender instanceof Player p ? p : null;
        switch (sub) {
            case "mark" -> {
                if (player == null) throw new EditException("Only players can set markers.");
                if (args.length != 2) throw new EditException("Usage: /aibuilder mark <name>");
                Block target = player.getTargetBlock((Set<Material>) null, 64);
                Vec3 pos;
                if (target != null && !target.getType().name().endsWith("AIR")) {
                    pos = new Vec3(target.getX(), target.getY(), target.getZ());
                } else {
                    Location l = player.getLocation();
                    pos = new Vec3(l.getBlockX(), l.getBlockY(), l.getBlockZ());
                }
                String name = Markers.name(args[1]);
                boolean moved = markers.set(player.getWorld().getName(), name, pos);
                player.sendMessage("Marker " + name + (moved ? " moved to " : " set at ") + pos.x() + " " + pos.y() + " " + pos.z()
                        + ". Claude can use \"@" + name + "\" as a position.");
            }
            case "unmark" -> {
                if (args.length != 2) throw new EditException("Usage: /aibuilder unmark <name>");
                String name = Markers.name(args[1]);
                // The console has no world of its own, so it removes the name everywhere.
                List<String> worlds = player != null ? List.of(player.getWorld().getName()) : markers.worlds();
                int removed = 0;
                for (String world : worlds) {
                    if (markers.remove(world, name)) removed++;
                }
                sender.sendMessage(removed > 0 ? "Marker " + name + " removed." : "There is no marker " + name + ".");
            }
            case "marks" -> {
                List<String> worlds = player != null ? List.of(player.getWorld().getName()) : markers.worlds();
                boolean any = false;
                for (String world : worlds) {
                    Map<String, Vec3> list = markers.list(world);
                    if (list.isEmpty()) continue;
                    any = true;
                    sender.sendMessage("Markers in " + world + ":");
                    list.forEach((name, v) -> sender.sendMessage("  " + name + ": " + v.x() + " " + v.y() + " " + v.z()));
                }
                if (!any) sender.sendMessage("No markers yet. Look at a block and run /aibuilder mark <name>.");
            }
            case "note" -> {
                if (player == null) throw new EditException("Only players can leave notes.");
                if (args.length < 2) throw new EditException("Usage: /aibuilder note <text>");
                String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                String note = markers.addNote(player.getName(), text);
                player.sendMessage("Noted for Claude" + (note.length() < text.trim().length() ? " (cut to " + Markers.MAX_NOTE_LENGTH
                        + " characters)" : "") + "; it reads notes with get_context.");
            }
            default -> throw new IllegalStateException(sub);
        }
    }

    /**
     * {@code /aibuilder draft} lists open drafts; {@code approve [world] [force]} and {@code reject [world]} decide
     * one. Approving reads the region over several ticks, so it runs off the main thread.
     */
    private void draftCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("aibuilder.approve")) {
            sender.sendMessage("You need aibuilder.approve to see or decide drafts.");
            return;
        }
        List<com.google.gson.JsonObject> open = drafts.openSummaries();
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        if (action.equals("list")) {
            if (open.isEmpty()) {
                sender.sendMessage("No drafts waiting." + (config.drafts().review() ? "" : " Review is off (review.enabled)."));
                return;
            }
            for (com.google.gson.JsonObject d : open) {
                sender.sendMessage(d.get("id").getAsString() + " in " + d.get("world").getAsString() + ": "
                        + d.get("edits").getAsInt() + " edits, +" + d.get("added").getAsLong() + " -" + d.get("removed").getAsLong()
                        + " ~" + d.get("changed").getAsLong());
            }
            sender.sendMessage("/aibuilder draft approve|reject [world] [force]");
            return;
        }
        if (!action.equals("approve") && !action.equals("reject")) {
            sender.sendMessage("Usage: /aibuilder draft [approve|reject] [world] [force]");
            return;
        }
        boolean force = Arrays.asList(args).subList(2, args.length).stream().anyMatch(a -> a.equalsIgnoreCase("force"));
        String world = Arrays.asList(args).subList(2, args.length).stream().filter(a -> !a.equalsIgnoreCase("force")).findFirst()
                .orElse(sender instanceof Player p ? p.getWorld().getName() : null);
        com.google.gson.JsonObject target = open.stream()
                .filter(d -> world == null ? open.size() == 1 : d.get("world").getAsString().equals(world)).findFirst().orElse(null);
        if (target == null) {
            sender.sendMessage(open.isEmpty() ? "No drafts waiting." : "Name the world: /aibuilder draft " + action + " <world>");
            return;
        }
        String id = target.get("id").getAsString();
        String by = sender.getName();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            String message;
            try {
                if (action.equals("reject")) {
                    drafts.reject(id, by);
                    message = "Draft " + id + " rejected.";
                } else {
                    com.google.gson.JsonObject r = drafts.approve(id, force, by);
                    message = "Draft " + id + " approved: placing " + r.get("blocks").getAsInt() + " blocks"
                            + (r.get("conflicts").getAsInt() > 0 ? "; " + r.get("conflicts").getAsInt()
                            + " changed since were left alone (add force to overwrite them)" : "") + ".";
                }
            } catch (EditException e) {
                message = e.getMessage();
            }
            String m = message;
            getServer().getScheduler().runTask(this, () -> sender.sendMessage(m));
        });
    }

    /** MCP traffic per tool since the last reset; token figures are estimates. */
    private void stats(CommandSender sender) {
        List<ToolStats.Row> rows = mcp.stats().rows();
        if (rows.isEmpty()) {
            sender.sendMessage("No MCP calls yet. (Calls from scripts are not counted.)");
            return;
        }
        sender.sendMessage(String.format("%-16s %6s %9s %9s %10s %8s", "tool", "calls", "arg ch", "result ch", "image px", "~tokens"));
        long total = 0;
        for (ToolStats.Row r : rows) {
            sender.sendMessage(String.format("%-16s %6d %9d %9d %10d %8d", r.tool(), r.calls(), r.argChars(), r.resultChars(), r.imagePixels(), r.tokens()));
            total += r.tokens();
        }
        sender.sendMessage(String.format("%-16s %46s %8d", "total", "", total));
        sender.sendMessage("Clear with /aibuilder stats reset.");
    }
}
