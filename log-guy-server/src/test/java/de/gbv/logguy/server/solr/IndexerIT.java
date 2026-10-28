package de.gbv.logguy.server.solr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.common.TestLogs;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.config.LogGuyProperties.HotProperties;
import de.gbv.logguy.server.config.LogGuyProperties.IndexerProperties;
import de.gbv.logguy.server.config.LogGuyProperties.IngestProperties;
import de.gbv.logguy.server.config.LogGuyProperties.ScriptProperties;
import de.gbv.logguy.server.hot.HotFile;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.index.DocumentsRejectedException;
import de.gbv.logguy.server.index.EventChecker;
import de.gbv.logguy.server.index.EventDocuments;
import de.gbv.logguy.server.index.EventReader;
import de.gbv.logguy.server.index.Indexer;
import de.gbv.logguy.server.script.Parser;
import de.gbv.logguy.server.script.ParserRegistry;
import de.gbv.logguy.server.script.ScriptRuntime;
import tools.jackson.databind.json.JsonMapper;

class IndexerIT {

    private static SolrTestContainer solr;

    private static SolrService service;

    private static ScriptRuntime runtime;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path dataDir;

    private final byte[] sample = TestLogs.mycoreSample();

    @BeforeAll
    static void startSolr() throws Exception {
        solr = new SolrTestContainer();
        service = new SolrService(solr.properties());
        runtime = new ScriptRuntime(Duration.ofMillis(50), Duration.ofSeconds(5));
        List<String> first = service.init();
        assertTrue(first.stream().anyMatch(s -> s.contains("uploaded")), first.toString());
        List<String> second = service.init();
        assertTrue(second.stream().allMatch(s -> s.contains("exists") || s.contains("points to")), second.toString());
    }

    @AfterAll
    static void stopSolr() throws Exception {
        runtime.close();
        service.close();
        solr.close();
    }

    @BeforeEach
    void clear() throws Exception {
        service.client().deleteByQuery("*:*");
        service.client().commit();
    }

    private LogGuyProperties properties(Duration provisionalAfter) {
        return new LogGuyProperties(dataDir, ZoneId.of("Europe/Berlin"),
            Map.of("digibib", new AppProperties(null, "mycore-log4j"), "access",
                new AppProperties(null, "apache-combined")),
            List.of(), List.of(),
            new IngestProperties(DataSize.ofMegabytes(8), 4, Duration.ofSeconds(1)),
            new HotProperties(DataSize.ofKilobytes(64), Duration.ofSeconds(60), Duration.ofSeconds(5), 3),
            new ScriptProperties(dataDir.resolve("scripts"), Duration.ofMillis(50), Duration.ofSeconds(5), 100,
                DataSize.ofKilobytes(256), 10000,
                500),
            solr.properties(),
            new IndexerProperties(true, 2, Duration.ofMillis(100), 500, DataSize.ofKilobytes(64),
                DataSize.ofMegabytes(64), provisionalAfter, DataSize.ofMegabytes(1), 7, Duration.ofSeconds(10)),
            null, null, null, null, null, null, null, null);
    }

    private int expectedEvents() throws Exception {
        try (Parser parser = new Parser(runtime, ParserRegistryHelper.mycore(runtime))) {
            EventReader reader = new EventReader(0, 1 << 20);
            List<EventReader.RawEvent> events = new ArrayList<>(reader.feed(sample, parser::isStart));
            events.addAll(reader.tail(parser::isStart));
            return events.size();
        }
    }

