package me.abdiskiosk.minecraftaibuilder.common;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Checks every block name, property and value in a list of ops against the server's {@link BlockCatalog}
 * before anything is built, so one bad block never leaves half a structure behind.
 */
public final class BlockCheck {

    private BlockCheck() {
    }

    /** @return warnings (properties that are ignored on this server); throws {@link EditException} on real errors */
    public static List<String> check(BlockCatalog catalog, List<Op> ops) {
        Set<String> errors = new LinkedHashSet<>();
        Set<String> warnings = new LinkedHashSet<>();
        for (Op op : ops) {
            for (String pattern : op.patterns()) checkList(catalog, pattern, errors, warnings);
            for (String mask : op.masks()) {
                for (String component : BlockSyntax.maskComponents(mask)) checkList(catalog, component, errors, warnings);
            }
        }
        if (!errors.isEmpty()) {
            throw new EditException(String.join("; ", errors) + ". Nothing was placed. Use block_info to look blocks up.");
        }
        return new ArrayList<>(warnings);
    }

    private static void checkList(BlockCatalog catalog, String list, Set<String> errors, Set<String> warnings) {
        for (BlockSyntax.Token token : BlockSyntax.tokens(list)) {
            if (token.plainBlock()) checkBlock(catalog, token.body(), errors, warnings);
        }
    }

    /** Validates one block, e.g. {@code oak_stairs[facing=east]}; used for single-block ops too. */
    public static void checkBlock(BlockCatalog catalog, String block, Set<String> errors, Set<String> warnings) {
        BlockState state;
        try {
            state = BlockState.parse(block);
        } catch (EditException e) {
            errors.add(e.getMessage());
            return;
        }
        Optional<BlockInfo> info = catalog.info(state.type());
        if (info.isEmpty()) {
            List<String> closest = catalog.suggest(state.type(), 4);
            errors.add("unknown block '" + state.type() + "' on " + catalog.describe()
                    + (closest.isEmpty() ? "" : ". Closest: " + String.join(", ", closest)));
            return;
        }
        BlockInfo b = info.get();
        state.props().forEach((name, value) -> {
            List<String> allowed = b.properties().get(name);
            if (allowed != null) {
                if (!allowed.contains(value)) {
                    errors.add(b.type() + ": '" + value + "' is not a value of " + name + " (" + String.join(", ", allowed) + ")");
                }
            } else if (!b.ignored().contains(name)) { // ignored = valid modern property this server cannot store
                String known = b.properties().isEmpty() ? "it has no properties" : "properties: " + String.join(", ", b.properties().keySet());
                if (catalog.strictProperties()) {
                    errors.add(b.type() + " has no property '" + name + "' (" + known + ")");
                } else {
                    warnings.add(b.type() + ": unknown property '" + name + "' was ignored (" + known + ")");
                }
            }
        });
    }
}
