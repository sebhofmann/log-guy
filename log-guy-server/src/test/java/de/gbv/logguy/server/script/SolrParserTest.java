package de.gbv.logguy.server.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import de.gbv.logguy.server.index.EventReader;

class SolrParserTest {

    private static ScriptRuntime runtime;

    private static ParserScript solr;

    @BeforeAll
    static void setUp() throws Exception {
        runtime = new ScriptRuntime(Duration.ofMillis(50));
        solr = ParserScript.load(runtime, "solr", ParserRegistry.template("solr"));
    }

    @AfterAll
    static void tearDown() {
        runtime.close();
    }

    private static ParsedEvent parse(String text) throws Exception {
        try (Parser parser = new Parser(runtime, solr)) {
            ParsedEvent event = parser.parse(text);
            assertNull(event.error(), text);
            return event;
        }
    }

    @Test
    void searchRequest() throws Exception {
        ParsedEvent event = parse("2026-10-23 13:47:27.828 INFO  (qtp1281025083-34-localhost-80517) [c:logs_v1"
            + " s:shard1 r:core_node2 x:logs_v1_shard1_replica_n1 t:localhost-80517] o.a.s.c.S.Request webapp=/solr"
            + " path=/select params={q=*:*&rows=0} rid=localhost-80517 hits=20897431 status=0 QTime=18");
        assertEquals(Instant.parse("2026-10-23T13:47:27.828Z"), event.ts());
        Map<String, Object> fields = event.fields();
        assertEquals("INFO", fields.get("level"));
        assertEquals("o.a.s.c.S.Request", fields.get("logger"));
        assertEquals("logs_v1", fields.get("collection"));
        assertEquals("shard1", fields.get("shard"));
        assertEquals("logs_v1_shard1_replica_n1", fields.get("core"));
        assertEquals("/select", fields.get("path"));
        assertEquals(0, ((Number) fields.get("status")).intValue());
        assertEquals(18, ((Number) fields.get("qtime")).intValue());
        assertEquals(20897431L, ((Number) fields.get("hits")).longValue());
    }

    @Test
    void adminCallWithoutCollectionAndThreadWithSpaces() throws Exception {
        Map<String, Object> admin = parse("2026-10-23 13:41:49.737 INFO  (qtp1281025083-144-localhost-2)"
            + " [ t:localhost-2] o.a.s.s.HttpSolrCall [admin] webapp=null path=/admin/configs"
            + " params={action=LIST&wt=javabin&version=2} status=0 QTime=3").fields();
        assertNull(admin.get("collection"));
        assertEquals("/admin/configs", admin.get("path"));
        assertEquals(3, ((Number) admin.get("qtime")).intValue());

        Map<String, Object> searcher = parse("2026-10-23 13:41:50.275 INFO  (searcherExecutor-22-thread-1-processing"
            + "-localhost:18983_solr logs_v1_shard1_replica_n1 localhost-6 logs_v1 shard1 core_node2) [c:logs_v1"
            + " s:shard1 r:core_node2 x:logs_v1_shard1_replica_n1 t:localhost-6] o.a.s.c.SolrCore Registered new"
            + " searcher autowarm time: 0 ms").fields();
        assertEquals("logs_v1", searcher.get("collection"));
        assertEquals("o.a.s.c.SolrCore", searcher.get("logger"));
        assertNull(searcher.get("path"));
    }

    @Test
    void wholeSampleWithStackTraces() throws Exception {
        byte[] sample;
        try (InputStream in = SolrParserTest.class.getResourceAsStream("/logs/solr-9.log")) {
            sample = in.readAllBytes();
        }
        try (Parser parser = new Parser(runtime, solr)) {
            EventReader reader = new EventReader(0, 1024 * 1024);
            List<EventReader.RawEvent> events = new ArrayList<>(reader.feed(sample, parser::isStart));
            events.addAll(reader.tail(parser::isStart));
            int errors = 0;
            for (EventReader.RawEvent raw : events) {
                ParsedEvent event = parser.parse(raw.text());
                assertNull(event.error(), raw.text());
                assertNotNull(event.ts(), raw.text());
                assertNotNull(event.fields().get("logger"), raw.text());
                if ("ERROR".equals(event.fields().get("level"))) {
                    errors++;
                    assertTrue(raw.text().contains("\n\tat org.apache.solr."), raw.text());
                    assertTrue(((List<?>) event.fields().get("exc")).contains(
                        "org.apache.solr.common.SolrException"), raw.text());
                    assertEquals("logs_v1", event.fields().get("collection"));
                }
            }
            assertEquals(2, errors);
        }
    }
}
