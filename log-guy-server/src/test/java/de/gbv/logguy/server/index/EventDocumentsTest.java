package de.gbv.logguy.server.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Test;

import de.gbv.logguy.server.script.FieldDefinition;
import de.gbv.logguy.server.script.FieldType;
import de.gbv.logguy.server.script.ParsedEvent;
import de.gbv.logguy.server.script.ParserScript;

class EventDocumentsTest {

    private static final Instant TS = Instant.parse("2026-10-01T00:00:00Z");

    private static final ParserScript SCRIPT = new ParserScript("test", "", "v1",
        Map.of("path", new FieldDefinition("path", FieldType.STRING, false),
            "tags", new FieldDefinition("tags", FieldType.STRINGS, false),
            "text", new FieldDefinition("text", FieldType.TEXT, false),
            "seen", new FieldDefinition("seen", FieldType.DATE, false)),
        false);

    private static int utf8(Object value) {
        return value.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void truncateKeepsShortStrings() {
        String value = "/mir/receive/mir_mods_00000001";
        assertSame(value, EventDocuments.truncate(value, EventDocuments.MAX_TERM_BYTES));
        // 20000 chars of ASCII are 20000 bytes, too short to cut although three bytes per char would not fit
        String ascii = "a".repeat(20_000);
        assertSame(ascii, EventDocuments.truncate(ascii, EventDocuments.MAX_TERM_BYTES));
    }

    @Test
    void truncateCutsAtCharacterBoundaries() {
        assertEquals("a".repeat(10), EventDocuments.truncate("a".repeat(20), 10));
        // ä takes two bytes, the fifth would end after byte 10 of 9
        assertEquals("ääää", EventDocuments.truncate("ä".repeat(10), 9));
        // € takes three bytes
        assertEquals("€€", EventDocuments.truncate("€".repeat(10), 8));
        // 😀 is a surrogate pair of four bytes, never split
        assertEquals("x😀", EventDocuments.truncate("x" + "😀".repeat(10), 8));
        assertEquals("x", EventDocuments.truncate("x" + "😀".repeat(10), 4));
        String mixed = "aä€😀".repeat(5000);
        for (int max = 0; max < 40; max++) {
            String cut = EventDocuments.truncate(mixed, max);
            assertTrue(utf8(cut) <= max, cut);
            assertTrue(mixed.startsWith(cut));
            assertFalse(cut.contains("�"));
        }
        String big = EventDocuments.truncate(mixed, EventDocuments.MAX_TERM_BYTES);
        assertTrue(utf8(big) <= EventDocuments.MAX_TERM_BYTES);
        assertTrue(utf8(big) > EventDocuments.MAX_TERM_BYTES - 4);
        assertTrue(mixed.startsWith(big));
    }

    @Test
    void createCutsStringFieldsAndNotesIt() {
        String url = "/mir/servlets/solr/find?q=" + "x".repeat(40_000);
        String text = "y".repeat(40_000);
        ParsedEvent parsed = new ParsedEvent(TS, "GET " + url, Map.of("path", url, "tags",
            List.of("short", "ö".repeat(20_000)), "text", text), null);
        SolrInputDocument doc = EventDocuments.create("fp:0", "digibib", "app1", "app1/digibib/x.log", 0, TS, parsed,
            SCRIPT);
        assertEquals(EventDocuments.MAX_TERM_BYTES, utf8(doc.getFieldValue("path_s")));
        assertTrue(url.startsWith(doc.getFieldValue("path_s").toString()));
        List<Object> tags = List.copyOf(doc.getFieldValues("tags_ss"));
        assertEquals("short", tags.get(0));
        assertEquals(EventDocuments.MAX_TERM_BYTES, utf8(tags.get(1)));
        // text fields are tokenized, their terms are short anyway
        assertEquals(text, doc.getFieldValue("text_t"));
        assertEquals("GET " + url, doc.getFieldValue("message"));
        assertEquals("cut to 32766 bytes: path_s, tags_ss", doc.getFieldValue("parse_error"));
    }

    @Test
    void createKeepsErrorOfScriptAndCutsIt() {
        ParsedEvent parsed = new ParsedEvent(TS, "line", Map.of("path", "p".repeat(40_000)), "z".repeat(40_000));
        SolrInputDocument doc = EventDocuments.create("fp:0", "digibib", "app1", "f", 0, TS, parsed, SCRIPT);
        Object error = doc.getFieldValue("parse_error");
        assertTrue(utf8(error) <= EventDocuments.MAX_TERM_BYTES);
        assertTrue(error.toString().startsWith("zzz"));
    }

    @Test
    void createWithoutChangesHasNoError() {
        ParsedEvent parsed = new ParsedEvent(TS, "line", Map.of("path", "/mir/", "seen", TS), null);
        SolrInputDocument doc = EventDocuments.create("fp:0", "digibib", "app1", "f", 0, TS, parsed, SCRIPT);
        assertNull(doc.getFieldValue("parse_error"));
        assertEquals("/mir/", doc.getFieldValue("path_s"));
        assertEquals(Date.from(TS), doc.getFieldValue("seen_dt"));
        assertEquals("v1", doc.getFieldValue("parser_version"));
    }

    @Test
    void createLeavesOutImplausibleDates() {
        Instant far = Instant.ofEpochMilli(Long.MAX_VALUE).plusSeconds(1);
        ParsedEvent parsed = new ParsedEvent(TS, "line", Map.of("seen", far), null);
        SolrInputDocument doc = EventDocuments.create("fp:0", "digibib", "app1", "f", 0, TS, parsed, SCRIPT);
        assertNull(doc.getFieldValue("seen_dt"));
        assertEquals("implausible date left out: seen_dt", doc.getFieldValue("parse_error"));
        assertThrows(IllegalArgumentException.class,
            () -> EventDocuments.create("fp:0", "digibib", "app1", "f", 0, far, parsed, SCRIPT));
    }

    @Test
    void implausibleTimeBecomesParseError() {
        assertTrue(EventDocuments.plausible(TS));
        assertTrue(EventDocuments.plausible(EventDocuments.MIN_TIME));
        assertTrue(EventDocuments.plausible(EventDocuments.MAX_TIME));
        assertFalse(EventDocuments.plausible(null));
        assertFalse(EventDocuments.plausible(EventDocuments.MAX_TIME.plusMillis(1)));
        assertFalse(EventDocuments.plausible(EventDocuments.MIN_TIME.minusMillis(1)));
        // Date.from fails for this one with an overflow
        Instant overflow = Instant.parse("+1000000000-01-01T00:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> Date.from(overflow));
        assertFalse(EventDocuments.plausible(overflow));

        ParsedEvent ok = new ParsedEvent(TS, "line", Map.of("path", "/"), null);
        assertSame(ok, EventDocuments.withPlausibleTime(ok));
        ParsedEvent none = new ParsedEvent(null, "line", Map.of(), null);
        assertSame(none, EventDocuments.withPlausibleTime(none));

        ParsedEvent bad = EventDocuments.withPlausibleTime(new ParsedEvent(overflow, "line", Map.of("path", "/"),
            "earlier"));
        assertNull(bad.ts());
        assertEquals(Map.of("path", "/"), bad.fields());
        assertEquals("earlier; implausible ts: " + overflow, bad.error());
    }
}
