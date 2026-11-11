package me.abdiskiosk.minecraftaibuilder.panel;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** {@code java -jar AIBuilderPanel.jar [--config panel.json] <command>}. */
public final class PanelApp {

    private static final String USAGE = """
            usage: java -jar AIBuilderPanel.jar [--config panel.json] [command]

              serve                      run the panel (default)
              set-password               set the web UI password (asks, or reads one line from stdin)
              add-server <id> [name]     register a Minecraft server and print its relay settings
              remove-server <id>
              add-client [label]         make a token for Claude Code and scripts
              remove-client <label>      revoke a client token
              list                       show servers and client tokens

            A running panel picks up changes to panel.json within a few seconds.""";

    private PanelApp() {
    }

    public static void main(String[] argv) throws Exception {
        List<String> args = new ArrayList<>(Arrays.asList(argv));
        Path file = Path.of("panel.json");
        int c = args.indexOf("--config");
        if (c >= 0) {
            if (c + 1 >= args.size()) fail("--config needs a file");
            file = Path.of(args.get(c + 1));
            args.remove(c + 1);
            args.remove(c);
        }
        String command = args.isEmpty() ? "serve" : args.remove(0);
        PanelConfig config = PanelConfig.load(file);
        switch (command) {
            case "serve" -> serve(config);
            case "set-password" -> setPassword(config);
            case "add-server" -> addServer(config, args);
            case "remove-server" -> removeServer(config, args);
            case "add-client" -> addClient(config, args);
            case "remove-client" -> removeClient(config, args);
            case "list" -> list(config);
            case "help", "-h", "--help" -> System.out.println(USAGE);
            default -> fail("unknown command '" + command + "'\n\n" + USAGE);
        }
    }

    private static void serve(PanelConfig config) throws InterruptedException, IOException {
        if (!java.nio.file.Files.exists(config.file())) config.save();
        PanelServer server = new PanelServer(config).start(config.bind, config.port);
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            stop.countDown();
        }));
        stop.await();
    }

    private static void setPassword(PanelConfig config) throws IOException {
        String password;
        Console console = System.console();
        if (console != null) {
            char[] a = console.readPassword("New web UI password: ");
            char[] b = console.readPassword("Again: ");
            if (a == null || b == null || !Arrays.equals(a, b)) fail("passwords do not match");
            password = new String(a);
        } else {
            password = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        }
        if (password == null || password.length() < 10) fail("use at least 10 characters");
        config.passwordHash = Auth.hashPassword(password);
        config.save();
        System.out.println("Password saved in " + config.file());
    }

    private static void addServer(PanelConfig config, List<String> args) throws IOException {
        if (args.isEmpty()) fail("usage: add-server <id> [name]");
        String id;
        try {
            id = PanelConfig.checkServerId(args.get(0));
        } catch (IllegalArgumentException e) {
            fail(e.getMessage());
            return;
        }
        String token = Auth.newToken("aibs_");
        PanelConfig.Server s = config.servers.getOrDefault(id, new PanelConfig.Server());
        boolean existed = config.servers.containsKey(id);
        s.name = args.size() > 1 ? String.join(" ", args.subList(1, args.size())) : s.name == null ? id : s.name;
        s.tokenHash = Auth.sha256(token);
        config.servers.put(id, s);
        config.save();
        System.out.println((existed ? "New token for server '" : "Added server '") + id + "'. In plugins/AIBuilder/config.yml on that server:");
        System.out.println();
        System.out.println("relay:");
        System.out.println("  url: \"wss://<your panel host>\"");
        System.out.println("  token: \"" + token + "\"");
        System.out.println();
        System.out.println("Then restart the server (or reload the plugin). Claude Code and scripts reach it at https://<your panel host>/s/" + id);
        if (existed) System.out.println("The old token no longer works.");
    }

    private static void removeServer(PanelConfig config, List<String> args) throws IOException {
        if (args.isEmpty()) fail("usage: remove-server <id>");
        if (config.servers.remove(args.get(0)) == null) fail("no server '" + args.get(0) + "'");
        config.save();
        System.out.println("Removed server '" + args.get(0) + "'.");
    }

    private static void addClient(PanelConfig config, List<String> args) throws IOException {
        String token = Auth.newToken("aibc_");
        PanelConfig.Client client = new PanelConfig.Client();
        client.label = args.isEmpty() ? "client " + (config.clients.size() + 1) : String.join(" ", args);
        client.tokenHash = Auth.sha256(token);
        config.clients.add(client);
        config.save();
        System.out.println("Client token '" + client.label + "' (shown once). For each server <id>:");
        System.out.println();
        System.out.println("export AIBUILDER_URL=https://<your panel host>/s/<id> AIBUILDER_TOKEN=" + token);
        System.out.println();
        System.out.println("The repo's mcp.json and the Python SDK use these two variables as they are.");
    }

    private static void removeClient(PanelConfig config, List<String> args) throws IOException {
        if (args.isEmpty()) fail("usage: remove-client <label>");
        String label = String.join(" ", args);
        if (!config.clients.removeIf(c -> label.equals(c.label))) fail("no client token '" + label + "'");
        config.save();
        System.out.println("Revoked client token '" + label + "'.");
    }

    private static void list(PanelConfig config) {
        System.out.println("Config: " + config.file().toAbsolutePath());
        System.out.println("Listens on " + config.bind + ":" + config.port + (config.passwordHash.isEmpty() ? " (no web UI password yet)" : ""));
        System.out.println("Servers:");
        if (config.servers.isEmpty()) System.out.println("  (none; add-server <id>)");
        config.servers.forEach((id, s) -> System.out.println("  " + id + "  " + s.name));
        System.out.println("Client tokens:");
        if (config.clients.isEmpty()) System.out.println("  (none; add-client)");
        config.clients.forEach(cl -> System.out.println("  " + cl.label));
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(2);
    }
}
