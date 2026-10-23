package de.gbv.logguy.server.solr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
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
        runtime = new ScriptRuntime(Duration.ofMillis(50));
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
            Map.of("digibib", new AppProperties(null, "mycore-log4j")), List.of(), List.of(),
            new IngestProperties(DataSize.ofMegabytes(8), 4, Duration.ofSeconds(1)),
            new HotProperties(DataSize.ofKilobytes(64), Duration.ofSeconds(60), Duration.ofSeconds(5), 3),
            new ScriptProperties(dataDir.resolve("scripts"), Duration.ofMillis(50)), solr.properties(),
            new IndexerProperties(true, 2, Duration.ofMillis(100), 500, DataSize.ofKilobytes(64),
                DataSize.ofMegabytes(64), provisionalAfter, DataSize.ofMegabytes(1), 7, Duration.ofSeconds(10)),
            null, null, null);
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

            try (Indexer indexer = new Indexer(properties, store, parsers, runtime, service::add, Clock.systemUTC(),
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
            try (Indexer indexer = new Indexer(properties, store, parsers, runtime, service::add, Clock.systemUTC(),
                JSON)) {
                await(() -> indexer.indexedEvents() >= expected && indexer.backlog() == 0);
            }
            assertEquals(expected, count("*:*"));
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
            try (Indexer indexer = new Indexer(properties, store, parsers, runtime, failing, Clock.systemUTC(),
                JSON)) {
                await(() -> calls.get() > 2);
            }
            try (Indexer indexer = new Indexer(properties, store, parsers, runtime, service::add, Clock.systemUTC(),
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
            try (Indexer indexer = new Indexer(properties, store, parsers, runtime, service::add, Clock.systemUTC(),
                JSON)) {
                await(() -> indexer.indexedEvents() >= 1);
                assertEquals(1, count("id:\"" + fp + ":0\""));
                assertEquals(0, count("exc_ss:\"java.io.IOException\""));

                byte[] second = more.getBytes();
                file.append(first.length, second, second.length, null, Instant.now());
                await(() -> indexer.indexedEvents() >= 2);
                assertEquals(1, count("*:*"));
                assertEquals(1, count("exc_ss:\"java.io.IOException\""));
            }
        }
    }
}
