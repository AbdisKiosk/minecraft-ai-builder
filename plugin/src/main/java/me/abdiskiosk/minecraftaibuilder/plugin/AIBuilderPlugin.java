package me.abdiskiosk.minecraftaibuilder.plugin;

import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import me.abdiskiosk.minecraftaibuilder.common.WorldEditBridge;
import me.abdiskiosk.minecraftaibuilder.plugin.http.HttpApi;
import me.abdiskiosk.minecraftaibuilder.plugin.mcp.McpEndpoint;
import me.abdiskiosk.minecraftaibuilder.plugin.mcp.ToolStats;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.ToolRegistry;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.Tools;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

public final class AIBuilderPlugin extends JavaPlugin {

    private static final Set<String> MARKER_COMMANDS = Set.of("mark", "unmark", "marks", "note");

    private HttpApi http;
    private WorldEditBridge bridge;
    private PluginConfig config;
    private McpEndpoint mcp;
    private BuildService service;

    @Override
    public void onEnable() {
        System.setProperty("java.awt.headless", "true");
        saveDefaultConfig();
        if (getConfig().getString("http.token", "").isEmpty()) {
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            getConfig().set("http.token", HexFormat.of().formatHex(bytes));
            saveConfig();
            getLogger().info("Generated an access token in config.yml (http.token).");
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
        ToolRegistry registry = new ToolRegistry(service, getLogger());
        Tools.registerAll(registry, service);
        mcp = new McpEndpoint(registry, getDescription().getVersion());
        http = new HttpApi(config.bind(), config.port(), config.token(), mcp, registry, getLogger());
        try {
            http.start();
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not listen on " + config.bind() + ":" + config.port(), e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Using " + bridge.name() + ". MCP: " + url() + "/mcp  REST: " + url() + "/api/<tool>");
    }

    @Override
    public void onDisable() {
        if (http != null) http.stop();
    }

    private String url() {
        return "http://" + config.bind() + ":" + config.port();
    }

    private WorldEditBridge loadBridge() throws ReflectiveOperationException {
        boolean fawe = getServer().getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
        String impl = classExists("com.sk89q.worldedit.math.BlockVector3")
                ? "me.abdiskiosk.minecraftaibuilder.bridge.we7.We7Bridge"
                : "me.abdiskiosk.minecraftaibuilder.bridge.we6.We6Bridge";
        // Loaded reflectively so the bridge for the other WorldEdit major version is never linked.
        return (WorldEditBridge) Class.forName(impl, true, getClassLoader()).getConstructor(boolean.class).newInstance(fawe);
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
        if (args.length > 0 && args[0].equalsIgnoreCase("stats")) {
            if (args.length > 1 && args[1].equalsIgnoreCase("reset")) {
                mcp.stats().reset();
                sender.sendMessage("AIBuilder stats cleared.");
            } else {
                stats(sender);
            }
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
        sender.sendMessage("MCP endpoint: " + url() + "/mcp");
        if (sender instanceof ConsoleCommandSender) {
            sender.sendMessage("export AIBUILDER_URL=" + url() + " AIBUILDER_TOKEN=" + config.token());
        } else {
            sender.sendMessage("The token is in plugins/AIBuilder/config.yml (or run /aibuilder from the console).");
        }
        return true;
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

    /** MCP traffic per tool since the last reset; token figures are estimates. */
    private void stats(CommandSender sender) {
        List<ToolStats.Row> rows = mcp.stats().rows();
        if (rows.isEmpty()) {
            sender.sendMessage("No MCP calls yet. (REST calls from scripts are not counted.)");
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
