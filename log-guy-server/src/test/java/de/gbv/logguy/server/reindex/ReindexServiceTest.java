package de.gbv.logguy.server.reindex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.common.TestLogs;
import de.gbv.logguy.server.config.AppGroups;
import de.gbv.logguy.server.config.AppRegistry;
import de.gbv.logguy.server.archive.ArchiveFile;
import de.gbv.logguy.server.archive.ArchiveStore;
import de.gbv.logguy.server.archive.ArchiveWriter;
import de.gbv.logguy.server.archive.LifecycleFilters;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.config.LogGuyProperties.ArchiveProperties;
import de.gbv.logguy.server.config.LogGuyProperties.HotProperties;
import de.gbv.logguy.server.config.LogGuyProperties.IndexerProperties;
import de.gbv.logguy.server.config.LogGuyProperties.ReindexProperties;
import de.gbv.logguy.server.config.LogGuyProperties.ScriptProperties;
import de.gbv.logguy.server.hot.HotFile;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.index.EventReader;
import de.gbv.logguy.server.index.Indexer.DocumentSink;
import de.gbv.logguy.server.pseudonym.PseudonymService;
import de.gbv.logguy.server.reindex.ReindexJob.Plan;
import de.gbv.logguy.server.reindex.ReindexJob.State;
import de.gbv.logguy.server.reindex.ReindexService.Source;
import de.gbv.logguy.server.retention.RetentionService;
import de.gbv.logguy.server.script.ParsedEvent;
import de.gbv.logguy.server.script.Parser;
import de.gbv.logguy.server.script.ParserRegistry;
import de.gbv.logguy.server.script.ScriptRuntime;
import de.gbv.logguy.server.script.ScriptStore;
import tools.jackson.databind.json.JsonMapper;

class ReindexServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ScriptRuntime runtime;

    @TempDir
    Path dataDir;

    private LogGuyProperties properties;

    private HotStore hot;

    private ArchiveStore archive;

    private ParserRegistry parsers;

    private ArchiveFile file;

    /** Event offset in the filtered file to its time, for every event of the archive file. */
    private TreeMap<Long, Instant> events;

    private final List<SolrInputDocument> sent = Collections.synchronizedList(new ArrayList<>());

    private final List<String> deleted = Collections.synchronizedList(new ArrayList<>());

    private final DocumentSink sink = new DocumentSink() {
        @Override
        public void add(List<SolrInputDocument> documents) {
            sent.addAll(documents);
        }

        @Override
        public void delete(String query) {
            deleted.add(query);
        }
    };

    @BeforeAll
    static void startRuntime() {
        runtime = new ScriptRuntime(Duration.ofMillis(200), Duration.ofSeconds(5));
    }

    @AfterAll
    static void stopRuntime() {
        runtime.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        Path scriptDir = dataDir.resolve("scripts");
        Files.createDirectories(scriptDir.resolve("lifecycle"));
        properties = new LogGuyProperties(dataDir, ZoneId.of("Europe/Berlin"),
            Map.of("digibib", new AppProperties(null, "mycore-log4j")), List.of(), List.of(), null,
            new HotProperties(DataSize.ofKilobytes(16), Duration.ofSeconds(60), Duration.ofSeconds(5), 3),
            new ScriptProperties(
                scriptDir, Duration.ofMillis(200), Duration.ofSeconds(5), 100, DataSize.ofKilobytes(256), 1000, 100),
            null,
            new IndexerProperties(true, 2, Duration.ofMillis(100), 50, DataSize.ofKilobytes(64),
                DataSize.ofMegabytes(64), Duration.ofSeconds(5), DataSize.ofMegabytes(1), 7, Duration.ofSeconds(10)),
            null, null, null, null,
            new ArchiveProperties(true, 2, Duration.ofMillis(50), 3, 7, "[x]", "[f {filter}]", null, null),
            new ReindexProperties(Duration.ofHours(24), "-", Duration.ofDays(92), 5), null, null);
        hot = new HotStore(properties, JSON, Clock.systemUTC());
        archive = new ArchiveStore(properties, JSON);
        parsers = new ParserRegistry(properties, runtime);
        assertTrue(parsers.load().isEmpty());

        byte[] data = TestLogs.mycoreSample();
        HotFile source = hot.getOrCreate(Fingerprint.of(data), "app1", "digibib");
        for (int offset = 0; offset < data.length; offset += 30_000) {
            int length = Math.min(30_000, data.length - offset);
            source.append(offset, Arrays.copyOfRange(data, offset, offset + length), length, "/logs/mycore-app.log",
                Instant.now());
        }
        source.close(data.length, "/logs/mycore-app.log", Instant.now());
        ScriptStore scripts = new ScriptStore(scriptDir, Clock.systemUTC(), JSON);
        try (ArchiveWriter writer = new ArchiveWriter(properties, hot, archive,
            new LifecycleFilters(runtime, scripts, JSON, () -> AppGroups.NONE),
            new PseudonymService(runtime, scripts, JSON, () -> AppGroups.NONE), parsers, runtime)) {
            Instant deadline = Instant.now().plusSeconds(30);
            while (!archive.find(source.fingerprint()).map(a -> a.meta().closed()).orElse(false)) {
                assertTrue(Instant.now().isBefore(deadline), "archive not written");
                Thread.sleep(20);
            }
        }
        file = archive.find(source.fingerprint()).orElseThrow();
        assertTrue(file.frames().size() > 10, "frames " + file.frames().size());
        events = allEvents();
    }

    private TreeMap<Long, Instant> allEvents() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long offset = 0;
        while (offset < file.meta().length()) {
            byte[] bytes = file.read(offset, 1 << 20);
            out.write(bytes);
            offset += bytes.length;
        }
        TreeMap<Long, Instant> result = new TreeMap<>();
        try (Parser parser = new Parser(runtime, parsers.forApp("digibib").orElseThrow())) {
            EventReader reader = new EventReader(0, 1 << 20);
            List<EventReader.RawEvent> raw = new ArrayList<>(reader.feed(out.toByteArray(), parser::isStart));
            raw.addAll(reader.tail(parser::isStart));
            Instant last = null;
            for (EventReader.RawEvent event : raw) {
                ParsedEvent parsed = parser.parse(event.text());
                last = parsed.ts() != null ? parsed.ts() : last;
                result.put(event.offset(), last);
            }
        }
        return result;
    }

    private ReindexService service(Instant now, Optional<Instant> oldest) {
        return service(now, oldest, sink);
    }

    private ReindexService service(Instant now, Optional<Instant> oldest, DocumentSink sink) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        RetentionService retention =
            new RetentionService(properties, new AppRegistry(properties), null, null, clock, null);
        return new ReindexService(properties, hot, archive, null, parsers, runtime, null, sink, app -> oldest,
            retention,
            clock);
    }

    private static void await(ReindexJob job) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (job.running()) {
            assertTrue(Instant.now().isBefore(deadline), "job did not finish");
            Thread.sleep(20);
        }
    }

    private void assertLoaded(Instant from, Instant to, Instant now) {
        List<String> expected = events.entrySet().stream()
            .filter(e -> !e.getValue().isBefore(from) && e.getValue().isBefore(to))
            .map(e -> file.fingerprint() + ":a" + e.getKey()).sorted().toList();
        List<String> ids = sent.stream().map(doc -> (String) doc.getFieldValue("id")).sorted().toList();
        assertEquals(expected, ids);
        Date expires = Date.from(now.plus(Duration.ofHours(24)));
        for (SolrInputDocument doc : sent) {
            assertEquals(expires, doc.getFieldValue("expires"));
            assertEquals("digibib", doc.getFieldValue("app"));
            assertEquals(file.relativePath(), doc.getFieldValue("file"));
        }
    }

    @Test
    void loadsTheWholeFileWithExpiry() throws Exception {
        Instant now = file.meta().to().plus(Duration.ofDays(30));
        Instant to = file.meta().to().plusSeconds(1);
        try (ReindexService service = service(now, Optional.empty())) {
            Plan plan = service.plan("digibib", file.meta().from(), to, Source.ALL);
            assertEquals(List.of(), plan.hotFiles());
            assertEquals(List.of(file.fingerprint()), plan.archiveFiles());
            assertEquals(file.meta().length(), plan.archiveBytes());
            ReindexJob job = service.start("admin", plan);
            await(job);
            assertEquals(State.DONE, job.status().state());
            assertEquals(events.size(), job.status().events());
            assertEquals(file.meta().length(), job.status().bytesDone());
            assertLoaded(file.meta().from(), to, now);
        }
    }

    @Test
    void loadsOnlyTheEventsOfTheRange() throws Exception {
        List<Instant> times = new ArrayList<>(events.values());
        Instant from = times.get(times.size() / 3);
        Instant to = times.get(times.size() * 2 / 3);
        Instant now = file.meta().to().plus(Duration.ofDays(30));
        try (ReindexService service = service(now, Optional.empty())) {
            Plan plan = service.plan("digibib", from, to, Source.ARCHIVE);
            assertTrue(plan.archiveBytes() < file.meta().length(), "reads only some frames");
            ReindexJob job = service.start("admin", plan);
            await(job);
            assertEquals(State.DONE, job.status().state());
            assertLoaded(from, to, now);
        }
    }

    @Test
    void stopsAtTheOldestEventInTheIndex() throws Exception {
        List<Instant> times = new ArrayList<>(events.values());
        Instant oldest = times.get(times.size() / 2);
        Instant now = file.meta().to().plus(Duration.ofDays(30));
        try (ReindexService service = service(now, Optional.of(oldest))) {
            Plan plan = service.plan("digibib", file.meta().from(), now, Source.ARCHIVE);
            assertEquals(oldest, plan.archiveTo());
            ReindexJob job = service.start("admin", plan);
            await(job);
            assertLoaded(file.meta().from(), oldest, now);
        }
    }

    @Test
    void rejectsRangesWithNothingToDo() throws Exception {
        Instant now = file.meta().to().plus(Duration.ofDays(1));
        try (ReindexService service = service(now, Optional.empty())) {
            ReindexException inWindow = assertThrows(ReindexException.class,
                () -> service.plan("digibib", file.meta().from(), now, Source.ARCHIVE));
            assertTrue(inWindow.getMessage().contains("ganz im Index"), inWindow.getMessage());
            ReindexException noIndexer = assertThrows(ReindexException.class,
                () -> service.plan("digibib", file.meta().from(), now, Source.HOT));
            assertTrue(noIndexer.getMessage().contains("Indexer"), noIndexer.getMessage());
            assertThrows(ReindexException.class, () -> service.plan("unknown", file.meta().from(), now, Source.ALL));
            assertThrows(ReindexException.class, () -> service.plan("digibib", now, now, Source.ALL));
            Instant before = now.minus(Duration.ofDays(30));
            ReindexException empty = assertThrows(ReindexException.class,
                () -> service.plan("digibib", before, before.plus(Duration.ofDays(1)), Source.ARCHIVE));
            assertTrue(empty.getMessage().contains("Im Archiv liegt"), empty.getMessage());
        }
        Instant later = file.meta().to().plus(Duration.ofDays(400));
        try (ReindexService service = service(later, Optional.empty())) {
            ReindexException tooLong = assertThrows(ReindexException.class,
                () -> service.plan("digibib", file.meta().from(), later, Source.ARCHIVE));
            assertTrue(tooLong.getMessage().contains("höchstens 92 Tage"), tooLong.getMessage());
        }
    }

    @Test
    void plansASingleFile() throws Exception {
        Instant now = file.meta().to().plus(Duration.ofDays(30));
        Instant to = file.meta().to().plusMillis(1);
        try (ReindexService service = service(now, Optional.empty())) {
            Plan plan = service.planFile(file.fingerprint());
            assertEquals("digibib", plan.app());
            assertEquals(List.of(), plan.hotFiles());
            assertEquals(List.of(file.fingerprint()), plan.archiveFiles());
            assertEquals(file.meta().from(), plan.archiveFrom());
            assertEquals(to, plan.archiveTo());
            ReindexJob job = service.start("admin", plan);
            await(job);
            assertEquals(State.DONE, job.status().state());
            assertLoaded(file.meta().from(), to, now);
        }
        Instant inWindow = file.meta().to().plus(Duration.ofDays(1));
        try (ReindexService service = service(inWindow, Optional.empty())) {
            ReindexException noIndexer = assertThrows(ReindexException.class,
                () -> service.planFile(file.fingerprint()));
            assertTrue(noIndexer.getMessage().contains("Indexer"), noIndexer.getMessage());
            ReindexException unknown = assertThrows(ReindexException.class, () -> service.planFile("unbekannt"));
            assertTrue(unknown.getMessage().contains("gibt es nicht"), unknown.getMessage());
        }
    }

    @Test
    void holdWaitsForTheRunningLoadAndDropsTheRestOfTheFile() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        DocumentSink slow = new DocumentSink() {
            @Override
            public void add(List<SolrInputDocument> documents) throws Exception {
                entered.countDown();
                gate.await();
                sent.addAll(documents);
            }

            @Override
            public void delete(String query) {
                deleted.add(query);
            }
        };
        Instant now = file.meta().to().plus(Duration.ofDays(30));
        try (ReindexService service = service(now, Optional.empty(), slow)) {
            ReindexJob job = service.start("admin",
                service.plan("digibib", file.meta().from(), file.meta().to().plusSeconds(1), Source.ARCHIVE));
            assertTrue(entered.await(30, TimeUnit.SECONDS), "first batch not sent");
            AtomicBoolean held = new AtomicBoolean();
            Thread deleting = Thread.ofVirtual().start(() -> {
                try {
                    service.hold(file.fingerprint());
                    held.set(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            deleting.join(Duration.ofMillis(200));
            assertFalse(held.get(), "hold waits until the batch being sent has arrived");
            gate.countDown();
            deleting.join(Duration.ofSeconds(30));
            assertTrue(held.get());
            // the delete query of the file runs here, nothing may arrive after it
            int beforeDelete = sent.size();
            await(job);
            service.release(file.fingerprint());
            assertEquals(State.DONE, job.status().state());
            assertEquals(List.of(), job.status().failed());
            assertEquals(beforeDelete, sent.size());
            assertEquals(properties.indexer().batchSize(), beforeDelete);
        }
    }

    @Test
    void expireDeletesOverdueEvents() throws IOException {
        Instant now = Instant.parse("2026-10-23T10:00:00Z");
        service(now, Optional.empty()).expire();
        assertEquals(List.of("expires:[* TO 2026-10-23T10:00:00Z]"), deleted);
    }
}
