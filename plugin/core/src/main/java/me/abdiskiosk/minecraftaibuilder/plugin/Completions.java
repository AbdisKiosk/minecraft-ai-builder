package me.abdiskiosk.minecraftaibuilder.plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Tab completion for {@code /aibuilder}: only what the sender may run, and an empty list (never Bukkit's player
 * names) where a free-form name, code or text goes.
 */
public final class Completions {

    private Completions() {
    }

    /**
     * @param args        the words typed so far after {@code /aibuilder}, the last one possibly empty
     * @param player      whether the sender is a player (link, unlink, asset, mark and note need one)
     * @param has         the sender's permissions
     * @param markers     marker names {@code unmark} can remove
     * @param draftWorlds worlds with an open draft
     */
    public static List<String> complete(String[] args, boolean player, Predicate<String> has,
                                        Supplier<Collection<String>> markers, Supplier<Collection<String>> draftWorlds) {
        if (args.length == 0) return List.of();
        boolean admin = has.test("aibuilder.admin");
        boolean asset = player && has.test(AccountCommands.PERMISSION);
        boolean approve = admin && has.test("aibuilder.approve");
        String sub = args[0].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        switch (args.length) {
            case 1 -> {
                if (admin) options.addAll(List.of("stats", "unmark", "marks"));
                if (admin && player) options.addAll(List.of("mark", "note"));
                if (approve) options.add("draft");
                if (asset) options.addAll(List.of("link", "unlink", "asset"));
            }
            case 2 -> {
                if (sub.equals("asset") && asset) options.addAll(List.of("save", "list"));
                if (sub.equals("stats") && admin) options.add("reset");
                if (sub.equals("unmark") && admin) options.addAll(markers.get());
                if (sub.equals("draft") && approve) options.addAll(List.of("approve", "reject"));
            }
            case 3, 4 -> {
                String action = args[1].toLowerCase(Locale.ROOT);
                if (sub.equals("draft") && approve && (action.equals("approve") || action.equals("reject"))) {
                    if (args.length == 3) options.addAll(draftWorlds.get());
                    if (action.equals("approve") && (args.length == 3 || !args[2].equalsIgnoreCase("force"))) options.add("force");
                }
            }
            default -> {
            }
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        TreeSet<String> out = new TreeSet<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(typed)) out.add(o);
        return new ArrayList<>(out);
    }
}
