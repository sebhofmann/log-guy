package de.gbv.logguy.server.serverlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.Test;

class RingBufferAppenderTest {

    private static LogEvent event(Level level, String logger, String message, Throwable thrown) {
        return Log4jLogEvent.newBuilder()
            .setLevel(level)
            .setLoggerName(logger)
            .setMessage(new SimpleMessage(message))
            .setThreadName("main")
            .setTimeMillis(1_700_000_000_000L)
            .setThrown(thrown)
            .build();
    }

    @Test
    void keepsTheNewestEntries() {
        RingBufferAppender ring = new RingBufferAppender("test", 3);
        ring.start();
        for (int i = 0; i < 5; i++) {
            ring.append(event(Level.INFO, "a.B", "m" + i, null));
        }
        List<LogEntry> entries = ring.entries(-1, null, null, 10);
        assertEquals(List.of("m2", "m3", "m4"), entries.stream().map(LogEntry::message).toList());
        assertEquals(5, ring.nextSeq());
        assertEquals(List.of("m4"), ring.entries(3, null, null, 10).stream().map(LogEntry::message).toList());
        assertEquals(List.of("m3", "m4"), ring.entries(-1, null, null, 2).stream().map(LogEntry::message).toList());
    }

    @Test
    void filtersByLevelAndLogger() {
        RingBufferAppender ring = new RingBufferAppender("test", 10);
        ring.start();
        ring.append(event(Level.DEBUG, "de.gbv.logguy.index.Indexer", "debug", null));
        ring.append(event(Level.WARN, "de.gbv.logguy.index.Indexer", "warn", null));
        ring.append(event(Level.ERROR, "org.apache.solr.Client", "error", new IllegalStateException("boom")));
        assertEquals(List.of("warn", "error"),
            ring.entries(-1, Level.WARN, null, 10).stream().map(LogEntry::message).toList());
        assertEquals(List.of("debug", "warn"),
            ring.entries(-1, null, "INDEXER", 10).stream().map(LogEntry::message).toList());
        LogEntry error = ring.entries(-1, Level.ERROR, null, 10).getFirst();
        assertNotNull(error.thrown());
        assertTrue(error.thrown().contains("boom"), error.thrown());
    }
}
