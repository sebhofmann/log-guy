package de.gbv.logguy.server.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.config.LogGuyProperties.HotProperties;
import de.gbv.logguy.server.config.LogGuyProperties.IndexerProperties;
import de.gbv.logguy.server.config.LogGuyProperties.IngestProperties;
import de.gbv.logguy.server.config.LogGuyProperties.RetentionProperties;
import de.gbv.logguy.server.config.LogGuyProperties.ScriptProperties;
import de.gbv.logguy.server.config.LogGuyProperties.SearchProperties;
import de.gbv.logguy.server.solr.SolrService;
import de.gbv.logguy.server.solr.SolrTestContainer;

class RetentionServiceIT {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    @TempDir
    static Path dataDir;

    private static SolrTestContainer solr;

    private static SolrService service;

    private static RetentionService retention;

    @BeforeAll
    static void start() throws Exception {
        solr = new SolrTestContainer();
        service = new SolrService(solr.properties());
        service.init();
        LogGuyProperties properties = new LogGuyProperties(dataDir, ZoneId.of("Europe/Berlin"),
            Map.of("digibib", new AppProperties(null, "mycore-log4j"), "short", new AppProperties(2, "mycore-log4j"),
                "same", new AppProperties(7, "mycore-log4j")),
            List.of(), List.of(), new IngestProperties(DataSize.ofMegabytes(8), 4, Duration.ofSeconds(1)),
            new HotProperties(DataSize.ofKilobytes(64), Duration.ofSeconds(60), Duration.ofSeconds(5), 3),
            new ScriptProperties(dataDir.resolve("scripts"), Duration.ofMillis(50), DataSize.ofKilobytes(256), 10000,
                500),
            solr.properties(),
            new IndexerProperties(true, 2, Duration.ofMillis(100), 500, DataSize.ofKilobytes(64),
                DataSize.ofMegabytes(64), Duration.ofSeconds(5), DataSize.ofMegabytes(1), 7, Duration.ofSeconds(10)),
            new SearchProperties(Duration.ofHours(24), 100, 1000, 300, 8, 10, 100, 20, 500,
                DataSize.ofKilobytes(512)),
            new RetentionProperties("0 30 2 * * *", true), null, null);
        retention = new RetentionService(properties, service, null, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterAll
    static void stop() throws Exception {
        service.close();
        solr.close();
    }

    private static SolrInputDocument document(String id, String app, Instant ts) {
        SolrInputDocument document = new SolrInputDocument();
        document.addField("id", id);
        document.addField("app", app);
        document.addField("host", "app1");
        document.addField("file", "f");
        document.addField("offset", 0L);
        document.addField("ts", Date.from(ts));
        document.addField("message", id);
        return document;
    }

    @Test
    void deletesPerApplication() throws Exception {
        List<SolrInputDocument> documents = new ArrayList<>();
        documents.add(document("digibib-old", "digibib", Instant.parse("2026-10-15T21:59:59Z")));
        documents.add(document("digibib-kept", "digibib", Instant.parse("2026-10-15T22:00:00Z")));
        documents.add(document("short-old", "short", NOW.minus(Duration.ofDays(3))));
        documents.add(document("short-kept", "short", NOW.minus(Duration.ofDays(1))));
        documents.add(document("same-old", "same", NOW.minus(Duration.ofDays(8))));
        documents.add(document("gone-old", "gone", NOW.minus(Duration.ofDays(30))));
        documents.add(document("gone-kept", "gone", NOW));
        service.add(documents);
        service.client().commit();

        assertEquals(List.of("app:(\"short\") AND ts:[* TO 2026-10-20T22:00:00Z}",
            "*:* -app:(\"short\") AND ts:[* TO 2026-10-15T22:00:00Z}"), retention.queries());
        retention.run();

        Set<String> left = new TreeSet<>();
        for (SolrDocument document : service.client().query(new SolrQuery("*:*").setRows(100)).getResults()) {
            left.add((String) document.getFirstValue("id"));
        }
        assertEquals(Set.of("digibib-kept", "short-kept", "gone-kept"), left);
    }
}
