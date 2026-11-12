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
        assertEquals("Usage: /aibuilder asset save <name> [description]", usage("asset"));
        assertEquals("Usage: /aibuilder asset save <name> [description]", usage("asset save"));
        assertEquals("Usage: /aibuilder asset save <name> [description]", usage("asset list window"));
        assertEquals("name must be 1-40 of a-z, 0-9, _ and -", usage("asset save Oak_Window"), "the same names as save_blueprint");
        assertEquals("The description is longer than 1000 characters.", usage("asset save window " + "x".repeat(1001)));
    }

    @Test
    void handlesOnlyItsOwnSubcommands() {
        assertEquals(java.util.Set.of("link", "unlink", "asset"), AccountCommands.COMMANDS);
        assertThrows(IllegalArgumentException.class, () -> parse("mark home"));
    }
}
