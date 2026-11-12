package me.abdiskiosk.minecraftaibuilder.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The relay settings the panel puts inside the jar a server owner downloads ({@value #ENTRY}), so that installing the
 * jar is the whole setup. They are written into config.yml once; a marker file remembers which settings were applied,
 * so editing config.yml afterwards wins until a jar with different settings is installed.
 */
final class JarSetup {

    static final String ENTRY = "aibuilder-setup.properties";
    private static final String MARKER = ".jar-setup";
    private static final Pattern RELAY = Pattern.compile("^relay:\\s*(#.*)?$");
    private static final Pattern CHILD = Pattern.compile("^(\\s+)(url|token):.*$");

    private JarSetup() {
    }

    /** Writes the jar's settings into config.yml unless they were applied before; true when config.yml changed. */
    static boolean apply(InputStream setup, Path dataFolder) throws IOException {
        if (setup == null) return false;
        Properties p = new Properties();
        try (InputStreamReader in = new InputStreamReader(setup, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        String url = p.getProperty("url", "").trim(), token = p.getProperty("token", "").trim();
        if (url.isEmpty() || token.isEmpty()) return false;
        String stamp = sha256(url + "\n" + token);
        Path marker = dataFolder.resolve(MARKER);
        if (Files.isRegularFile(marker) && Files.readString(marker).trim().equals(stamp)) return false;
        Path config = dataFolder.resolve("config.yml");
        String yaml = Files.isRegularFile(config) ? Files.readString(config) : "";
        Files.writeString(config, withRelay(yaml, url, token));
        Files.writeString(marker, stamp + "\n");
        return true;
    }

    /** The config.yml text with relay.url and relay.token set, leaving every other line (and comment) alone. */
    static String withRelay(String yaml, String url, String token) {
        String nl = yaml.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(yaml.split("\r?\n", -1)));
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (RELAY.matcher(lines.get(i)).matches()) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            String head = yaml.isEmpty() || yaml.endsWith("\n") ? yaml : yaml + nl;
            return head + "relay:" + nl + "  url: " + quote(url) + nl + "  token: " + quote(token) + nl;
        }
        boolean sawUrl = false, sawToken = false;
        String indent = "  ";
        // The relay block runs until the next line that starts at column 0 (another key or a top-level comment).
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) break;
            Matcher m = CHILD.matcher(line);
            if (!m.matches()) continue;
            indent = m.group(1);
            boolean isUrl = m.group(2).equals("url");
            lines.set(i, indent + m.group(2) + ": " + quote(isUrl ? url : token));
            if (isUrl) sawUrl = true;
            else sawToken = true;
        }
        if (!sawToken) lines.add(start + 1, indent + "token: " + quote(token));
        if (!sawUrl) lines.add(start + 1, indent + "url: " + quote(url));
        return String.join(nl, lines);
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
