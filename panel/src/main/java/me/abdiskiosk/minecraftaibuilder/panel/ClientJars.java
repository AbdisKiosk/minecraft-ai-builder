package me.abdiskiosk.minecraftaibuilder.panel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minecraft client jars for the UI's 3D view, which takes block models and textures from them. Each version is
 * downloaded from Mojang once, checked against Mojang's SHA-1 and kept in a cache folder beside {@code panel.json}.
 */
final class ClientJars {

    static final URI MOJANG_MANIFEST = URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}");
    private static final Pattern LEGACY = Pattern.compile("1\\.(\\d+)(\\..*)?");
    /** The oldest client with flattened block names, which servers before 1.13 report too (see LegacyBlockTable). */
    static final String FIRST_FLAT = "1.13.2";
    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    private final Path dir;
    private final URI manifest;
    private final HttpClient http;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    ClientJars(Path dir, URI manifest, HttpClient http) {
        this.dir = dir;
        this.manifest = manifest;
        this.http = http;
    }

    /** Thrown for a version Mojang does not list; the message is meant for the UI. */
    static final class UnknownVersion extends IOException {
        UnknownVersion(String message) {
            super(message);
        }
    }

    /**
     * The jar for a server's reported version, such as {@code 1.21.11}, {@code 1.12.2} or {@code 26.3.build.248},
     * downloading it on first use.
     */
    Path get(String reported) throws IOException, InterruptedException {
        if (!VERSION.matcher(reported).matches()) throw new UnknownVersion("not a Minecraft version: " + reported);
        String version = clientVersion(reported);
        Path jar = dir.resolve(version + ".jar");
        if (Files.isRegularFile(jar)) return jar;
        synchronized (locks.computeIfAbsent(version, v -> new Object())) {
            if (Files.isRegularFile(jar)) return jar;
            download(version, jar);
            return jar;
        }
    }

    /** The client version to use for a server's reported version. */
    static String clientVersion(String reported) {
        Matcher legacy = LEGACY.matcher(reported);
        if (legacy.matches() && Integer.parseInt(legacy.group(1)) < 13) return FIRST_FLAT;
        // Paper's builds report e.g. 26.3.build.248.
        return reported.replaceFirst("\\.build\\..*$", "");
    }

    private void download(String version, Path jar) throws IOException, InterruptedException {
        JsonObject versions = json(manifest);
        URI versionUrl = null;
        for (JsonElement v : versions.getAsJsonArray("versions")) {
            JsonObject o = v.getAsJsonObject();
            if (o.get("id").getAsString().equals(version)) versionUrl = URI.create(o.get("url").getAsString());
        }
        if (versionUrl == null) throw new UnknownVersion("Mojang lists no Minecraft " + version);
        JsonObject client = json(versionUrl).getAsJsonObject("downloads").getAsJsonObject("client");
        String sha1 = client.get("sha1").getAsString();

        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, version, ".part");
        try {
            HttpResponse<InputStream> r = http.send(request(URI.create(client.get("url").getAsString())),
                    HttpResponse.BodyHandlers.ofInputStream());
            MessageDigest digest = sha1();
            try (InputStream in = r.body()) {
                check(r);
                try (var out = Files.newOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) {
                        digest.update(buf, 0, n);
                        out.write(buf, 0, n);
                    }
                }
            }
            String got = HexFormat.of().formatHex(digest.digest());
            if (!got.equalsIgnoreCase(sha1)) throw new IOException("client " + version + " has SHA-1 " + got + ", Mojang says " + sha1);
            Files.move(tmp, jar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private JsonObject json(URI uri) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(request(uri), HttpResponse.BodyHandlers.ofString());
        check(r);
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static HttpRequest request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(TIMEOUT).build();
    }

    private static void check(HttpResponse<?> r) throws IOException {
        if (r.statusCode() != 200) throw new IOException(r.uri() + " answered " + r.statusCode());
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
