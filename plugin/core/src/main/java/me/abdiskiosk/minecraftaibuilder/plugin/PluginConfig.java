package me.abdiskiosk.minecraftaibuilder.plugin;

import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import me.abdiskiosk.minecraftaibuilder.plugin.map.WorldMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;
import java.util.List;

public record PluginConfig(
        int blocksPerTick,
        long maxVolumePerOp,
        long maxReadVolume,
        long minFreeMemoryMb,
        int jobWaitSeconds,
        List<String> allowedWorlds,
        String zoneWorld,
        Box zone,
        List<Path> blueprintDirs,
        boolean allowTeleport,
        String relayUrl,
        String relayToken,
        History.Settings history,
        Drafts.Settings drafts,
        WorldMap.Settings map) {

    public static PluginConfig load(FileConfiguration config) {
        ConfigurationSection zone = config.getConfigurationSection("safety.build-zone");
        String zoneWorld = zone == null ? "" : zone.getString("world", "");
        Box zoneBox = zoneWorld.isEmpty() ? null : Box.of(vec(zone.getIntegerList("min")), vec(zone.getIntegerList("max")));
        return new PluginConfig(
                Math.max(100, config.getInt("limits.blocks-per-tick", 4000)),
                config.getLong("limits.max-volume-per-op", 2_000_000L),
                config.getLong("limits.max-read-volume", 2_000_000L),
                Math.max(0, config.getLong("limits.min-free-memory-mb", 256L)),
                Math.max(1, config.getInt("limits.job-wait-seconds", 30)),
                config.getStringList("safety.allowed-worlds"),
                zoneWorld,
                zoneBox,
                config.getStringList("safety.blueprint-dirs").stream().map(p -> Path.of(p).toAbsolutePath().normalize()).toList(),
                config.getBoolean("players.allow-teleport", true),
                config.getString("relay.url", "").trim(),
                config.getString("relay.token", "").trim(),
                new History.Settings(
                        config.getBoolean("history.enabled", true),
                        config.getLong("history.max-volume", 2_000_000L),
                        Math.max(1, config.getInt("history.keep", 500)),
                        Math.max(1, config.getLong("history.max-mb", 200)) * 1024 * 1024,
                        Math.max(1024, config.getLong("history.reads-per-tick", 32_768L)),
                        config.getLong("history.max-render-volume", 1_000_000L)),
                new Drafts.Settings(
                        config.getBoolean("review.enabled", false),
                        Math.max(1, config.getLong("review.max-volume", 1_000_000L)),
                        Math.max(0, config.getInt("review.keep", 50)),
                        Math.max(1, config.getLong("promote.max-volume", 2_000_000L))),
                new WorldMap.Settings(
                        config.getBoolean("map.enabled", true),
                        Math.max(1, Math.min(8, config.getInt("map.threads", 2)))));
    }

    private static Vec3 vec(List<Integer> list) {
        return new Vec3(list.size() > 0 ? list.get(0) : 0, list.size() > 1 ? list.get(1) : 0, list.size() > 2 ? list.get(2) : 0);
    }
}
