package me.abdiskiosk.minecraftaibuilder.plugin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tab completion offers only what the sender may run, and never Bukkit's player names for free text. */
class CompletionsTest {

    private static final Set<String> ALL = Set.of("aibuilder.admin", "aibuilder.approve", AccountCommands.PERMISSION);

    private static List<String> complete(boolean player, Set<String> perms, String line) {
        // Bukkit passes an empty last word after a trailing space.
        String[] args = (line + (line.endsWith(" ") ? "x" : "")).split(" ", -1);
        if (line.endsWith(" ")) args[args.length - 1] = "";
        return Completions.complete(args, player, perms::contains, () -> List.of("home", "gate"), () -> List.of("world"));
    }

    @Test
    void subcommandsFollowPermissions() {
        assertEquals(List.of("asset", "draft", "link", "mark", "marks", "note", "stats", "unlink", "unmark"), complete(true, ALL, ""));
        assertEquals(List.of("asset", "link", "unlink"), complete(true, Set.of(AccountCommands.PERMISSION), ""));
        assertEquals(List.of(), complete(true, Set.of(), ""));
        assertEquals(List.of("marks", "stats", "unmark"), complete(false, Set.of("aibuilder.admin"), ""),
                "the console gets neither player-only commands nor draft without aibuilder.approve");
        assertEquals(List.of("mark", "marks"), complete(true, ALL, "MA"));
    }

    @Test
    void secondAndThirdWords() {
        assertEquals(List.of("list", "save"), complete(true, Set.of(AccountCommands.PERMISSION), "asset "));
        assertEquals(List.of("save"), complete(true, Set.of(AccountCommands.PERMISSION), "asset s"));
        assertEquals(List.of(), complete(true, Set.of("aibuilder.admin"), "asset "), "no aibuilder.asset");
        assertEquals(List.of(), complete(true, ALL, "asset save "), "a new name is typed, not completed");
        assertEquals(List.of(), complete(true, ALL, "link "));
        assertEquals(List.of("gate", "home"), complete(true, ALL, "unmark "));
        assertEquals(List.of("reset"), complete(false, ALL, "stats "));
        assertEquals(List.of("approve", "reject"), complete(false, ALL, "draft "));
        assertEquals(List.of(), complete(false, Set.of("aibuilder.admin"), "draft "));
        assertEquals(List.of("force", "world"), complete(false, ALL, "draft approve "));
        assertEquals(List.of("world"), complete(false, ALL, "draft reject "));
        assertEquals(List.of("force"), complete(false, ALL, "draft approve world "));
        assertEquals(List.of(), complete(false, ALL, "draft approve force "));
        assertEquals(List.of(), complete(true, ALL, "note hello "));
    }
}
