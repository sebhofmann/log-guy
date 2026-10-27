package de.gbv.logguy.server.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.common.StreamStatus;
import de.gbv.logguy.common.TestLogs;
import de.gbv.logguy.server.archive.ArchiveFile;
import de.gbv.logguy.server.archive.ArchiveStore;
import de.gbv.logguy.server.archive.ArchiveWriter;
import de.gbv.logguy.server.archive.LifecycleFilters;
import de.gbv.logguy.server.config.AppGroups;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.config.LogGuyProperties.ArchiveProperties;
import de.gbv.logguy.server.config.LogGuyProperties.HotProperties;
import de.gbv.logguy.server.config.LogGuyProperties.ScriptProperties;
import de.gbv.logguy.server.files.FileService.FileInfo;
import de.gbv.logguy.server.files.FileService.Line;
import de.gbv.logguy.server.files.FileService.Page;
import de.gbv.logguy.server.files.FileService.SearchResult;
import de.gbv.logguy.server.files.FileService.Source;
import de.gbv.logguy.server.hot.HotFile;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.index.Indexer.DocumentSink;
import de.gbv.logguy.server.ingest.IngestService;
import de.gbv.logguy.server.script.ParserRegistry;
import de.gbv.logguy.server.script.ScriptRuntime;
import de.gbv.logguy.server.script.ScriptStore;
import de.gbv.logguy.server.security.IngestPrincipal;
import tools.jackson.databind.json.JsonMapper;

class FileServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final UnaryOperator<String> PLAIN = UnaryOperator.identity();

    private static ScriptRuntime runtime;

    @TempDir
    Path dataDir;

    private LogGuyProperties properties;

    private HotStore hot;

    private ArchiveStore archive;

    private DeletedFiles deleted;

    private FileService service;

    private byte[] sample;

    private String fingerprint;

    private final List<String> deletedQueries = Collections.synchronizedList(new ArrayList<>());

    private final DocumentSink sink = new DocumentSink() {
        @Override
        public void add(List<SolrInputDocument> documents) {
        }

        @Override
        public void delete(String query) {
            deletedQueries.add(query);
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
            new ScriptProperties(scriptDir, Duration.ofMillis(200), Duration.ofSeconds(5), 100,
                DataSize.ofKilobytes(256), 1000, 100),
            null,
            null, null, null, null, null,
            new ArchiveProperties(true, 2, Duration.ofMillis(50), 3, 7, "[x]", "[f {filter}]", null), null, null,
            null, null);
        hot = new HotStore(properties, JSON, Clock.systemUTC());
        archive = new ArchiveStore(properties, JSON);
        ParserRegistry parsers = new ParserRegistry(properties, runtime);
        assertTrue(parsers.load().isEmpty());

        sample = TestLogs.mycoreSample();
        HotFile source = hot.getOrCreate(Fingerprint.of(sample), "app1", "digibib");
        for (int offset = 0; offset < sample.length; offset += 30_000) {
            int length = Math.min(30_000, sample.length - offset);
            source.append(offset, Arrays.copyOfRange(sample, offset, offset + length), length,
                "/logs/mycore-app.log", Instant.now());
        }
        source.close(sample.length, "/logs/mycore-app.log", Instant.now());
        fingerprint = source.fingerprint();
        ScriptStore scripts = new ScriptStore(scriptDir, Clock.systemUTC(), JSON);
        try (ArchiveWriter writer = new ArchiveWriter(properties, hot, archive,
            new LifecycleFilters(runtime, scripts, JSON, () -> AppGroups.NONE), parsers, runtime)) {
            Instant deadline = Instant.now().plusSeconds(30);
            while (!archive.find(fingerprint).map(a -> a.meta().closed()).orElse(false)) {
                assertTrue(Instant.now().isBefore(deadline), "archive not written");
                Thread.sleep(20);
            }
        }
        deleted = new DeletedFiles(properties.stateDir(), JSON);
        FilesProperties settings = new FilesProperties(50, DataSize.ofKilobytes(16), DataSize.ofKilobytes(64), 5,
            DataSize.ofKilobytes(64), 10);
        service = new FileService(settings, hot, archive, null, null, sink, deleted, null, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() throws IOException {
        hot.close();
    }

    private String lines() {
        String text = new String(sample, StandardCharsets.UTF_8);
        return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }

    private static String join(List<Line> lines) {
        return String.join("\n", lines.stream().map(Line::text).toList());
    }

    @Test
    void listsTheFileForVisibleApplications() {
        FileInfo info = service.info(fingerprint);
        assertEquals("app1", info.host());
        assertEquals("digibib", info.app());
        assertEquals(List.of("/logs/mycore-app.log"), info.paths());
        assertEquals(sample.length, info.hot().length());
        assertEquals(sample.length, info.archive().sourceLength());
        assertTrue(info.archive().closed());
        assertEquals(List.of(info), service.list(app -> true, null, null, null, null).files());
        assertEquals(List.of(), service.list(app -> false, null, null, null, null).files());
        assertEquals(List.of(), service.list(app -> true, "other", null, null, null).files());
        assertEquals(Set.of("digibib"), service.facets(app -> true).apps());
    }

    @Test
    void pagesForwardAndBackwardThroughTheWholeFile() throws IOException {
        List<Line> forward = new ArrayList<>();
        Long at = 0L;
        int pages = 0;
        while (at != null) {
            Page page = service.content(fingerprint, Source.HOT, at, null, PLAIN);
            assertTrue(page.lines().size() <= 50);
            forward.addAll(page.lines());
            at = page.next();
            pages++;
        }
        assertTrue(pages > 10, "pages " + pages);
        assertEquals(lines(), join(forward));

        List<Line> backward = new ArrayList<>();
        Long before = Long.MAX_VALUE;
        while (before != null) {
            Page page = service.content(fingerprint, Source.HOT, null, before, PLAIN);
            backward.addAll(0, page.lines());
            before = page.prev();
        }
        assertEquals(forward, backward);

        // an offset inside a line shows that line first
        Line middle = forward.get(forward.size() / 2);
        Page page = service.content(fingerprint, Source.HOT, middle.offset() + 5, null, PLAIN);
        assertEquals(middle, page.lines().getFirst());
        assertEquals(middle.offset(), page.prev());

        // the archive holds the filtered form with the same lines
        Page archived = service.content(fingerprint, Source.ARCHIVE, 0L, null, PLAIN);
        assertEquals(forward.getFirst().text(), archived.lines().getFirst().text());
    }

    @Test
    void searchesWithLimitsAndContinues() throws IOException {
        String text = lines();
        long total = text.lines().filter(line -> line.contains("ERROR")).count();
        assertTrue(total > 10, "errors " + total);
        List<Line> hits = new ArrayList<>();
        long from = 0;
        boolean done = false;
        while (!done) {
            SearchResult result = service.search(fingerprint, Source.HOT, "error", false, true, from, PLAIN);
            assertTrue(result.hits().size() <= 5);
            hits.addAll(result.hits());
            from = result.scannedTo();
            done = result.done();
        }
        assertTrue(hits.size() >= total, hits.size() + " < " + total);
        for (Line hit : hits) {
            int start = (int) hit.offset();
            int end = start;
            while (sample[end] != '\n') {
                end++;
            }
            String line = new String(sample, start, end - start, StandardCharsets.UTF_8);
            assertEquals(FileService.withoutColors(line), hit.text());
        }

        SearchResult regex = service.search(fingerprint, Source.ARCHIVE, "ERROR +\\S+ MCR", true, false, 0, PLAIN);
        assertFalse(regex.hits().isEmpty());

        // a line the pseudonymization withholds is no hit
        SearchResult withheld = service.search(fingerprint, Source.HOT, "ERROR", false, false, 0, line -> null);
        assertEquals(List.of(), withheld.hits());
        assertTrue(withheld.done());

        assertThrows(IllegalArgumentException.class,
            () -> service.search(fingerprint, Source.HOT, "(", true, false, 0, PLAIN));
        assertThrows(IllegalArgumentException.class,
            () -> service.search(fingerprint, Source.HOT, "", false, false, 0, PLAIN));
    }

    @Test
    void downloadsTheOriginal() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.download(fingerprint, Source.HOT, out);
        assertTrue(Arrays.equals(sample, out.toByteArray()));
        assertTrue(service.fileName(fingerprint, Source.HOT).endsWith(".log"));
    }

    @Test
    void deletesAndBlocksTheFile() throws Exception {
        HotFile hotFile = hot.find(fingerprint).orElseThrow();
        ArchiveFile archived = archive.find(fingerprint).orElseThrow();
        Path archivePath = properties.archiveDir().resolve(archived.relativePath());
        assertTrue(Files.exists(archivePath));

        FileService.DeleteResult result = service.delete(fingerprint, true, "admin");
        assertTrue(result.hot());
        assertTrue(result.archive());
        assertTrue(result.blocked());
        assertNull(result.indexError());
        assertTrue(hot.find(fingerprint).isEmpty());
        assertTrue(archive.find(fingerprint).isEmpty());
        assertFalse(Files.exists(archivePath));
        assertTrue(new ArchiveStore(properties, JSON).find(fingerprint).isEmpty());
        assertEquals(List.of("id:" + fingerprint + "\\:*"), deletedQueries);
        assertThrows(IOException.class, () -> hotFile.append(sample.length, new byte[] { 'x' }, 1, null,
            Instant.now()));

        // the agent sending the file again is told it is closed
        DeletedFiles.Entry entry = deleted.blocked(fingerprint).orElseThrow();
        assertEquals("admin", entry.user());
        assertEquals(sample.length, entry.length());
        IngestService ingest = new IngestService(hot, archive, new DeletedFiles(properties.stateDir(), JSON));
        IngestPrincipal agent = new IngestPrincipal("agent", "app1", Set.of("digibib"));
        StreamStatus closed = new StreamStatus(fingerprint, sample.length, true);
        assertEquals(closed, ingest.status(agent, fingerprint));
        assertEquals(closed, ingest.append(agent, fingerprint, 0, "app1", "digibib", "/logs/x.log",
            Arrays.copyOf(sample, 100), 100));
        assertTrue(hot.find(fingerprint).isEmpty());

        // after the block is lifted the file arrives again
        assertEquals(entry, service.unblock(fingerprint));
        assertTrue(deleted.list().isEmpty());
        IngestService again = new IngestService(hot, archive, new DeletedFiles(properties.stateDir(), JSON));
        StreamStatus status = again.append(agent, fingerprint, 0, "app1", "digibib", "/logs/x.log",
            Arrays.copyOf(sample, 30_000), 30_000);
        assertEquals(30_000, status.length());
        assertFalse(status.closed());
    }

    @Test
    void deletesWithoutBlock() throws Exception {
        FileService.DeleteResult result = service.delete(fingerprint, false, "admin");
        assertFalse(result.blocked());
        assertTrue(deleted.blocked(fingerprint).isEmpty());
        assertThrows(java.util.NoSuchElementException.class, () -> service.info(fingerprint));
    }
}
