package me.abdiskiosk.minecraftaibuilder.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.StateArrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AssetLibraryTest {
    @TempDir Path dir;
    private HttpServer storage;
    private final byte[] data = StateArrays.encode(new String[]{"oak_stairs[facing=east]", "air", "stone", "air"});
    private final AtomicInteger downloads = new AtomicInteger(), requests = new AtomicInteger();
    private final AtomicBoolean allowed = new AtomicBoolean(true);

    @AfterEach void stop() { if (storage != null) storage.stop(0); }

    private JsonObject metadata() throws Exception {
        JsonObject meta = new JsonObject();
        meta.addProperty("id", "abcdefgh2345");
        meta.addProperty("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
        meta.addProperty("bytes", data.length);
        JsonArray size = new JsonArray(); size.add(2); size.add(2); size.add(1); meta.add("size", size);
        meta.addProperty("url", "http://127.0.0.1:" + storage.getAddress().getPort() + "/asset");
        return meta;
    }

    private AssetLibrary library() throws Exception {
        storage = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        storage.createContext("/asset", exchange -> {
            downloads.incrementAndGet();
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"), "no panel credentials go to storage");
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data); exchange.close();
        });
        storage.start();
        JsonObject meta = metadata();
        return new AssetLibrary(dir, (op, args) -> {
            assertEquals("get", op);
            assertEquals("window", args.get("name").getAsString());
            requests.incrementAndGet();
            if (!allowed.get()) throw new EditException("no asset in your library");
            return meta;
        });
    }

    private <T> T as(long user, java.util.function.Supplier<T> work) {
        return CallContext.withIdentity(new CallContext.Identity(user, "a".repeat(64)), work);
    }

    @Test void cacheIsScopedByOwnerAndNeverBypassesAuthorization() throws Exception {
        AssetLibrary library = library();
        var blueprint = as(1, () -> library.load("window", 100));
        assertEquals(4, blueprint.cellCount(), "saved air is preserved");
        assertTrue(blueprint.palette().containsValue("oak_stairs[facing=east]"));
        as(1, () -> library.load("window", 100));
        assertEquals(1, downloads.get(), "reuse validated cached bytes");
        assertEquals(2, requests.get(), "still resolve/authorize every paste");
        as(2, () -> library.load("window", 100));
        assertEquals(2, downloads.get(), "accounts do not share cache paths");
        assertTrue(Files.isDirectory(dir.resolve("asset-cache/1")));
        assertTrue(Files.isDirectory(dir.resolve("asset-cache/2")));
        allowed.set(false);
        assertThrows(EditException.class, () -> as(1, () -> library.load("window", 100)));
        assertNull(CallContext.identity());
    }

    @Test void corruptCacheIsReplacedButOversizedDimensionsDoNotDownload() throws Exception {
        AssetLibrary library = library();
        as(1, () -> library.load("window", 100));
        Path cacheFile;
        try (var files = Files.list(dir.resolve("asset-cache/1"))) { cacheFile = files.findFirst().orElseThrow(); }
        Files.write(cacheFile, new byte[data.length]);
        as(1, () -> library.load("window", 100));
        assertEquals(2, downloads.get());
        assertArrayEquals(data, Files.readAllBytes(cacheFile));
        assertThrows(EditException.class, () -> as(1, () -> library.load("window", 3)));
        assertEquals(2, downloads.get());
    }

    @Test void badChecksumsAndCachePathsAreRefused() throws Exception {
        library();
        JsonObject meta = metadata(); meta.addProperty("sha256", "0".repeat(64));
        AssetLibrary library = new AssetLibrary(dir, (op, args) -> meta);
        assertThrows(EditException.class, () -> as(1, () -> library.load("window", 100)));
        assertTrue(!Files.exists(dir.resolve("asset-cache")));
        meta.addProperty("id", "../../outside");
        assertThrows(EditException.class, () -> as(1, () -> library.load("window", 100)));
        assertEquals(1, downloads.get());
        assertThrows(EditException.class, () -> library.load("window", 100));
    }

    @Test void oversizedHttpBodyIsStoppedBeforeItCanBeCached() throws Exception {
        library();
        JsonObject meta = metadata(); meta.addProperty("bytes", data.length - 1);
        AssetLibrary library = new AssetLibrary(dir, (op, args) -> meta);
        assertThrows(EditException.class, () -> as(1, () -> library.load("window", 100)));
        assertTrue(!Files.exists(dir.resolve("asset-cache")));
    }

    @Test void malformedMetadataIsRefusedWithoutEchoingTheUrl() throws Exception {
        library();
        for (String field : new String[]{"id", "sha256", "url", "size", "bytes"}) {
            JsonObject meta = metadata(); meta.remove(field);
            AssetLibrary library = new AssetLibrary(dir, (op, args) -> meta);
            EditException e = assertThrows(EditException.class, () -> as(1, () -> library.load("window", 100)), field);
            assertEquals("the panel sent invalid asset metadata", e.getMessage(), field);
        }
        JsonObject wrongTypes = metadata(); wrongTypes.add("size", new JsonObject()); wrongTypes.addProperty("bytes", "many");
        assertThrows(EditException.class, () -> as(1, () -> new AssetLibrary(dir, (op, args) -> wrongTypes).load("window", 100)));
        for (String sha : new String[]{"../" + "0".repeat(61), "A".repeat(64), "0".repeat(63)}) {
            JsonObject meta = metadata(); meta.addProperty("sha256", sha);
            assertThrows(EditException.class, () -> as(1, () -> new AssetLibrary(dir, (op, args) -> meta).load("window", 100)), sha);
        }
        String secret = "http://127.0.0.1:" + storage.getAddress().getPort() + "/missing?X-Amz-Signature=secret";
        JsonObject gone = metadata(); gone.addProperty("url", secret);
        EditException e = assertThrows(EditException.class, () -> as(1, () -> new AssetLibrary(dir, (op, args) -> gone).load("window", 100)));
        assertFalse(e.getMessage().contains("secret"), e.getMessage());
        JsonObject bad = metadata(); bad.addProperty("url", "file:///etc/passwd?secret");
        e = assertThrows(EditException.class, () -> as(1, () -> new AssetLibrary(dir, (op, args) -> bad).load("window", 100)));
        assertEquals("invalid asset download URL", e.getMessage());
        JsonObject unparsable = metadata(); unparsable.addProperty("url", "http://exa mple/secret");
        e = assertThrows(EditException.class, () -> as(1, () -> new AssetLibrary(dir, (op, args) -> unparsable).load("window", 100)));
        assertEquals("invalid asset download URL", e.getMessage());
        assertEquals(0, downloads.get());
        assertTrue(!Files.exists(dir.resolve("asset-cache/1")) || Files.list(dir.resolve("asset-cache/1")).noneMatch(p -> p.toString().endsWith(".bin")));
    }

    /** The panel answers `get` for a name with whichever asset that caller may use now: own, shared, or a replacement. */
    @Test void cacheIsKeyedByAssetIdAndChecksumAndEveryPasteIsReauthorized() throws Exception {
        byte[] oak = data, spruce = StateArrays.encode(new String[]{"spruce_planks", "air", "stone", "air"});
        storage = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (var e : java.util.Map.of("/oak", oak, "/spruce", spruce).entrySet()) {
            storage.createContext(e.getKey(), exchange -> {
                downloads.incrementAndGet();
                exchange.sendResponseHeaders(200, e.getValue().length);
                exchange.getResponseBody().write(e.getValue()); exchange.close();
            });
        }
        storage.start();
        var answer = new java.util.concurrent.atomic.AtomicReference<JsonObject>();
        AssetLibrary library = new AssetLibrary(dir, (op, args) -> {
            requests.incrementAndGet();
            if (answer.get() == null) throw new EditException("no asset 'window' in your library or shared with this server");
            return answer.get();
        });
        java.util.function.BiFunction<String, byte[], JsonObject> asset = (id, bytes) -> {
            try {
                JsonObject meta = metadata();
                meta.addProperty("id", id);
                meta.addProperty("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                meta.addProperty("bytes", bytes.length);
                meta.addProperty("url", "http://127.0.0.1:" + storage.getAddress().getPort() + (bytes == oak ? "/oak" : "/spruce"));
                return meta;
            } catch (Exception e) { throw new IllegalStateException(e); }
        };

        // User 2 pastes owner 1's 'window', shared with this server; the file is named by asset id and checksum.
        answer.set(asset.apply("abcdefgh2345", oak));
        assertTrue(as(2, () -> library.load("window", 100)).palette().containsValue("oak_stairs[facing=east]"));
        assertEquals(1, downloads.get());
        String oakSha = answer.get().get("sha256").getAsString();
        assertTrue(Files.isRegularFile(dir.resolve("asset-cache/2/abcdefgh2345-" + oakSha + ".bin")));

        // The owner replaces it under the same name (a new asset id): the old bytes are never used.
        answer.set(asset.apply("zyxwvuts2345", spruce));
        assertTrue(as(2, () -> library.load("window", 100)).palette().containsValue("spruce_planks"));
        assertEquals(2, downloads.get());

        // Same id with changed content (another checksum): downloaded again, not served from the cache.
        answer.set(asset.apply("abcdefgh2345", spruce));
        assertTrue(as(2, () -> library.load("window", 100)).palette().containsValue("spruce_planks"));
        assertEquals(3, downloads.get());

        // Unshared or deleted: the panel refuses, and the cached file is not used.
        answer.set(null);
        int before = requests.get();
        EditException e = assertThrows(EditException.class, () -> as(2, () -> library.load("window", 100)));
        assertTrue(e.getMessage().contains("shared with this server"), e.getMessage());
        assertEquals(before + 1, requests.get());
        assertEquals(3, downloads.get());
    }

    @Test void listingIsValidatedAndCapped() {
        JsonArray many = new JsonArray();
        for (int i = 0; i < 50; i++) { JsonObject a = new JsonObject(); a.addProperty("name", "a" + i); many.add(a); }
        JsonObject answer = new JsonObject(); answer.add("assets", many);
        assertEquals(AssetLibrary.MAX_LISTED, new AssetLibrary(dir, (op, args) -> answer).list().size());
        assertThrows(EditException.class, () -> new AssetLibrary(dir, (op, args) -> new JsonObject()).list());
        assertTrue(new AssetLibrary(dir, (op, args) -> answer).available());
        assertFalse(new AssetLibrary(dir, (op, args) -> answer, () -> false).available());
    }

    @Test void nestedIdentityIsRestoredEvenOnFailure() {
        as(7, () -> {
            assertThrows(EditException.class, () -> as(8, () -> { throw new EditException("failed"); }));
            assertEquals(7, CallContext.identity().user());
            return null;
        });
        assertNull(CallContext.identity());
    }
}
