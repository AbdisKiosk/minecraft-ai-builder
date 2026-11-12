package me.abdiskiosk.minecraftaibuilder.plugin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin must never listen on a port: everything reaches it over the connection it makes to the panel. This
 * fails when shipped code (any module's {@code src/main}) names a class that accepts connections.
 */
class NoOpenPortTest {

    private static final Pattern LISTENER = Pattern.compile(
            "\\b(ServerSocket|ServerSocketChannel|AsynchronousServerSocketChannel|DatagramSocket|DatagramChannel|MulticastSocket"
                    + "|HttpServer|HttpsServer|WebSocketServer|ServerBootstrap)\\b|com\\.sun\\.net\\.httpserver");

    @Test
    void shippedCodeOpensNoServerSocket() throws IOException {
        // Gradle runs tests in plugin/core; the other modules are its siblings.
        Path plugin = Path.of("").toAbsolutePath().getParent();
        List<String> found = new ArrayList<>();
        int files = 0;
        for (String module : List.of("common", "bridge-we6", "bridge-we7", "core")) {
            Path main = plugin.resolve(module).resolve("src/main/java");
            assertTrue(Files.isDirectory(main), "missing " + main);
            try (Stream<Path> walk = Files.walk(main)) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    files++;
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        if (LISTENER.matcher(lines.get(i)).find()) found.add(plugin.relativize(file) + ":" + (i + 1));
                    }
                }
            }
        }
        assertTrue(files > 20, "found only " + files + " source files");
        assertEquals(List.of(), found, "the plugin must not listen on a port");
    }
}
