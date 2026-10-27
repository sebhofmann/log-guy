package de.gbv.logguy.server.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ApacheParserTest {

    private static ScriptRuntime runtime;

    private static ParserScript apache;

    @BeforeAll
    static void setUp() throws Exception {
        runtime = new ScriptRuntime(Duration.ofMillis(50), Duration.ofSeconds(5));
        apache = ParserScript.load(runtime, "apache-combined", ParserRegistry.template("apache-combined"));
    }

    @AfterAll
    static void tearDown() {
        runtime.close();
    }

    private static ParsedEvent parse(String line) throws Exception {
        try (Parser parser = new Parser(runtime, apache)) {
            ParsedEvent event = parser.parse(line);
            assertNull(event.error(), line);
            return event;
        }
    }

    @Test
    void everyLineIsAnEvent() {
        assertFalse(apache.hasIsStart());
        assertTrue(apache.fields().get("status").facet());
        assertEquals(FieldType.INT, apache.fields().get("status").type());
    }

    @Test
    void combinedFormat() throws Exception {
        String line = "203.0.113.7 - mueller [01/Oct/2026:00:00:05 +0200] \"GET /mir/receive/mir_mods_00000001?lang=de"
            + " HTTP/1.1\" 200 5120 \"https://example.org/suche?q=a\" \"Mozilla/5.0 (X11; Linux x86_64) Firefox/140.0\"";
        ParsedEvent event = parse(line);
        assertEquals(Instant.parse("2026-09-30T22:00:05Z"), event.ts());
        assertEquals(line, event.message());
        Map<String, Object> fields = event.fields();
        assertEquals("203.0.113.7", fields.get("ip"));
        assertEquals("mueller", fields.get("login"));
        assertEquals("GET", fields.get("method"));
        assertEquals("/mir/receive/mir_mods_00000001", fields.get("path"));
        assertEquals(200, ((Number) fields.get("status")).intValue());
        assertEquals(5120L, ((Number) fields.get("bytes")).longValue());
        assertEquals("https://example.org/suche?q=a", fields.get("referer"));
        assertEquals(Boolean.FALSE, fields.get("bot"));
    }

    @Test
    void commonFormatFromTomcatWithIpv6() throws Exception {
        ParsedEvent event = parse("0:0:0:0:0:0:0:1 - - [23/Mar/2026:23:29:58 +0100] \"GET /mir/ HTTP/1.1\" 404 683");
        assertEquals(Instant.parse("2026-03-23T22:29:58Z"), event.ts());
        assertEquals("0:0:0:0:0:0:0:1", event.fields().get("ip"));
        assertNull(event.fields().get("login"));
        assertEquals(404, ((Number) event.fields().get("status")).intValue());
        assertNull(event.fields().get("referer"));
        assertNull(event.fields().get("bot"));
    }

    @Test
    void escapedQuotesDashesAndBots() throws Exception {
        ParsedEvent event = parse("198.51.100.20 - - [01/Oct/2026:12:00:00 +0000] \"GET /a\\\"b HTTP/1.1\" 304 - \"-\""
            + " \"Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)\" 1234");
        assertEquals("/a\"b", event.fields().get("path"));
        assertNull(event.fields().get("bytes"));
        assertNull(event.fields().get("referer"));
        assertEquals(Boolean.TRUE, event.fields().get("bot"));
    }

    @Test
    void brokenLinesKeepTheMessage() throws Exception {
        ParsedEvent garbage = parse("\u0016\u0003\u0001 binary garbage");
        assertNull(garbage.ts());
        assertEquals(Map.of(), garbage.fields());
        ParsedEvent noRequest = parse("192.0.2.1 - - [01/Oct/2026:12:00:00 +0000] \"-\" 408 -");
        assertEquals(408, ((Number) noRequest.fields().get("status")).intValue());
        assertNull(noRequest.fields().get("method"));
    }
}
