package de.gbv.logguy.server.serverlog;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Keeps the last entries of the own log in memory for the page Server-Log.
 */
public class RingBufferAppender extends AbstractAppender {

    private final LogEntry[] entries;

    private long next;

    /**
     * Creates the appender.
     *
     * @param name name of the appender
     * @param size number of entries kept
     */
    public RingBufferAppender(String name, int size) {
        super(name, (Filter) null, null, true, Property.EMPTY_ARRAY);
        if (size < 1) {
            throw new IllegalArgumentException("ring size must be positive");
        }
        this.entries = new LogEntry[size];
    }

    @Override
    public void append(LogEvent event) {
        String thrown = null;
        if (event.getThrown() != null) {
            StringWriter writer = new StringWriter();
            event.getThrown().printStackTrace(new PrintWriter(writer));
            thrown = writer.toString();
        }
        Instant ts = Instant.ofEpochSecond(event.getInstant().getEpochSecond(), event.getInstant().getNanoOfSecond());
        String message = event.getMessage().getFormattedMessage();
        synchronized (entries) {
            LogEntry entry = new LogEntry(next, ts, event.getLevel().name(), event.getLoggerName(),
                event.getThreadName(), message, thrown);
            entries[(int) (next % entries.length)] = entry;
            next++;
        }
    }

    /**
     * Entries newer than {@code after}, oldest first.
     *
     * @param after sequence number of the last known entry, {@code -1} for all
     * @param minLevel least severe level returned, {@code null} for all
     * @param logger part of the logger name, case insensitive, {@code null} for all
     * @param limit largest number of entries, the newest are returned
     * @return the entries
     */
    public List<LogEntry> entries(long after, Level minLevel, String logger, int limit) {
        List<LogEntry> copy = new ArrayList<>();
        synchronized (entries) {
            long first = Math.max(Math.max(after + 1, 0), next - entries.length);
            for (long seq = first; seq < next; seq++) {
                copy.add(entries[(int) (seq % entries.length)]);
            }
        }
        String part = logger == null || logger.isBlank() ? null : logger.toLowerCase(Locale.ROOT);
        List<LogEntry> result = copy.stream()
            .filter(e -> minLevel == null || Level.getLevel(e.level()).isMoreSpecificThan(minLevel))
            .filter(e -> part == null || e.logger().toLowerCase(Locale.ROOT).contains(part))
            .toList();
        return result.size() <= limit ? result : result.subList(result.size() - limit, result.size());
    }

    /**
     * Sequence number the next entry gets.
     *
     * @return the number
     */
    public long nextSeq() {
        synchronized (entries) {
            return next;
        }
    }
}
