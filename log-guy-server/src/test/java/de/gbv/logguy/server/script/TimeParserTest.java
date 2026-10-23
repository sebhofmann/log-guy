package de.gbv.logguy.server.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class TimeParserTest {

    private final TimeParser parser = new TimeParser();

    @Test
    void log4jLocalTime() {
        // summer time, UTC+2
        assertEquals(Instant.parse("2026-10-01T10:15:30.123Z").toEpochMilli(),
            parser.parse("2026-10-01T12:15:30,123", "%Y-%m-%dT%H:%M:%S,%3f", "Europe/Berlin"));
        // winter time, UTC+1
        assertEquals(Instant.parse("2026-01-15T11:00:00.000Z").toEpochMilli(),
            parser.parse("2026-01-15 12:00:00,000", "%Y-%m-%d %H:%M:%S,%3f", "Europe/Berlin"));
    }

    @Test
    void apacheWithOffset() {
        assertEquals(Instant.parse("2026-10-23T08:00:01Z").toEpochMilli(),
            parser.parse("23/Oct/2026:10:00:01 +0200", "%d/%b/%Y:%H:%M:%S %z", "Europe/Berlin"));
    }

    @Test
    void mismatchGivesNull() {
        assertNull(parser.parse("2026-10-01 garbage", "%Y-%m-%dT%H:%M:%S,%3f", "Europe/Berlin"));
        assertNull(parser.parse("2026-13-01T12:15:30,123", "%Y-%m-%dT%H:%M:%S,%3f", "Europe/Berlin"));
        assertNull(parser.parse("xx", "%d/%b/%Y", "Europe/Berlin"));
    }
}
