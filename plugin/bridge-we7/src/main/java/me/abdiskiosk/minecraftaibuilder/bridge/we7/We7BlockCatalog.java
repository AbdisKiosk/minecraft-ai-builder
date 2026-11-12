package me.abdiskiosk.minecraftaibuilder.bridge.we7;

import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import me.abdiskiosk.minecraftaibuilder.common.BlockCatalog;
import me.abdiskiosk.minecraftaibuilder.common.BlockInfo;
import me.abdiskiosk.minecraftaibuilder.common.BlockNames;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The block set of the running 1.13+ server, as WorldEdit's registry knows it. */
final class We7BlockCatalog implements BlockCatalog {

    private final String describe;
    private final Map<String, Optional<BlockInfo>> cache = new ConcurrentHashMap<>();

    We7BlockCatalog(String describe) {
        this.describe = describe;
    }

    @Override
    public String describe() {
        return describe;
    }

    @Override
    public boolean legacy() {
        return false;
    }

    @Override
    public boolean strictProperties() {
        return true; // WorldEdit 7 rejects unknown properties itself
    }

    @Override
    public Optional<BlockInfo> info(String type) {
        return cache.computeIfAbsent(type, t -> {
            BlockType block = BlockTypes.get("minecraft:" + t);
            if (block == null) return Optional.empty();
            Map<String, List<String>> properties = new LinkedHashMap<>();
            for (Property<?> property : block.getProperties()) {
                List<String> values = new ArrayList<>();
                for (Object v : property.getValues()) values.add(String.valueOf(v).toLowerCase(Locale.ROOT));
                properties.put(property.getName(), values);
            }
            return Optional.of(new BlockInfo(t, properties, List.of(), null));
        });
    }

    private List<String> names() {
        return BlockType.REGISTRY.keySet().stream().map(id -> id.startsWith("minecraft:") ? id.substring(10) : id).toList();
    }

    @Override
    public List<String> suggest(String type, int max) {
        return BlockNames.closest(type, names(), max);
    }

    @Override
    public List<String> search(String query, int max) {
        return BlockNames.search(query, names(), max);
    }
}
