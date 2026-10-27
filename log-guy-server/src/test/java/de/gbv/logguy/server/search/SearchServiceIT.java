package de.gbv.logguy.server.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.solr.client.solrj.SolrQuery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.unit.DataSize;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.common.TestLogs;
import de.gbv.logguy.server.archive.LifecycleFilters;
import de.gbv.logguy.server.config.AppGroups;
import de.gbv.logguy.server.config.AppRegistry;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.config.LogGuyProperties.ArchiveProperties;
import de.gbv.logguy.server.config.LogGuyProperties.ArchivePseudonymizeProperties;
import de.gbv.logguy.server.config.LogGuyProperties.GrantProperties;
import de.gbv.logguy.server.config.LogGuyProperties.HotProperties;
import de.gbv.logguy.server.config.LogGuyProperties.IndexerProperties;
import de.gbv.logguy.server.config.LogGuyProperties.IngestProperties;
import de.gbv.logguy.server.config.LogGuyProperties.Right;
import de.gbv.logguy.server.config.LogGuyProperties.ScriptProperties;
import de.gbv.logguy.server.config.LogGuyProperties.SearchProperties;
import de.gbv.logguy.server.hot.HotFile;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.index.Indexer;
import de.gbv.logguy.server.pseudonym.PseudonymService;
import de.gbv.logguy.server.pseudonym.Pseudonyms;
import de.gbv.logguy.server.script.ParserRegistry;
import de.gbv.logguy.server.script.ScriptRuntime;
import de.gbv.logguy.server.script.ScriptStore;
import de.gbv.logguy.server.search.ContextService.ContextView;
import de.gbv.logguy.server.search.SearchService.EventView;
import de.gbv.logguy.server.search.SearchService.FacetValue;
import de.gbv.logguy.server.search.SearchService.Histogram;
import de.gbv.logguy.server.search.SearchService.SearchResult;
import de.gbv.logguy.server.security.LogGuyUser;
import de.gbv.logguy.server.solr.SolrService;
import de.gbv.logguy.server.solr.SolrTestContainer;
import tools.jackson.databind.json.JsonMapper;

class SearchServiceIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The sample covers 2026-10-01 00:00 to 00:05 in Berlin. */
    private static final Instant FROM = Instant.parse("2026-09-30T21:00:00Z");

    private static final Instant TO = Instant.parse("2026-09-30T23:00:00Z");

    private static final byte[] SAMPLE = TestLogs.mycoreSample();

    @TempDir
    static Path dataDir;

    private static SolrTestContainer solr;

    private static SolrService service;

    private static ScriptRuntime runtime;

    private static HotStore store;

    private static SearchService search;

    private static ContextService context;

    private static PseudonymService pseudonyms;

    private static PseudonymizedSearch pseudonymized;

    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b");

    private static final LogGuyUser READER =
        LogGuyUser.of("reader", List.of(new GrantProperties(List.of("digi*"), Set.of(Right.READ))));

    private static final LogGuyUser OTHER =
        LogGuyUser.of("other", List.of(new GrantProperties(List.of("other"), Set.of(Right.READ))));

    private static final LogGuyUser PSEUDONYMIZED = LogGuyUser.of("pseudo",
        List.of(new GrantProperties(List.of("digi*"), Set.of(Right.READ_PSEUDONYMIZED))));

    private static final LogGuyUser MIXED = LogGuyUser.of("mixed", List.of(
        new GrantProperties(List.of("other"), Set.of(Right.READ)),
        new GrantProperties(List.of("digibib"), Set.of(Right.READ_PSEUDONYMIZED))));

    @BeforeAll
    static void index() throws Exception {
        solr = new SolrTestContainer();
        service = new SolrService(solr.properties());
        service.init();
        runtime = new ScriptRuntime(Duration.ofMillis(50), Duration.ofSeconds(5));
        LogGuyProperties properties = new LogGuyProperties(dataDir, ZoneId.of("Europe/Berlin"),
            Map.of("digibib", new AppProperties(null, "mycore-log4j", List.of("mir", "produktiv")), "other",
                new AppProperties(null, "mycore-log4j", List.of("mir"))),
            List.of(), List.of(), new IngestProperties(DataSize.ofMegabytes(8), 4, Duration.ofSeconds(1)),
            new HotProperties(DataSize.ofKilobytes(64), Duration.ofSeconds(60), Duration.ofSeconds(5), 3),
            new ScriptProperties(dataDir.resolve("scripts"), Duration.ofMillis(50), Duration.ofSeconds(5), 100,
                DataSize.ofKilobytes(256), 10000,
                500),
            solr.properties(),
            new IndexerProperties(true, 2, Duration.ofMillis(100), 500, DataSize.ofKilobytes(64),
                DataSize.ofMegabytes(64), Duration.ofSeconds(5), DataSize.ofMegabytes(1), 7, Duration.ofSeconds(10)),
            new SearchProperties(Duration.ofHours(24), 100, 1000, 300, 8, 10, 100, 20, 500,
                DataSize.ofKilobytes(512)),
            null, null, null,
            new ArchiveProperties(true, 2, Duration.ofMillis(50), 3, 7, "[line {filter} {offset}]",
                "[field {filter}]", null, new ArchivePseudonymizeProperties(List.of())),
            null, null, null, null);
        store = new HotStore(properties, JSON, Clock.systemUTC());
        HotFile file = store.getOrCreate(Fingerprint.of(SAMPLE), "app1", "digibib");
        file.append(0, SAMPLE, SAMPLE.length, null, Instant.now());
        file.close(SAMPLE.length, null, Instant.now());
        ParserRegistry parsers = new ParserRegistry(properties, runtime);
        assertTrue(parsers.load().isEmpty());
        try (Indexer indexer =
            new Indexer(properties, store, parsers, runtime, null, null, service::add, Clock.systemUTC(),
                JSON)) {
            Instant deadline = Instant.now().plusSeconds(60);
            while (indexer.backlog() > 0 || indexer.indexedEvents() == 0) {
                assertTrue(Instant.now().isBefore(deadline), "indexing timed out");
                Thread.sleep(100);
            }
        }
        service.client().commit();
        search = new SearchService(properties, new AppRegistry(properties), parsers, service, Clock.systemUTC(), JSON);
        ScriptStore scripts = new ScriptStore(dataDir.resolve("scripts"), Clock.systemUTC(), JSON);
        scripts.scan();
        pseudonyms = new PseudonymService(runtime, scripts, JSON, () -> AppGroups.NONE);
        context = new ContextService(properties, store, null,
            new LifecycleFilters(runtime, scripts, JSON, () -> AppGroups.NONE), pseudonyms);
        pseudonymized = new PseudonymizedSearch(search, pseudonyms);
    }

    @AfterAll
    static void stop() throws Exception {
        pseudonyms.close();
        store.close();
        runtime.close();
        service.close();
        solr.close();
    }

    private static SearchScope scope(LogGuyUser user, String query, Map<String, List<String>> filters) {
        return search.scope(user, new SearchRequest(List.of(), query, FROM, TO, filters));
    }

    private static long solrCount(String query) throws Exception {
        return service.client().query(new SolrQuery(query).setRows(0)).getResults().getNumFound();
    }

    @Test
    void pagesThroughAllEventsWithCursor() throws Exception {
        SearchScope scope = scope(READER, null, Map.of());
        long total = solrCount("*:*");
        Set<String> ids = new HashSet<>();
        String cursor = null;
        Instant last = Instant.MAX;
        SearchResult page;
        do {
            page = search.search(scope, cursor, 300, false);
            assertEquals(total, page.total());
            for (EventView event : page.events()) {
                assertTrue(ids.add(event.id()), "duplicate " + event.id());
                assertFalse(event.ts().isAfter(last));
                last = event.ts();
            }
            cursor = page.cursor();
        } while (!page.done());
        assertEquals(total, ids.size());
    }

    @Test
    void translatesFieldsAndFilters() throws Exception {
        SearchResult errors = search.search(scope(READER, "level:ERROR", Map.of()), null, 10, true);
        assertEquals(solrCount("level_s:ERROR"), errors.total());
        assertEquals("ERROR", errors.events().getFirst().fields().get("level"));
        assertEquals("digibib", errors.events().getFirst().app());

        SearchResult filtered = search.search(scope(READER, null, Map.of("level", List.of("ERROR", "WARN"))), null,
            1, true);
        assertEquals(solrCount("level_s:(ERROR OR WARN)"), filtered.total());

        assertThrows(SearchException.class, () -> scope(READER, "level_s:ERROR", Map.of()));
        assertThrows(SearchException.class, () -> scope(READER, "{!join from=id to=id}x", Map.of()));
        assertThrows(SearchException.class, () -> search.scope(READER,
            new SearchRequest(List.of("other"), null, FROM, TO, Map.of())));
    }

    @Test
    void appFilterCannotBeRemoved() throws Exception {
        assertEquals(0, search.search(scope(OTHER, "*:*", Map.of()), null, 10, false).total());
        assertEquals(0, search.search(scope(OTHER, "app:digibib", Map.of()), null, 10, false).total());
        assertEquals(0, search.search(scope(OTHER, "*:* OR app:digibib", Map.of()), null, 10, false).total());
    }

    @Test
    void groupsStandForTheirReadableMembers() throws Exception {
        assertEquals(Set.of("digibib"), search.apps(READER, List.of("@produktiv")));
        assertEquals(Set.of("digibib"), search.apps(READER, List.of("@mir")));
        assertEquals(Set.of("other"), search.apps(OTHER, List.of("@mir")));
        assertThrows(SearchException.class, () -> search.apps(OTHER, List.of("@produktiv")));
        assertThrows(SearchException.class, () -> search.apps(READER, List.of("@unbekannt")));
        SearchService.FieldsView view = search.describeFields(OTHER, List.of("@mir"));
        assertEquals(Map.of("mir", List.of("other")), view.groups());
        assertEquals(List.of("other"), view.selected());
        long all = search.search(scope(READER, "*:*", Map.of()), null, 10, false).total();
        SearchScope group = search.scope(READER, new SearchRequest(List.of("@produktiv"), "*:*", FROM, TO,
            Map.of()));
        assertEquals(all, search.search(group, null, 10, false).total());
    }

    @Test
    void histogramCoversRangeAndSplitsByLevel() throws Exception {
        Histogram histogram = search.histogram(scope(READER, null, Map.of()), "level");
        assertEquals(30, histogram.interval());
        assertEquals(240, histogram.times().size());
        assertEquals(solrCount("*:*"), histogram.total().stream().mapToLong(Long::longValue).sum());
        assertTrue(histogram.series().containsKey("INFO"));
        long errors = histogram.series().get("ERROR").stream().mapToLong(Long::longValue).sum();
        assertEquals(solrCount("level_s:ERROR"), errors);
    }

    @Test
    void facetsIgnoreTheirOwnFilter() throws Exception {
        Map<String, List<FacetValue>> facets = search.facets(scope(READER, null, Map.of("level", List.of("ERROR"))),
            List.of("level", "logger"), 5);
        assertTrue(facets.get("level").size() > 1);
        assertTrue(facets.get("logger").size() <= 5);
        long loggers = facets.get("logger").stream().mapToLong(FacetValue::count).sum();
        assertTrue(loggers > 0 && loggers <= solrCount("level_s:ERROR"));
    }

    @Test
    void fixedFieldsCanBeFacetedAndFiltered() throws Exception {
        long all = solrCount("*:*");
        Map<String, List<FacetValue>> facets = search.facets(scope(READER, null, Map.of("app", List.of("digibib"))),
            List.of("app", "host"), 10);
        assertEquals(List.of(new FacetValue("digibib", all)), facets.get("app"));
        assertEquals(all, facets.get("host").stream().mapToLong(FacetValue::count).sum());
        assertEquals(all, search.search(scope(READER, null, Map.of("app", List.of("digibib"))), null, 10, false)
            .total());
        assertEquals(all, search.search(scope(READER, "app:digibib", Map.of()), null, 10, false).total());
    }

    @Test
    void contextReadsOriginalLines() throws Exception {
        EventView event = search.search(scope(READER, "level:ERROR", Map.of()), null, 1, true).events().getFirst();
        ContextView view = context.context(READER, event.id(), 5, 3);
        assertEquals(5, view.before().size());
        assertEquals(3, view.lines().size());
        assertTrue(view.lines().getFirst().contains("ERROR"), view.lines().getFirst());
        assertFalse(view.lines().getFirst().contains("\u001B"));
        assertThrows(AccessDeniedException.class, () -> context.context(OTHER, event.id(), 5, 3));

        ContextView first = context.context(READER, Fingerprint.of(SAMPLE) + ":0", 5, 1);
        assertTrue(first.start());
        assertEquals(0, first.before().size());
    }

    @Test
    void pseudonymizedReadersSeePseudonymsOnly() throws Exception {
        assertTrue(search.apps(PSEUDONYMIZED, List.of()).isEmpty());
        assertFalse(pseudonymized.searchUser(READER, List.of()).pseudonymized());
        assertTrue(pseudonymized.searchUser(PSEUDONYMIZED, List.of()).pseudonymized());
        assertFalse(pseudonymized.searchUser(MIXED, List.of("other")).pseudonymized());
        assertTrue(pseudonymized.searchUser(MIXED, List.of()).pseudonymized());
        assertEquals(List.of("digibib"), search.describeFields(MIXED, List.of()).pseudonymized());
        assertEquals(List.of(), search.describeFields(READER, List.of()).pseudonymized());

        LogGuyUser user = pseudonymized.searchUser(PSEUDONYMIZED, List.of());
        Pseudonyms session = new Pseudonyms();
        SearchScope scope = pseudonymized.scope(user, session, new SearchRequest(List.of(), "ip", FROM, TO,
            Map.of()));
        SearchResult plain = search.search(scope, null, 50, true);
        assertTrue(plain.events().stream().anyMatch(e -> IPV4.matcher(e.message()).find()));
        SearchResult result = pseudonymized.result(session, plain);
        assertEquals(plain.total(), result.total());
        for (EventView event : result.events()) {
            assertFalse(IPV4.matcher(event.message()).find(), event.message());
            assertFalse(PseudonymizedSearch.HELD_BACK.equals(event.message()));
        }
        assertTrue(result.events().stream().anyMatch(e -> e.message().contains("$IP1")));

        Map<String, List<FacetValue>> facets = pseudonymized.facets(session, scope.apps(),
            search.facets(scope, List.of("level"), 10), value -> value);
        assertFalse(facets.get("level").isEmpty());

        ContextView view = pseudonymized.context(context.context(user, result.events().getFirst().id(), 0,
            20), line -> line);
        view.lines().forEach(line -> assertFalse(IPV4.matcher(line).find(), line));
        assertThrows(AccessDeniedException.class, () -> context.context(PSEUDONYMIZED, view.id(), 0, 1));
    }
}
