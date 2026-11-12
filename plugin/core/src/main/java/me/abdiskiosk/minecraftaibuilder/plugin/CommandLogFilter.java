package me.abdiskiosk.minecraftaibuilder.plugin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;

import java.util.function.Consumer;

/**
 * Drops the server's "issued server command: /aibuilder link CODE" line (see {@link LinkCodeMask}). Compiled against
 * the Log4j of Spigot 1.8.8 (2.0-beta9), whose {@code AbstractFilter#filter(LogEvent)} every later Log4j 2 keeps. Only
 * loaded through {@link #install}, which a server without Log4j core turns into a warning.
 */
final class CommandLogFilter extends AbstractFilter {
    private static boolean installed;
    private final Consumer<String> relog;

    private CommandLogFilter(Consumer<String> relog) { this.relog = relog; }

    /** Once per server JVM: Log4j has no way to remove a filter, and a reload must not stack them. */
    static synchronized void install(Consumer<String> relog) {
        if (installed) { LinkCodeMask.active = true; return; }
        ((Logger) LogManager.getRootLogger()).addFilter(new CommandLogFilter(relog));
        installed = true;
        LinkCodeMask.active = true;
    }

    @Override
    public Filter.Result filter(LogEvent event) {
        if (!LinkCodeMask.active || event == null || event.getMessage() == null) return Filter.Result.NEUTRAL;
        String masked = LinkCodeMask.masked(event.getMessage().getFormattedMessage());
        if (masked == null) return Filter.Result.NEUTRAL;
        relog.accept(masked);
        return Filter.Result.DENY;
    }
}
