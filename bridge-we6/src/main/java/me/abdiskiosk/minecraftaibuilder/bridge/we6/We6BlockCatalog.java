package me.abdiskiosk.minecraftaibuilder.bridge.we6;

import me.abdiskiosk.minecraftaibuilder.common.BlockCatalog;
import me.abdiskiosk.minecraftaibuilder.common.BlockInfo;

import java.util.List;
import java.util.Optional;

/** The 1.8-1.12 block set, backed by {@link LegacyBlockTable}. */
final class We6BlockCatalog implements BlockCatalog {

    private final LegacyBlockTable table;
    private final String describe;

    We6BlockCatalog(LegacyBlockTable table, String describe) {
        this.table = table;
        this.describe = describe;
    }

    @Override
    public String describe() {
        return describe;
    }

    @Override
    public boolean legacy() {
        return true;
    }

    @Override
    public boolean strictProperties() {
        return false;
    }

    @Override
    public Optional<BlockInfo> info(String type) {
        if (!table.has(type)) return Optional.empty();
        String canonical = table.canonical(type);
        return Optional.of(new BlockInfo(canonical, table.properties(canonical), List.copyOf(table.fixedProperties(canonical)), null));
    }

    @Override
    public List<String> suggest(String type, int max) {
        return table.suggest(type, max);
    }

    @Override
    public List<String> search(String query, int max) {
        return table.search(query, max);
    }
}
