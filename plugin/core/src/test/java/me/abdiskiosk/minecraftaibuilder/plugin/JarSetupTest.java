package me.abdiskiosk.minecraftaibuilder.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarSetupTest {

    private static final String CONFIG = """
            # AIBuilder
            relay:
              # The panel to connect out to.
              url: ""
              # Shown once.
              token: ""

            limits:
              blocks-per-tick: 4000
            """;

    private static InputStream setup(String url, String token) {
        return new ByteArrayInputStream(("# key\nurl=" + url + "\ntoken=" + token + "\n").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void setsUrlAndTokenAndKeepsTheRest() {
        String out = JarSetup.withRelay(CONFIG, "wss://panel.example.com", "aibs_abc");
        assertEquals(CONFIG.replace("url: \"\"", "url: \"wss://panel.example.com\"").replace("token: \"\"", "token: \"aibs_abc\""), out);
    }

    @Test
    void addsMissingKeysAndSections() {
        assertEquals("relay:\n  url: \"u\"\n  token: \"t\"\nlimits: {}\n", JarSetup.withRelay("relay:\nlimits: {}\n", "u", "t"));
        assertEquals("a: 1\nrelay:\n  url: \"u\"\n  token: \"t\"\n", JarSetup.withRelay("a: 1", "u", "t"));
    }

    @Test
    void appliesOnceSoLaterEditsWin(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("config.yml");
        Files.writeString(config, CONFIG);
        assertTrue(JarSetup.apply(setup("wss://p", "aibs_1"), dir));
        assertTrue(Files.readString(config).contains("token: \"aibs_1\""));

        Files.writeString(config, Files.readString(config).replace("aibs_1", "aibs_by_hand"));
        assertFalse(JarSetup.apply(setup("wss://p", "aibs_1"), dir));
        assertTrue(Files.readString(config).contains("aibs_by_hand"));

        // A jar with a new key (downloaded after the old one was replaced) is applied again.
        assertTrue(JarSetup.apply(setup("wss://p", "aibs_2"), dir));
        assertTrue(Files.readString(config).contains("token: \"aibs_2\""));
        assertFalse(JarSetup.apply(null, dir));
    }
}
