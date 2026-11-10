package me.abdiskiosk.minecraftaibuilder.plugin;

import me.abdiskiosk.minecraftaibuilder.common.WorldEditBridge;
import me.abdiskiosk.minecraftaibuilder.plugin.http.HttpApi;
import me.abdiskiosk.minecraftaibuilder.plugin.mcp.McpEndpoint;
import me.abdiskiosk.minecraftaibuilder.plugin.mcp.ToolStats;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.ToolRegistry;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.Tools;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.logging.Level;

public final class AIBuilderPlugin extends JavaPlugin {

    private HttpApi http;
    private WorldEditBridge bridge;
    private PluginConfig config;
    private McpEndpoint mcp;

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

        BuildService service = new BuildService(this, bridge, config, getDataFolder().toPath());
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
        sender.sendMessage("AIBuilder " + getDescription().getVersion() + " using " + bridge.name());
        sender.sendMessage("MCP endpoint: " + url() + "/mcp");
        if (sender instanceof ConsoleCommandSender) {
            sender.sendMessage("export AIBUILDER_URL=" + url() + " AIBUILDER_TOKEN=" + config.token());
        } else {
            sender.sendMessage("The token is in plugins/AIBuilder/config.yml (or run /aibuilder from the console).");
        }
        return true;
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
