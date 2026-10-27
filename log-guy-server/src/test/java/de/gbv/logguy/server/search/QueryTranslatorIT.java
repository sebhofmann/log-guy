package de.gbv.logguy.server.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import de.gbv.logguy.server.solr.SolrService;
import de.gbv.logguy.server.solr.SolrTestContainer;

/**
 * Checks the {@link QueryTranslator} against the query parser of a real Solr. Two events differ only in
 * {@code login_s}. A query that does not search {@code login_s} finds both or none, so exactly one hit shows that
 * Solr read {@code login_s} as a field.
 */
class QueryTranslatorIT {

    /** Declared fields without {@code login}, so {@code login_s} must never reach Solr. */
    private static final Map<String, String> FIELDS = Map.of("message", "message", "level", "level_s");

    /** The ways to hide a field name from the old translator, from the issues #2 and #5 and beyond. */
    private static final List<String> ATTACKS = List.of("lo\\gin_s:canary", "\\_query_:\"login_s:canary\"",
        "(*:*)login_s:canary", "\"x\"login_s:canary", "level_s:[A TO Z]login_s:canary",
        "level_s:{A TO Z}login_s:canary", "level_s:[A TO \"]\"]login_s:canary", "(*:*)_query_:\"login_s:canary\"",
        "x^2login_s:canary", "x^=2login_s:canary", "(x)^2login_s:canary", "x~login_s:canary", "x~2login_s:canary",
        "\"x\"~2login_s:canary", "/x/login_s:canary", "/* c */login_s:canary", "/* a /* b */ c */login_s:canary",
        "login_s :canary", "login_s\n:canary", "x\u3000login_s:canary", "+login_s:canary",
        "filter(login_s:canary)", "{!term f=login_s}canary", "x {!term f=login_s}canary");

    private static SolrTestContainer solr;

    private static SolrService service;

    @BeforeAll
    static void start() throws Exception {
        solr = new SolrTestContainer();
        service = new SolrService(solr.properties());
        service.init();
        service.add(List.of(event("a", "canary"), event("b", "other")));
        service.client().commit();
    }

    @AfterAll
    static void stop() throws Exception {
        service.close();
        solr.close();
    }

    private static SolrInputDocument event(String id, String login) {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("id", id);
        doc.addField("app", "app");
        doc.addField("host", "host");
        doc.addField("file", "file");
        doc.addField("offset", 0L);
        doc.addField("ts", java.util.Date.from(Instant.parse("2026-10-01T00:00:00Z")));
        doc.addField("message", "x y login_s canary");
        doc.addField("level_s", "E");
        doc.addField("login_s", login);
        return doc;
    }

    /** Number of hits, -1 if Solr rejects the query. */
    private static long count(String query) throws Exception {
        try {
            return service.client().query(new SolrQuery(query).setRows(0).set("q.op", "AND")).getResults()
                .getNumFound();
        } catch (SolrException | SolrServerException e) {
            return -1;
        }
    }

    @Test
    void rejectsEveryAttackSolrWouldRun() throws Exception {
        for (String attack : ATTACKS) {
            assertEquals(1, count(attack), "Solr does not read login_s in " + attack);
            assertThrows(SearchException.class, () -> QueryTranslator.translate(attack, FIELDS), attack);
        }
    }

    @Test
    void translatedQueriesNeverReachUndeclaredFields() throws Exception {
        assertEquals(2, count(QueryTranslator.translate("level:E message:x", FIELDS)));
        assertEquals(2, count(QueryTranslator.translate("\"login_s canary\" /* login_s:canary */", FIELDS)));
        List<String> parts = List.of("login_s", ":", "canary", "x", "level", "message", "\"", "(", ")", "[", "]",
            "{", "}", " TO ", "/", "*", "\\", "^2", "~", " ", "/*", "*/", "\u3000", "+", "-", "!", "AND ", "E");
        Random random = new Random(4711);
        int accepted = 0;
        for (int i = 0; i < 3000; i++) {
            StringBuilder query = new StringBuilder();
            for (int j = random.nextInt(2, 10); j > 0; j--) {
                query.append(parts.get(random.nextInt(parts.size())));
            }
            String translated;
            try {
                translated = QueryTranslator.translate(query.toString(), FIELDS);
            } catch (SearchException e) {
                continue;
            }
            accepted++;
            assertNotEquals(1, count(translated), "login_s reached Solr with " + query + " as " + translated);
        }
        assertTrue(accepted > 300, "too few accepted queries: " + accepted);
    }
}
