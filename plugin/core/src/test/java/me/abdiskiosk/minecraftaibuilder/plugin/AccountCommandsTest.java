package me.abdiskiosk.minecraftaibuilder.plugin;

import me.abdiskiosk.minecraftaibuilder.common.EditException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What players type after /aibuilder for linking and in-game saves; RelayClientTest covers what then happens. */
class AccountCommandsTest {

    private static AccountCommands.Request parse(String line) {
        return AccountCommands.parse(line.split(" "));
    }

    private static String usage(String line) {
        return assertThrows(EditException.class, () -> parse(line)).getMessage();
    }

    @Test
    void linkTakesTheCodeAsTyped() {
        assertEquals(new AccountCommands.Link("ABCD-EFGH"), parse("link ABCD-EFGH"));
        assertEquals(new AccountCommands.Link("abcdefgh"), parse("LINK abcd efgh"), "a space inside the code is fine");
        assertTrue(usage("link").startsWith("Usage: /aibuilder link <code>"));
        assertTrue(usage("link ab$cd").startsWith("Usage: /aibuilder link <code>"));
        assertTrue(usage("link " + "A".repeat(33)).startsWith("Usage: /aibuilder link <code>"));
    }

    @Test
    void unlinkTakesNothing() {
        assertEquals(new AccountCommands.Unlink(), parse("unlink"));
        assertEquals("Usage: /aibuilder unlink", usage("unlink me"));
    }

    @Test
    void assetSaveTakesANameAndAnOptionalDescription() {
        assertEquals(new AccountCommands.Save("oak-window", ""), parse("asset save oak-window"));
        assertEquals(new AccountCommands.Save("oak-window", "two panes, spruce frame"), parse("Asset SAVE oak-window two panes, spruce frame"));
        String usage = "Usage: /aibuilder asset save <name> [description], or /aibuilder asset list";
        assertEquals(usage, usage("asset"));
        assertEquals(usage, usage("asset save"));
        assertEquals(usage, usage("asset list window"));
        assertEquals(usage, usage("asset delete window"));
        assertEquals("An asset name is 1-40 of a-z, 0-9, _ and -, such as oak-window.", usage("asset save Oak_Window"),
                "the same names as save_blueprint");
        assertEquals("The description is longer than 1000 characters.", usage("asset save window " + "x".repeat(1001)));
    }

    @Test
    void assetListTakesNothing() {
        assertEquals(new AccountCommands.ListAssets(), parse("asset list"));
        assertEquals(new AccountCommands.ListAssets(), parse("ASSET List"));
    }

    @Test
    void theListShowsOwnThenSharedAssetsInAFewLines() {
        assertTrue(AccountCommands.listing(json("{\"assets\":[]}")).startsWith("Your asset library is empty. Save a WorldEdit selection"));
        assertEquals("Your asset library:\n  window  3x4x1 - oak window, two panes\n  tower  9x30x9  shared by sam",
                AccountCommands.listing(json("{\"assets\":[{\"name\":\"window\",\"size\":[3,4,1],\"description\":\"oak window,\\ntwo panes\"},"
                        + "{\"name\":\"tower\",\"size\":[9,30,9],\"description\":\"\",\"by\":\"sam\"}]}")));

        StringBuilder many = new StringBuilder("{\"assets\":[");
        for (int i = 0; i < AssetLibrary.MAX_LISTED; i++) {
            many.append(i == 0 ? "" : ",").append("{\"name\":\"a").append(i).append("\",\"size\":[1,1,1],\"description\":\"").append("x".repeat(60)).append("\"}");
        }
        String[] lines = AccountCommands.listing(json(many.append("]}").toString())).split("\n");
        assertEquals(AccountCommands.LIST_LINES + 2, lines.length, "at most a screen of chat");
        assertEquals("Your asset library (first 30):", lines[0]);
        assertEquals("  a0  1x1x1 - " + "x".repeat(37) + "...", lines[1], "long descriptions are cut");
        assertEquals("  ...and more; the panel's Assets page has them all.", lines[lines.length - 1]);
        assertThrows(EditException.class, () -> AccountCommands.listing(json("{}")));
    }

    @Test
    void reasonsReachThePlayerAsOneSentence() {
        assertEquals("This panel does not support account linking yet; ask its owner to update it.",
                AccountCommands.sentence("this panel does not support account linking yet; ask its owner to update it"));
        assertEquals("Your library is full: 50 of 50 assets. Delete some to save more.",
                AccountCommands.sentence("your library is full: 50 of 50 assets. Delete some to save more."));
        assertEquals("That failed; try again in a moment.", AccountCommands.sentence(null));
    }

    private static com.google.gson.JsonObject json(String text) {
        return com.google.gson.JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void handlesOnlyItsOwnSubcommands() {
        assertEquals(java.util.Set.of("link", "unlink", "asset"), AccountCommands.COMMANDS);
        assertThrows(IllegalArgumentException.class, () -> parse("mark home"));
    }
}
