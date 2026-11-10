package me.abdiskiosk.minecraftaibuilder.plugin.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What MCP traffic costs Claude, per tool: argument text it wrote, result text and image pixels it read back.
 * REST calls from scripts are not counted because they never enter Claude's context.
 * Token figures are estimates: about 3.5 characters of JSON and 750 image pixels per token.
 */
public final class ToolStats {

    /** Row name for the tool list and instructions sent once per session. */
    public static final String SESSION = "(session start)";

    public record Row(String tool, long calls, long argChars, long resultChars, long imagePixels) {
        public long tokens() {
            return Math.round((argChars + resultChars) / 3.5 + imagePixels / 750.0);
        }
    }

    private final Map<String, long[]> rows = new LinkedHashMap<>();

    public synchronized void record(String tool, long argChars, long resultChars, long imagePixels) {
        long[] r = rows.computeIfAbsent(tool, k -> new long[4]);
        r[0]++;
        r[1] += argChars;
        r[2] += resultChars;
        r[3] += imagePixels;
    }

    /** Most expensive first. */
    public synchronized List<Row> rows() {
        List<Row> out = new ArrayList<>();
        rows.forEach((tool, r) -> out.add(new Row(tool, r[0], r[1], r[2], r[3])));
        out.sort((a, b) -> Long.compare(b.tokens(), a.tokens()));
        return out;
    }

    public synchronized void reset() {
        rows.clear();
    }

    /** Width times height from a PNG header, or 0 when the bytes are not a PNG. */
    public static long pngPixels(byte[] png) {
        if (png == null || png.length < 24 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') return 0;
        return (long) readInt(png, 16) * readInt(png, 20);
    }

    private static int readInt(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }
}
