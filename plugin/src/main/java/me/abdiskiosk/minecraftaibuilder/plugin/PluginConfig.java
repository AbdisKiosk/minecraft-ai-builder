package me.abdiskiosk.minecraftaibuilder.plugin;

import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;
import java.util.List;

public record PluginConfig(
        String bind,
        int port,
        String token,
        int blocksPerTick,
        long maxVolumePerOp,
        long maxReadVolume,
        int jobWaitSeconds,
        List<String> allowedWorlds,
        String zoneWorld,
        Box zone,
        List<Path> blueprintDirs) {

    public static PluginConfig load(FileConfiguration config) {
        ConfigurationSection zone = config.getConfigurationSection("safety.build-zone");
        String zoneWorld = zone == null ? "" : zone.getString("world", "");
        Box zoneBox = zoneWorld.isEmpty() ? null : Box.of(vec(zone.getIntegerList("min")), vec(zone.getIntegerList("max")));
        return new PluginConfig(
                config.getString("http.bind", "127.0.0.1"),
                config.getInt("http.port", 8765),
                config.getString("http.token", ""),
                Math.max(100, config.getInt("limits.blocks-per-tick", 4000)),
                config.getLong("limits.max-volume-per-op", 2_000_000L),
                config.getLong("limits.max-read-volume", 262_144L),
                Math.max(1, config.getInt("limits.job-wait-seconds", 30)),
                config.getStringList("safety.allowed-worlds"),
                zoneWorld,
                zoneBox,
                config.getStringList("safety.blueprint-dirs").stream().map(p -> Path.of(p).toAbsolutePath().normalize()).toList());
    }

    private static Vec3 vec(List<Integer> list) {
        return new Vec3(list.size() > 0 ? list.get(0) : 0, list.size() > 1 ? list.get(1) : 0, list.size() > 2 ? list.get(2) : 0);
    }
}
