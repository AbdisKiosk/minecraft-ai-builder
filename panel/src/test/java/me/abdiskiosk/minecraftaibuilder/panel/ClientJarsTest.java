package me.abdiskiosk.minecraftaibuilder.panel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientJarsTest {

    @TempDir
    Path dir;

    @Test
    void mapsServerVersionsToClientVersions() {
        assertEquals("1.21.11", ClientJars.clientVersion("1.21.11"));
        assertEquals("26.3", ClientJars.clientVersion("26.3.build.248"));
        assertEquals(ClientJars.FIRST_FLAT, ClientJars.clientVersion("1.12.2"));
        assertEquals(ClientJars.FIRST_FLAT, ClientJars.clientVersion("1.8"));
        assertEquals("1.13", ClientJars.clientVersion("1.13"));
    }

    @Test
    void downloadsOnceAndKeepsTheJar() throws Exception {
        byte[] bytes = "PK fake jar".getBytes();
        try (FakeMojang mojang = new FakeMojang("1.21.11", bytes)) {
            ClientJars jars = new ClientJars(dir, mojang.manifest(), HttpClient.newHttpClient());
            Path jar = jars.get("1.21.11");
            assertArrayEquals(bytes, Files.readAllBytes(jar));
            assertEquals(jar, jars.get("1.21.11"));
            assertEquals(1, mojang.jarDownloads.get());
        }
    }

    @Test
    void refusesAJarWithTheWrongChecksum() throws Exception {
        try (FakeMojang mojang = new FakeMojang("1.21.11", "PK fake jar".getBytes())) {
            mojang.sha1 = "0".repeat(40);
            ClientJars jars = new ClientJars(dir, mojang.manifest(), HttpClient.newHttpClient());
            IOException e = assertThrows(IOException.class, () -> jars.get("1.21.11"));
            assertTrue(e.getMessage().contains("SHA-1"), e.getMessage());
            try (var files = Files.list(dir)) {
                assertFalse(files.findAny().isPresent(), "nothing is left in the cache");
            }
        }
    }

    @Test
    void unknownAndMalformedVersions() throws Exception {
        try (FakeMojang mojang = new FakeMojang("1.21.11", new byte[1])) {
            ClientJars jars = new ClientJars(dir, mojang.manifest(), HttpClient.newHttpClient());
            assertThrows(ClientJars.UnknownVersion.class, () -> jars.get("1.99"));
            assertThrows(ClientJars.UnknownVersion.class, () -> jars.get("../panel"));
            assertEquals(0, mojang.jarDownloads.get());
        }
    }
}
