package de.gbv.logguy.server.archive.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.gbv.logguy.server.config.AppRegistry;
import de.gbv.logguy.server.archive.ArchiveFile;
import de.gbv.logguy.server.archive.ArchiveStore;
import de.gbv.logguy.server.archive.search.ArchiveSearchJob.Owner;
import de.gbv.logguy.server.archive.search.ArchiveSearchJob.State;
import de.gbv.logguy.server.archive.search.ArchiveSearchJob.Status;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.config.LogGuyProperties.ArchiveProperties;
import de.gbv.logguy.server.config.LogGuyProperties.ArchiveSearchProperties;
import de.gbv.logguy.server.config.LogGuyProperties.GrantProperties;
import de.gbv.logguy.server.config.LogGuyProperties.Right;
import de.gbv.logguy.server.hot.FileMeta;
import de.gbv.logguy.server.search.SearchException;
import de.gbv.logguy.server.security.LogGuyUser;
import tools.jackson.databind.json.JsonMapper;

class ArchiveSearchTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private static final Instant DAY1 = Instant.parse("2026-10-01T00:00:00Z");

    private static final Instant DAY2 = Instant.parse("2026-10-02T00:00:00Z");

    @TempDir
    Path dataDir;

    private ArchiveStore archive;

    private ArchiveSearch search;

    /** What a reload makes of the user of a job. */
    private Function<LogGuyUser, Optional<LogGuyUser>> reload = Optional::of;

    @BeforeEach
    void setUp() throws Exception {
        LogGuyProperties properties = properties(5);
        archive = new ArchiveStore(properties, JSON);
        search = new ArchiveSearch(properties, new AppRegistry(properties), archive,
            user -> reload.apply(user), Clock.fixed(NOW, ZoneOffset.UTC));

        ArchiveFile file = archive.getOrCreate(meta("fp1", "host1", "digibib"));
        append(file, DAY1, """
            2026-10-01T08:00:00,000 INFO  guest MCRServlet: first line
            2026-10-01T08:00:01,000 \u001B[31mERROR\u001B[m guest MCRSolr: solr is down
            2026-10-01T08:00:02,000 INFO  guest MCRServlet: third line
            """);
        append(file, DAY2, """
            2026-10-02T08:00:00,000 ERROR guest MCRSolr: solr is down again
            2026-10-02T08:00:01,000 INFO  [Nutzer#3f9a1c2e] MCRServlet: login from 203.0.113.0
            2026-10-02T08:00:02,000 INFO  guest MCRServlet: last line
            """);
        file.close(List.of("/logs/mycore.log"));
        ArchiveFile other = archive.getOrCreate(meta("fp2", "host2", "secret"));
        append(other, DAY1, "2026-10-01T08:00:00,000 ERROR guest MCRSolr: solr in secret\n");
    }

    @AfterEach
    void tearDown() {
        search.close();
    }

    private LogGuyProperties properties(int maxHits) {
        return new LogGuyProperties(dataDir, ZoneId.of("Europe/Berlin"),
            Map.of("digibib", new AppProperties(null, "mycore-log4j"), "secret",
                new AppProperties(null, "mycore-log4j")),
            List.of(), List.of(), null, null, null, null, null, null, null, null, null,
            new ArchiveProperties(true, 1, Duration.ofSeconds(1), 3, 7, "[x]", "[f {filter}]",
                new ArchiveSearchProperties(2, 2, Duration.ofDays(1), Duration.ofDays(92), maxHits,
                    Duration.ofMinutes(10), 0, 5, 200, Duration.ofHours(1)),
                null),
            null, null, null);
    }

    private static FileMeta meta(String fingerprint, String host, String app) {
        return new FileMeta(fingerprint, host, app, LocalDate.of(2026, 10, 1), List.of("/logs/mycore.log"), DAY1,
            DAY2, 0, false);
    }

    private static void append(ArchiveFile file, Instant day, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        long src = file.meta().sourceLength();
        file.append(data, src, src + data.length, (int) text.lines().count(), 0, day.plusSeconds(8 * 3600),
            day.plusSeconds(8 * 3600 + 2), day.plusSeconds(9 * 3600), List.of(), List.of("/logs/mycore.log"), 3);
    }

    private static LogGuyUser user(String app, Right... rights) {
        return LogGuyUser.of("anna", List.of(new GrantProperties(List.of(app), Set.of(rights))));
    }

    private static Owner owner() {
        return new Owner("anna", null, "web");
    }

    private static ArchiveSearchRequest request(String query, boolean regex, Integer context) {
        return new ArchiveSearchRequest(List.of("digibib"), List.of(), DAY1, NOW, query, regex, false, context);
    }

    private static ArchiveSearchJob finished(ArchiveSearchJob job) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (job.running()) {
            assertTrue(Instant.now().isBefore(deadline), "job does not finish");
            job.await(Integer.MAX_VALUE, 50);
        }
        return job;
    }

    private static List<String> lines(ArchiveSearchJob job) {
        return job.hits(0, 100).stream().map(ArchiveHit::line).sorted().toList();
    }

    @Test
    void findsLinesWithContextAcrossFrames() throws Exception {
        LogGuyUser user = user("*", Right.READ, Right.ARCHIVE);
        assertEquals(Set.of("digibib", "secret"), search.apps(user));
        ArchiveSearchJob job = finished(search.start(user, owner(), request("solr is down", false,
            1)));
        Status status = job.status();
        assertEquals(State.DONE, status.state());
        assertEquals(List.of("digibib"), status.apps());
        assertFalse(status.pseudonymized());
        assertEquals(1, status.files());
        assertEquals(1, status.filesDone());
        assertEquals(2, status.framesDone());
        assertEquals(status.bytes(), status.bytesDone());
        assertEquals(2, status.hits());

        List<ArchiveHit> hits = job.hits(0, 10).stream().sorted((a, b) -> Long.compare(a.offset(), b.offset()))
            .toList();
        ArchiveHit first = hits.getFirst();
        // ANSI colours are removed, the offset points into the archived file
        assertEquals("2026-10-01T08:00:01,000 ERROR guest MCRSolr: solr is down", first.line());
        assertEquals(List.of("2026-10-01T08:00:00,000 INFO  guest MCRServlet: first line"), first.before());
        assertEquals(List.of("2026-10-01T08:00:02,000 INFO  guest MCRServlet: third line"), first.after());
        assertEquals("host1", first.host());
        assertEquals(59, first.offset());
        ArchiveHit second = hits.get(1);
        assertEquals(List.of("2026-10-01T08:00:02,000 INFO  guest MCRServlet: third line"), second.before());
        assertEquals(List.of("2026-10-02T08:00:01,000 INFO  [Nutzer#3f9a1c2e] MCRServlet: login from 203.0.113.0"),
            second.after());
    }

    @Test
    void regularExpressionsWorkPerLine() throws Exception {
        LogGuyUser user = user("digibib", Right.READ, Right.ARCHIVE);
        assertEquals(List.of("2026-10-01T08:00:00,000 INFO  guest MCRServlet: first line",
            "2026-10-02T08:00:02,000 INFO  guest MCRServlet: last line"),
            lines(finished(search.start(user, owner(), request("^\\S+ INFO .*(first|last)",
                true, null)))));
        // a match across the line end does not count
        assertEquals(List.of(), lines(finished(search.start(user, owner(),
            request("first line\\s+2026", true, null)))));
        assertEquals(2, finished(search.start(user, owner(), new ArchiveSearchRequest(List.of(),
            List.of(), DAY1, NOW, "SOLR IS", false, true, null))).status().hits());
        SearchException invalid = assertThrows(SearchException.class,
            () -> search.start(user, owner(), request("(a", true, null)));
        assertTrue(invalid.getMessage().startsWith("Ungültiger regulärer Ausdruck"), invalid.getMessage());
        assertThrows(SearchException.class, () -> search.start(user, owner(),
            request("(a)\\1", true, null)));
    }

    @Test
    void timeRangeSkipsFrames() throws Exception {
        LogGuyUser user = user("digibib", Right.READ, Right.ARCHIVE);
        ArchiveSearchJob job = finished(search.start(user, owner(), new ArchiveSearchRequest(
            List.of(), List.of(), DAY2, NOW, "solr", false, false, null)));
        assertEquals(1, job.status().frames());
        assertEquals(List.of("2026-10-02T08:00:00,000 ERROR guest MCRSolr: solr is down again"), lines(job));

        ArchiveSearchJob none = finished(search.start(user, owner(), new ArchiveSearchRequest(
            List.of(), List.of("host2"), DAY1, NOW, "solr", false, false, null)));
        assertEquals(0, none.status().files());
        assertEquals(State.DONE, none.status().state());

        SearchException tooLong = assertThrows(SearchException.class,
            () -> search.start(user, owner(),
                new ArchiveSearchRequest(List.of(), List.of(), NOW.minus(Duration.ofDays(93)), NOW,
                    "solr", false, false, null)));
        assertTrue(tooLong.getMessage().contains("92 Tage"), tooLong.getMessage());
        // without from the default range of one day applies
        ArchiveSearchJob recent = finished(search.start(user, owner(), new ArchiveSearchRequest(
            List.of(), List.of(), null, null, "solr", false, false, null)));
        assertEquals(NOW.minus(Duration.ofDays(1)), recent.status().from());
        assertEquals(0, recent.status().hits());
    }

    @Test
    void rightsLimitTheApplications() {
        assertEquals(Set.of(), search.apps(user("*", Right.READ)));
        assertThrows(SearchException.class,
            () -> search.start(user("*", Right.READ), owner(), request("solr", false, null)));
        LogGuyUser digibib = user("digibib", Right.READ, Right.ARCHIVE);
        assertEquals(Set.of("digibib"), search.apps(digibib));
        SearchException forbidden = assertThrows(SearchException.class,
            () -> search.start(digibib, owner(),
                new ArchiveSearchRequest(List.of("secret"), List.of(), DAY1, NOW, "solr", false,
                    false, null)));
        assertTrue(forbidden.getMessage().contains("secret"), forbidden.getMessage());
        assertThrows(SearchException.class,
            () -> search.start(digibib, owner(), request(" ", false, null)));
        assertThrows(SearchException.class,
            () -> search.start(digibib, owner(), request("solr", false, 6)));
    }

    @Test
    void reloadThatTakesRightsAwayDropsTheJob() throws Exception {
        LogGuyUser user = user("digibib", Right.READ, Right.ARCHIVE);
        ArchiveSearchJob plain = finished(search.start(user, owner(),
            request("solr", false, null)));
        assertTrue(plain.status().hits() > 0);
        reload = before -> Optional.of(user("digibib", Right.READ, Right.ARCHIVE, Right.DELETE));
        assertEquals(plain, search.job(owner(), plain.id()), "more rights keep the job");

        reload = before -> Optional.of(user("digibib", Right.READ_PSEUDONYMIZED, Right.ARCHIVE));
        NoSuchElementException dropped = assertThrows(NoSuchElementException.class,
            () -> search.job(owner(), plain.id()));
        assertTrue(dropped.getMessage().contains("Rechte"), dropped.getMessage());
        assertEquals(List.of(), search.jobs(owner()));

        LogGuyUser pseudonymized = user("digibib", Right.READ_PSEUDONYMIZED, Right.ARCHIVE);
        ArchiveSearchJob kept = finished(search.start(pseudonymized, owner(),
            request("solr", false, null)));
        assertEquals(List.of(kept), search.jobs(owner()), "pseudonymized hits stay readable");

        reload = before -> Optional.empty();
        assertEquals(List.of(), search.jobs(owner()), "the user was removed");
    }

    @Test
    void runningJobStopsWhenTheRightsAreGone() throws Exception {
        reload = before -> Optional.of(user("rolf", Right.READ, Right.ARCHIVE));
        ArchiveSearchJob job =
            finished(search.start(user("digibib", Right.READ, Right.ARCHIVE), owner(), request("solr", false, null)));
        assertEquals(State.CANCELLED, job.status().state());
        assertEquals(0, job.status().hits());
        assertTrue(job.status().message().contains("Rechte"), job.status().message());
    }

    @Test
    void jobsBelongToTheirOwner() throws Exception {
        LogGuyUser user = user("digibib", Right.READ, Right.ARCHIVE);
        ArchiveSearchJob job = finished(search.start(user, owner(), request("solr", false, null)));
        assertEquals(job, search.job(owner(), job.id()));
        assertEquals(List.of(job), search.jobs(owner()));
        Owner other = new Owner("bert", null, "web");
        assertThrows(NoSuchElementException.class, () -> search.job(other, job.id()));
        assertThrows(NoSuchElementException.class, () -> search.cancel(other, job.id()));
        assertEquals(List.of(), search.jobs(other));
        assertThrows(NoSuchElementException.class, () -> search.job(new Owner("anna", "token", "web"), job.id()));
        // a finished job stays finished
        assertEquals(State.DONE, search.cancel(owner(), job.id()).status().state());
    }

    @Test
    void stopsAtTheHitLimit() throws Exception {
        search.close();
        search = new ArchiveSearch(properties(2), new AppRegistry(properties(2)), archive,
            user -> reload.apply(user), Clock.fixed(NOW, ZoneOffset.UTC));
        ArchiveSearchJob job =
            finished(search.start(user("digibib", Right.READ, Right.ARCHIVE), owner(), request("2026", false, null)));
        assertEquals(State.LIMIT, job.status().state());
        assertEquals(2, job.status().hits());
        assertTrue(job.status().message().contains("Trefferlimit"), job.status().message());
    }

    @Test
    void pseudonymizedJobsSearchTheArchivedLine() throws Exception {
        LogGuyUser user = user("digibib", Right.READ_PSEUDONYMIZED, Right.ARCHIVE);
        assertEquals(Set.of("digibib"), search.apps(user));
        ArchiveSearchJob login = finished(search.start(user, owner(), request("login from", false, 1)));
        assertTrue(login.status().pseudonymized());
        ArchiveHit hit = login.hits(0, 1).getFirst();
        // the line as it is archived, not pseudonymized again
        assertEquals("2026-10-02T08:00:01,000 INFO  [Nutzer#3f9a1c2e] MCRServlet: login from 203.0.113.0",
            hit.line());
        assertEquals(List.of("2026-10-02T08:00:00,000 ERROR guest MCRSolr: solr is down again"), hit.before());
        assertEquals(List.of(hit.line()), lines(finished(search.start(user, owner(),
            request("[Nutzer#3f9a1c2e]", false, null)))));

        // #15: an alternation cannot combine a check of the real line with one of the pseudonymized line
        assertEquals(List.of(hit.line()), lines(finished(search.start(user, owner(),
            request("mueller|\\[Nutzer#", true, null)))));
        assertEquals(0, finished(search.start(user, owner(), request("mueller", false, null))).status().hits());
    }
}
