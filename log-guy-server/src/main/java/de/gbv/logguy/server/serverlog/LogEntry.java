package de.gbv.logguy.server.serverlog;

import java.time.Instant;

/**
 * An entry of the own log in the ring buffer.
 *
 * @param seq increasing number, for polling only new entries
 * @param ts time of the entry
 * @param level the level, e.g. {@code INFO}
 * @param logger full name of the logger
 * @param thread name of the thread
 * @param message the formatted message
 * @param thrown stack trace of the exception, {@code null} if there is none
 */
public record LogEntry(long seq, Instant ts, String level, String logger, String thread, String message,
    String thrown) {
}