    private long count(String query) throws Exception {
        service.client().commit();
        return service.client().query(new SolrQuery(query).setRows(0)).getResults().getNumFound();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(60);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not reached");
            }
            Thread.sleep(100);
        }
    }

    @Test
    void indexesClosedFileOnceAndAgainWithoutDuplicates() throws Exception {
        LogGuyProperties properties = properties(Duration.ofSeconds(5));
        String fp = Fingerprint.of(sample);
        try (HotStore store = new HotStore(properties, JSON, Clock.systemUTC())) {
            HotFile file = store.getOrCreate(fp, "app1", "digibib");
            for (int offset = 0; offset < sample.length; offset += 50_000) {
                int length = Math.min(50_000, sample.length - offset);
                file.append(offset, Arrays.copyOfRange(sample, offset, offset + length), length, null, Instant.now());
            }
            file.close(sample.length, null, Instant.now());
            ParserRegistry parsers = new ParserRegistry(properties, runtime);
            assertTrue(parsers.load().isEmpty());
            int expected = expectedEvents();

            try (Indexer indexer =
                new Indexer(properties, store, parsers, runtime, null, null, service::add, Clock.systemUTC(),
                    JSON)) {
                await(() -> indexer.indexedEvents() >= expected && indexer.backlog() == 0);
            }
            assertEquals(expected, count("*:*"));
            assertEquals(expected, count("app:digibib AND host:app1"));
            assertTrue(count("level_s:ERROR") > 0);
            assertTrue(count("exc_ss:\"org.mycore.common.MCRException\"") > 0);
            assertEquals(0, count("parse_error:*"));

            QueryResponse response = service.client().query(new SolrQuery("message:MCRErrorServlet")
                .setFields("id", "offset", "ts", "level_s", "logger_s", "message", "file").setRows(1));
            SolrDocument doc = response.getResults().getFirst();
            long offset = (Long) doc.getFieldValue("offset");
            assertEquals(fp + ":" + offset, doc.getFieldValue("id"));
            assertTrue(doc.getFieldValue("file").toString().startsWith("app1/digibib/"));

            // a second run from scratch overwrites the same documents
            Files.list(dataDir.resolve("state/index")).forEach(p -> p.toFile().delete());
            try (Indexer indexer =
                new Indexer(properties, store, parsers, runtime, null, null, service::add, Clock.systemUTC(),
                    JSON)) {
                await(() -> indexer.indexedEvents() >= expected && indexer.backlog() == 0);
            }
            assertEquals(expected, count("*:*"));
        }
    }

    private static SolrInputDocument event(String id, String file) {
        SolrInputDocument doc = new SolrInputDocument();
        doc.setField("id", id);
        doc.setField("app", "digibib");
        doc.setField("host", "app1");
        doc.setField("file", file);
        doc.setField("offset", 17L);
        doc.setField("ts", new Date());
        doc.setField("message", "test");
        return doc;
    }

    @Test
    void reindexReplacesTheEventsOfAFileAndKeepsLoadedOnes() throws Exception {
        LogGuyProperties properties = properties(Duration.ofSeconds(5));
        String fp = Fingerprint.of(sample);
        try (HotStore store = new HotStore(properties, JSON, Clock.systemUTC())) {
            HotFile file = store.getOrCreate(fp, "app1", "digibib");
            file.append(0, sample, sample.length, null, Instant.now());
            file.close(sample.length, null, Instant.now());
            ParserRegistry parsers = new ParserRegistry(properties, runtime);
            assertTrue(parsers.load().isEmpty());
            int expected = expectedEvents();

            try (Indexer indexer =
                new Indexer(properties, store, parsers, runtime, null, null, service, Clock.systemUTC(),
                    JSON)) {
                await(() -> indexer.indexedEvents() >= expected && indexer.backlog() == 0);

                // an event the old parser cut differently and one loaded from the archive with an expiry
                SolrInputDocument stale = event(fp + ":999999999", file.relativePath());
                SolrInputDocument loaded = event(fp + ":a17", file.relativePath());
                loaded.setField("expires", Date.from(Instant.now().plus(Duration.ofHours(3))));
                service.add(List.of(stale, loaded));
                assertEquals(expected + 2, count("*:*"));

                indexer.reindex(fp);
                await(() -> indexer.pendingReindex() == 0 && indexer.indexedEvents() >= 2L * expected
                    && indexer.backlog() == 0);
            }
            assertEquals(expected + 1, count("*:*"));
            assertEquals(0, count("id:" + ClientUtils.escapeQueryChars(fp + ":999999999")));
            assertEquals(1, count("expires:*"));
        }
    }

    @Test
    void stopAfterPartialBlockLosesNoEvents() throws Exception {
        LogGuyProperties properties = properties(Duration.ofSeconds(5));
        String fp = Fingerprint.of(sample);
        try (HotStore store = new HotStore(properties, JSON, Clock.systemUTC())) {
            HotFile file = store.getOrCreate(fp, "app1", "digibib");
            file.append(0, sample, sample.length, null, Instant.now());
            file.close(sample.length, null, Instant.now());
            ParserRegistry parsers = new ParserRegistry(properties, runtime);
            assertTrue(parsers.load().isEmpty());
            int expected = expectedEvents();

            // the first batch ends inside a read block, then Solr is gone until the restart
            AtomicInteger calls = new AtomicInteger();
            Indexer.DocumentSink failing = docs -> {
                if (calls.incrementAndGet() > 1) {
                    throw new IOException("Solr is gone");
                }
                service.add(docs);
            };
            try (Indexer indexer =
                new Indexer(properties, store, parsers, runtime, null, null, failing, Clock.systemUTC(),
                    JSON)) {
                await(() -> calls.get() > 2);
            }
            try (Indexer indexer =
                new Indexer(properties, store, parsers, runtime, null, null, service::add, Clock.systemUTC(),
                    JSON)) {
                await(() -> indexer.backlog() == 0 && countUnchecked("*:*") == expected);
            }
        }
    }

    private long countUnchecked(String query) {
        try {
            return count(query);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void growingStackTraceIsIndexedProvisionallyAndReplaced() throws Exception {
        LogGuyProperties properties = properties(Duration.ofMillis(300));
        String head = "\u001b[1;31m2026-10-01T00:00:00,834 ERROR  MCRErrorServlet: boom\n"
            + "org.mycore.common.MCRException: Error while transforming!\n" + "x".repeat(1100) + "\n";
        String more = "\tat org.mycore.Foo.bar(Foo.java:1)\nCaused by: java.io.IOException: gone\n";
        byte[] first = head.getBytes();
        String fp = Fingerprint.of(first);
        try (HotStore store = new HotStore(properties, JSON, Clock.systemUTC())) {
            HotFile file = store.getOrCreate(fp, "app1", "digibib");
            file.append(0, first, first.length, null, Instant.now());
            ParserRegistry parsers = new ParserRegistry(properties, runtime);
            assertTrue(parsers.load().isEmpty());
            List<String> checks = new CopyOnWriteArrayList<>();
            EventChecker checker = (app, host, id, ts, event, skip) -> {
                checks.add(id + " " + skip);
                return event.message().contains("IOException") ? Set.of("io") : Set.of("boom");
            };
            try (Indexer indexer =
                new Indexer(properties, store, parsers, runtime, null, checker, service::add, Clock.systemUTC(),
                    JSON)) {
                await(() -> indexer.indexedEvents() >= 1);
                assertEquals(1, count("id:\"" + fp + ":0\""));
                assertEquals(0, count("exc_ss:\"java.io.IOException\""));

                byte[] second = more.getBytes();
                file.append(first.length, second, second.length, null, Instant.now());
                await(() -> indexer.indexedEvents() >= 2);
                assertEquals(1, count("*:*"));
                assertEquals(1, count("exc_ss:\"java.io.IOException\""));
                // the rule that matched the provisional event is skipped when the event comes again
                assertEquals(List.of(fp + ":0 []", fp + ":0 [boom]"), checks);
            }
        }
    }

    @Test
    void matchesOfAProvisionalEventSurviveARestart() throws Exception {
        LogGuyProperties properties = properties(Duration.ofMillis(300));
        byte[] first = "\u001b[1;31m2026-10-01T00:00:00,834 ERROR  MCRErrorServlet: boom\n".getBytes();
        String fp = Fingerprint.of(first);
        try (HotStore store = new HotStore(properties, JSON, Clock.systemUTC())) {
            HotFile file = store.getOrCreate(fp, "app1", "digibib");
            file.append(0, first, first.length, null, Instant.now());
            ParserRegistry parsers = new ParserRegistry(properties, runtime);
            assertTrue(parsers.load().isEmpty());
            List<String> checks = new CopyOnWriteArrayList<>();
            EventChecker checker = (app, host, id, ts, event, skip) -> {
                checks.add(id + " " + skip);
                return Set.of("boom");
            };
            try (Indexer indexer =
                new Indexer(properties, store, parsers, runtime, null, checker, service::add, Clock.systemUTC(),
                    JSON)) {
                await(() -> indexer.indexedEvents() >= 1);
            }
            try (Indexer indexer =
                new Indexer(properties, store, parsers, runtime, null, checker, service::add, Clock.systemUTC(),
                    JSON)) {
                await(() -> indexer.indexedEvents() >= 1);
                byte[] second = "\u001b[1;31m2026-10-01T00:00:01,000 INFO  MCRServlet: next\n".getBytes();
                file.append(first.length, second, second.length, null, Instant.now());
                await(() -> indexer.indexedEvents() >= 2);
            }
            // after the restart the rule that matched before is skipped
            assertEquals(List.of(fp + ":0 []", fp + ":0 [boom]"), checks.subList(0, 2));
            assertTrue(
                checks.stream().skip(1).filter(c -> c.startsWith(fp + ":0 ")).allMatch(c -> c.endsWith("[boom]")),
                checks.toString());
        }
    }

    private static final String LONG_URL = "/mir/receive/" + "x".repeat(40_000);

    // three access log lines, the second one with a URL and a referer longer than a term in Lucene may be
    private static byte[] accessLog() {
        return ("203.0.113.7 - - [01/Oct/2026:00:00:00 +0200] \"GET /mir/ HTTP/1.1\" 200 5120 \"-\" \"curl/8\"\n"
            + "203.0.113.8 - - [01/Oct/2026:00:00:01 +0200] \"GET " + LONG_URL + " HTTP/1.1\" 414 0 \""
            + "https://example.org" + LONG_URL + "\" \"Mozilla/5.0\"\n"
            + "203.0.113.9 - - [01/Oct/2026:00:00:02 +0200] \"GET /mir/x HTTP/1.1\" 404 10 \"-\" \"curl/8\"\n")
            .getBytes(StandardCharsets.UTF_8);
    }

    private SolrDocument only(String query, String... fields) throws Exception {
        service.client().commit();
        SolrDocumentList found = service.client().query(new SolrQuery(query).setFields(fields)).getResults();
        assertEquals(1, found.getNumFound(), query);
        return found.getFirst();
    }

    @Test
    void lineWithUrlBeyondTheTermLimitIsIndexedAndFileGetsDone() throws Exception {
        LogGuyProperties properties = properties(Duration.ofSeconds(5));
        byte[] log = accessLog();
        String fp = Fingerprint.of(log);
        try (HotStore store = new HotStore(properties, JSON, Clock.systemUTC())) {
            HotFile file = store.getOrCreate(fp, "web1", "access");
            file.append(0, log, log.length, null, Instant.now());
            file.close(log.length, null, Instant.now());
            ParserRegistry parsers = new ParserRegistry(properties, runtime);
            assertTrue(parsers.load().isEmpty());
            try (Indexer indexer =
                new Indexer(properties, store, parsers, runtime, null, null, service, Clock.systemUTC(), JSON)) {
                await(() -> indexer.isIndexed(fp));
                assertEquals(3, indexer.indexedEvents());
                assertEquals(Map.of("access", 1L), indexer.parseErrors());
            }
        }
        assertEquals(3, count("app:access"));
        assertEquals(1, count("parse_error:*"));
        SolrDocument cut = only("status_i:414", "path_s", "referer_s", "agent_s", "parse_error", "message");
        assertEquals(EventDocuments.MAX_TERM_BYTES, cut.getFieldValue("path_s").toString().length());
        assertTrue(LONG_URL.startsWith(cut.getFieldValue("path_s").toString()));
        assertEquals(EventDocuments.MAX_TERM_BYTES, cut.getFieldValue("referer_s").toString().length());
        assertEquals("Mozilla/5.0", cut.getFieldValue("agent_s"));
        assertEquals("cut to 32766 bytes: path_s, referer_s", cut.getFieldValue("parse_error"));
        // the message is a text field and keeps the whole line
        assertTrue(cut.getFieldValue("message").toString().contains(LONG_URL + " HTTP/1.1"));
        assertEquals(1, count("status_i:404 AND path_s:\"/mir/x\""));
    }

    @Test
    void documentRejectedBySolrIsIndexedWithoutTheFieldAndFileGetsDone() throws Exception {
        LogGuyProperties properties = properties(Duration.ofSeconds(5));
        // without long values, so no document has a parse_error before Solr rejects it
        byte[] log = new String(accessLog(), StandardCharsets.UTF_8).replace(LONG_URL, "/mir/long")
            .getBytes(StandardCharsets.UTF_8);
        String fp = Fingerprint.of(log);
        String immense = "y".repeat(40_000);
        // as if EventDocuments had not cut path_s, Solr rejects the whole batch until the field is left out
        Indexer.DocumentSink poisoning = docs -> {
            List<SolrInputDocument> copy = new ArrayList<>();
            for (SolrInputDocument doc : docs) {
                SolrInputDocument sent = doc.deepCopy();
                if ("/mir/long".equals(doc.getFieldValue("path_s"))) {
                    sent.setField("path_s", immense);
                }
                copy.add(sent);
            }
            service.add(copy);
        };
        try (HotStore store = new HotStore(properties, JSON, Clock.systemUTC())) {
            HotFile file = store.getOrCreate(fp, "web1", "access");
            file.append(0, log, log.length, null, Instant.now());
            file.close(log.length, null, Instant.now());
            ParserRegistry parsers = new ParserRegistry(properties, runtime);
            assertTrue(parsers.load().isEmpty());
            try (Indexer indexer =
                new Indexer(properties, store, parsers, runtime, null, null, poisoning, Clock.systemUTC(), JSON)) {
                await(() -> indexer.isIndexed(fp));
            }
        }
        assertEquals(3, count("app:access"));
        assertEquals(1, count("parse_error:*"));
        SolrDocument repaired = only("status_i:414", "path_s", "referer_s", "parse_error");
        assertEquals(null, repaired.getFieldValue("path_s"));
        assertEquals("https://example.org/mir/long", repaired.getFieldValue("referer_s"));
        assertEquals("rejected by Solr, left out: path_s", repaired.getFieldValue("parse_error"));
        assertEquals(1, count("status_i:404 AND path_s:\"/mir/x\""));
    }

    @Test
    void solrRejectionIsReportedAsRejectedDocuments() {
        SolrInputDocument bad = event("bad", "f");
        bad.setField("path_s", "z".repeat(40_000));
        DocumentsRejectedException e = assertThrows(DocumentsRejectedException.class,
            () -> service.add(List.of(event("good", "f"), bad)));
        assertTrue(e.getMessage().contains("field=\"path_s\""), e.getMessage());
    }
}
