package me.abdiskiosk.minecraftaibuilder.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LinkCodeMaskTest {
    @Test
    void hidesTheCodeOfALinkCommand() {
        assertEquals("Steve issued server command: /aibuilder link ********",
                LinkCodeMask.masked("Steve issued server command: /aibuilder link ABCD-EFGH"));
        assertEquals("Steve issued server command: /AIBuilder:aibuilder LINK ********",
                LinkCodeMask.masked("Steve issued server command: /AIBuilder:aibuilder LINK abcd efgh"));
        assertEquals("[12:00:00 INFO]: Steve issued server command: /aibuilder  link ********",
                LinkCodeMask.masked("[12:00:00 INFO]: Steve issued server command: /aibuilder  link X"));
    }

    @Test
    void leavesEveryOtherLineAlone() {
        for (String line : new String[]{
                "Steve issued server command: /aibuilder unlink",
                "Steve issued server command: /aibuilder link",
                "Steve issued server command: /aibuilder asset save link",
                "Steve issued server command: /tell Alex /aibuilder link ABCD",
                "Steve issued server command: /aibuilderx link ABCD",
                "Steve: /aibuilder link ABCD-EFGH",
                "",
                null}) {
            assertNull(LinkCodeMask.masked(line), String.valueOf(line));
        }
    }
}
