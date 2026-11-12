package me.abdiskiosk.minecraftaibuilder.plugin;

import java.util.function.Supplier;

/**
 * Which transport and tool the current request thread is serving, so jobs can say where they came from
 * (shown in the panel's history). Tools call {@link BuildService#submit} on the request thread.
 */
public final class CallContext {

    private static final ThreadLocal<String> SOURCE = new ThreadLocal<>();
    private static final ThreadLocal<String> TOOL = new ThreadLocal<>();
    private static final ThreadLocal<Identity> IDENTITY = new ThreadLocal<>();

    /**
     * The panel account a call came from, with the opaque per-request grant for its asset library (null when the panel
     * could not issue one). Never a tool argument or Minecraft player id.
     */
    public record Identity(long user, String assetKey) {}

    public static <T> T withIdentity(Identity identity, Supplier<T> body) {
        Identity old = IDENTITY.get();
        IDENTITY.set(identity);
        try {
            return body.get();
        } finally {
            if (old == null) IDENTITY.remove();
            else IDENTITY.set(old);
        }
    }

    public static Identity identity() { return IDENTITY.get(); }

    private CallContext() {
    }

    /** @param source e.g. "panel mcp", "panel rest", "panel" */
    public static <T> T withSource(String source, Supplier<T> body) {
        String old = SOURCE.get();
        SOURCE.set(source);
        try {
            return body.get();
        } finally {
            SOURCE.set(old);
        }
    }

    public static <T> T withTool(String tool, Supplier<T> body) {
        String old = TOOL.get();
        TOOL.set(tool);
        try {
            return body.get();
        } finally {
            TOOL.set(old);
        }
    }

    public static String source() {
        String s = SOURCE.get();
        return s == null ? "plugin" : s;
    }

    public static String tool() {
        String t = TOOL.get();
        return t == null ? "?" : t;
    }
}
