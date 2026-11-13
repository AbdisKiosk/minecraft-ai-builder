package me.abdiskiosk.minecraftaibuilder.plugin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps link codes out of the server log. The server logs every player command ("Steve issued server command: ...")
 * before any plugin sees it, and a code the plugin could not send yet stays valid for ten minutes, so whoever reads the
 * log could link their own player to the code's account. {@link CommandLogFilter} drops that line and this class logs it
 * again with the code hidden. Pure, so it is tested without Log4j.
 */
public final class LinkCodeMask {
    /** "/aibuilder link CODE", also as "/aibuilder:aibuilder link CODE"; the command name in any case. */
    private static final Pattern LINK = Pattern.compile("(?i)(/(?:[a-z0-9_.-]+:)?aibuilder\\s+link)(\\s+\\S.*)?$");
    private static final String MARK = "issued server command: ";

    /** True once the filter is installed, so a masked line is logged in place of the one it drops. */
    static volatile boolean active;

    private LinkCodeMask() { }

    /** The log line with its code hidden, or null when the line carries no link code. */
    public static String masked(String line) {
        if (line == null) return null;
        int at = line.indexOf(MARK);
        if (at < 0) return null;
        Matcher m = LINK.matcher(line.substring(at + MARK.length()));
        if (!m.lookingAt() || m.group(2) == null) return null;
        return line.substring(0, at + MARK.length()) + m.group(1) + " ********";
    }
}
