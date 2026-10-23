package de.gbv.logguy.server.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

class QueryTranslatorTest {

    private static final Map<String, String> FIELDS = Map.of("level", "level_s", "import_id", "import_id_s",
        "message", "message", "ts", "ts", "logger", "logger_s");

    private static String translate(String query) {
        return QueryTranslator.translate(query, FIELDS);
    }

    @Test
    void mapsDeclaredFields() {
        assertEquals("message:(connection AND refused) AND import_id_s:4711",
            translate("message:(connection AND refused) AND import_id:4711"));
        assertEquals("(level_s:ERROR OR level_s:WARN) -logger_s:Foo",
            translate("(level:ERROR OR level:WARN) -logger:Foo"));
        assertEquals("*:*", translate(" "));
        assertEquals("*:*", translate("*:*"));
    }

    @Test
    void leavesTermsQuotesAndRangesAlone() {
        assertEquals("refused \"level:ERROR\" foo\\:bar", translate("refused \"level:ERROR\" foo\\:bar"));
        assertEquals("ts:[2026-10-23T10:00:00Z TO *]", translate("ts:[2026-10-23T10:00:00Z TO *]"));
        assertEquals("ts:{2026-10-23T10:00:00Z TO NOW}", translate("ts:{2026-10-23T10:00:00Z TO NOW}"));
        assertEquals("message:/a:b/", translate("message:/a:b/"));
    }

    @Test
    void rejectsUnknownFieldsAndLocalParams() {
        assertThrows(SearchException.class, () -> translate("level_s:ERROR"));
        assertThrows(SearchException.class, () -> translate("_query_:\"{!join from=a to=b}x\""));
        assertThrows(SearchException.class, () -> translate("{!join from=a to=b}x"));
        assertThrows(SearchException.class, () -> translate("foo {!lucene}x"));
        assertThrows(SearchException.class, () -> translate("message:\"open"));
    }
}
