package me.abdiskiosk.minecraftaibuilder.panel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code panel.json}: where to listen, the admin password and the tokens. Only SHA-256 hashes of tokens are stored;
 * the CLI prints each token once when it is made.
 */
public final class PanelConfig {

    private static final Pattern SERVER_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    public static final class Server {
        public String name;
        public String tokenHash;
    }

    public static final class Client {
        public String label;
        public String tokenHash;
    }

    public String bind = "127.0.0.1";
    public int port = 8080;
    /** PBKDF2 hash from {@link Auth#hashPassword}; empty = the web UI refuses every login. */
    public String passwordHash = "";
    public Map<String, Server> servers = new LinkedHashMap<>();
    /** Tokens for Claude Code and scripts ({@code /s/<id>/mcp}, {@code /s/<id>/api/...}). */
    public List<Client> clients = new ArrayList<>();

    private transient Path file;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static PanelConfig load(Path file) throws IOException {
        PanelConfig c;
        if (Files.exists(file)) {
            c = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), PanelConfig.class);
            if (c == null) c = new PanelConfig();
            if (c.servers == null) c.servers = new LinkedHashMap<>();
            if (c.clients == null) c.clients = new ArrayList<>();
            if (c.passwordHash == null) c.passwordHash = "";
        } else {
            c = new PanelConfig();
        }
        c.file = file;
        return c;
    }

    public synchronized void save() throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // not a POSIX file system
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public Path file() {
        return file;
    }

    public static String checkServerId(String id) {
        if (id == null || !SERVER_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("server id must be 1-32 characters: a-z, 0-9, _ and -, starting with a letter or digit");
        }
        return id;
    }

    /** The server whose token hashes to {@code hash}, or null. */
    public synchronized String serverForTokenHash(String hash) {
        for (Map.Entry<String, Server> e : servers.entrySet()) {
            if (Auth.equalsConstantTime(e.getValue().tokenHash, hash)) return e.getKey();
        }
        return null;
    }

    public synchronized boolean isClientTokenHash(String hash) {
        boolean ok = false;
        for (Client c : clients) ok |= Auth.equalsConstantTime(c.tokenHash, hash);
        return ok;
    }
}
