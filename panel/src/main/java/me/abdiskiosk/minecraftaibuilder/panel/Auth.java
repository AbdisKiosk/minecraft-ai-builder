package me.abdiskiosk.minecraftaibuilder.panel;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tokens, the admin password and browser sessions. */
public final class Auth {

    public static final String SESSION_COOKIE = "aib_session";
    static final long SESSION_MILLIS = 7L * 24 * 3600 * 1000;
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Session id → expiry. In memory: a panel restart logs everyone out, which is fine for an admin tool. */
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    /** Remote address → failed logins in the current minute window. */
    private final Map<String, long[]> failures = new ConcurrentHashMap<>();

    /** @param prefix shows what a token is for when it turns up in a config file ("aibs_" server, "aibc_" client) */
    public static String newToken(String prefix) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean equalsConstantTime(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** The token in an {@code Authorization: Bearer ...} header, or null. */
    public static String bearer(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String t = header.substring(7).trim();
        return t.isEmpty() ? null : t;
    }

    public static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, PBKDF2_ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return "pbkdf2-sha256$" + PBKDF2_ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    public static boolean checkPassword(String password, String stored) {
        if (password == null || stored == null) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, pbkdf2(password, salt, iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** False when {@code remote} failed too many logins in the last minute. */
    public boolean mayTryLogin(String remote) {
        long[] f = failures.get(remote);
        long minute = System.currentTimeMillis() / 60_000;
        return f == null || f[0] != minute || f[1] < 10;
    }

    public void loginFailed(String remote) {
        long minute = System.currentTimeMillis() / 60_000;
        failures.compute(remote, (k, f) -> f == null || f[0] != minute ? new long[]{minute, 1} : new long[]{minute, f[1] + 1});
    }

    public String newSession() {
        String id = newToken("");
        sessions.put(id, System.currentTimeMillis() + SESSION_MILLIS);
        return id;
    }

    public boolean validSession(String id) {
        if (id == null) return false;
        Long expiry = sessions.get(id);
        if (expiry == null) return false;
        if (expiry < System.currentTimeMillis()) {
            sessions.remove(id);
            return false;
        }
        return true;
    }

    public void endSession(String id) {
        if (id != null) sessions.remove(id);
    }

    void purgeExpired() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<String, Long>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() < now) it.remove();
        }
        failures.entrySet().removeIf(e -> e.getValue()[0] != now / 60_000);
    }
}
