package me.abdiskiosk.minecraftaibuilder.panel;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

/** Mojang's version manifest, one version file and its client jar, served locally. */
final class FakeMojang implements AutoCloseable {
    final HttpServer server;
    final byte[] jar;
    final AtomicInteger jarDownloads = new AtomicInteger();
    String sha1;

    FakeMojang(String version, byte[] jar) throws Exception {
        this.jar = jar;
        this.sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(jar));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/manifest.json", ex -> send(ex, ("{\"versions\":[{\"id\":\"" + version + "\",\"url\":\"" + base
                + "/version.json\"}]}").getBytes(StandardCharsets.UTF_8)));
        server.createContext("/version.json", ex -> send(ex, ("{\"downloads\":{\"client\":{\"url\":\"" + base
                + "/client.jar\",\"sha1\":\"" + sha1 + "\"}}}").getBytes(StandardCharsets.UTF_8)));
        server.createContext("/client.jar", ex -> {
            jarDownloads.incrementAndGet();
            send(ex, jar);
        });
        server.start();
    }

    URI manifest() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/manifest.json");
    }

    private static void send(com.sun.net.httpserver.HttpExchange ex, byte[] body) throws IOException {
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
