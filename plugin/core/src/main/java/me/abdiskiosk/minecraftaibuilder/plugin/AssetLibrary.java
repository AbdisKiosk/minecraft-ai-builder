package me.abdiskiosk.minecraftaibuilder.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.abdiskiosk.minecraftaibuilder.common.Anchor;
import me.abdiskiosk.minecraftaibuilder.common.Blueprint;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.StateArrays;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;

import java.io.IOException;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/** Account-owned pieces, resolved through the current call's grant. Cache hits still require authorization. */
public final class AssetLibrary {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final long CACHE_BYTES = 128L * 1024 * 1024;
    private final Path cache;
    /** The most assets get_context lists; the panel sends no more, and a faulty answer cannot grow the result. */
    public static final int MAX_LISTED = 30;
    private final BiFunction<String, JsonObject, JsonObject> request;
    private final BooleanSupplier available;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public AssetLibrary(Path dir, BiFunction<String, JsonObject, JsonObject> request) {
        this(dir, request, () -> true);
    }

    /** @param available false while the panel has no library (no object storage, or an older panel) */
    public AssetLibrary(Path dir, BiFunction<String, JsonObject, JsonObject> request, BooleanSupplier available) {
        this.cache = dir.resolve("asset-cache");
        this.request = request;
        this.available = available;
    }

    public boolean available() {
        return available.getAsBoolean();
    }

    public JsonArray list() {
        JsonElement assets = request.apply("list", new JsonObject()).get("assets");
        if (assets == null || !assets.isJsonArray()) throw new EditException("the panel sent an invalid asset list");
        JsonArray out = new JsonArray();
        for (JsonElement a : assets.getAsJsonArray()) if (out.size() < MAX_LISTED) out.add(a);
        return out;
    }

    public void check(String name) { request.apply("check", named(name)); }

    public JsonObject save(String name, String description, int sx, int sy, int sz, byte[] data) {
        if (data.length > MAX_BYTES) throw new EditException("asset region is larger than 5 MB; save a smaller region");
        JsonObject args = named(name);
        args.addProperty("description", description);
        JsonArray size = new JsonArray();
        size.add(sx); size.add(sy); size.add(sz);
        args.add("size", size);
        args.addProperty("data", Base64.getEncoder().encodeToString(data));
        return request.apply("save", args);
    }

    /** A library asset's blocks, with the anchor and facing its owner set (each null when not set). */
    public record Asset(Blueprint blueprint, Anchor anchor) {}

    /** Serializes cache writes and eviction, not editing. No world chunks are read here. */
    public synchronized Asset load(String name, long maxVolume) {
        CallContext.Identity caller = CallContext.identity();
        if (caller == null) throw new EditException("personal assets need an authenticated panel caller");
        JsonObject meta = request.apply("get", named(name));
        String id, sha, url, facing = null;
        int sx, sy, sz, length;
        Vec3 anchor = null;
        try {
            id = meta.get("id").getAsString();
            sha = meta.get("sha256").getAsString();
            url = meta.get("url").getAsString();
            JsonArray size = meta.getAsJsonArray("size");
            if (size.size() != 3) throw new IllegalStateException("size");
            sx = size.get(0).getAsInt(); sy = size.get(1).getAsInt(); sz = size.get(2).getAsInt();
            length = meta.get("bytes").getAsInt();
            // Both optional: an older panel, or an asset whose owner set none.
            if (meta.has("anchor")) {
                JsonArray a = meta.getAsJsonArray("anchor");
                if (a.size() != 3) throw new IllegalStateException("anchor");
                anchor = new Vec3(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
                if (anchor.x() < 0 || anchor.y() < 0 || anchor.z() < 0 || anchor.x() >= sx || anchor.y() >= sy || anchor.z() >= sz)
                    throw new IllegalStateException("anchor");
            }
            if (meta.has("facing")) {
                facing = meta.get("facing").getAsString();
                if (!Anchor.FACINGS.contains(facing)) throw new IllegalStateException("facing");
            }
        } catch (RuntimeException e) { // missing fields, wrong types, numbers out of range
            throw new EditException("the panel sent invalid asset metadata");
        }
        // Both end up in the cache path: nothing but these characters may reach it.
        if (!id.matches("[a-z0-9]{12}") || !sha.matches("[a-f0-9]{64}")) throw new EditException("the panel sent invalid asset metadata");
        if (sx < 1 || sy < 1 || sz < 1 || sx > maxVolume || sy > maxVolume / sx || sz > maxVolume / sx / sy)
            throw new EditException("asset exceeds limits.max-volume-per-op; use a smaller asset");
        long volume = (long) sx * sy * sz;
        if (volume > Math.min(maxVolume, 2_000_000)) throw new EditException("asset exceeds volume limit");
        if (length < 1 || length > MAX_BYTES) throw new EditException("asset region is larger than 5 MB");
        Path file = cache.resolve(Long.toString(caller.user())).resolve(id + "-" + sha + ".bin");
        try {
            byte[] data = null;
            if (Files.isRegularFile(file) && Files.size(file) == length) {
                byte[] cached = Files.readAllBytes(file);
                if (digest(cached).equals(sha)) data = cached;
            }
            if (data == null) {
                URI uri;
                try {
                    uri = URI.create(url);
                } catch (IllegalArgumentException e) {
                    throw new EditException("invalid asset download URL"); // never echo it: it is a bearer credential
                }
                if (!List.of("http", "https").contains(uri.getScheme())) throw new EditException("invalid asset download URL");
                HttpRequest get = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45)).GET().build();
                HttpResponse<byte[]> response = http.send(get, info -> new LimitedBody(length));
                if (response.statusCode() != 200) throw new EditException("asset storage answered " + response.statusCode() + "; try again");
                data = response.body();
                if (data.length != length || !digest(data).equals(sha)) throw new EditException("asset download failed its checksum; try again");
                Files.createDirectories(file.getParent());
                Path tmp = Files.createTempFile(file.getParent(), "download-", ".tmp");
                try {
                    Files.write(tmp, data);
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(tmp);
                }
                prune(file);
            } else {
                Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            }
            String[] states = StateArrays.decode(data, volume);
            if (states.length != volume) throw new EditException("asset data does not match its dimensions");
            // Saved air places air, like local blueprints.
            return new Asset(Blueprint.ofStates(sx, sy, sz, states, false), new Anchor(anchor, facing));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EditException("asset download interrupted");
        } catch (IOException e) {
            throw new EditException("cannot download or cache asset; check storage and try again");
        }
    }

    /** Only after a download adds a file, oldest first; never the file just written. */
    private void prune(Path keep) throws IOException {
        try (var paths = Files.walk(cache)) {
            List<Path> files = paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".bin")).filter(p -> !p.equals(keep))
                    .sorted(Comparator.comparingLong(p -> p.toFile().lastModified())).toList();
            long total = Files.size(keep);
            for (Path p : files) total += Files.size(p);
            for (Path p : files) {
                if (total <= CACHE_BYTES) break;
                long bytes = Files.size(p);
                Files.delete(p);
                total -= bytes;
            }
        }
    }

    private static JsonObject named(String name) {
        JsonObject args = new JsonObject(); args.addProperty("name", name); return args;
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Bounds bytes while receiving, and completes only after the body, so the HTTP timeout covers the download. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private final java.util.concurrent.CompletableFuture<byte[]> body = new java.util.concurrent.CompletableFuture<>();
        private java.util.concurrent.Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription s) { subscription = s; s.request(1); }
        public void onNext(List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("asset download too large"));
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
