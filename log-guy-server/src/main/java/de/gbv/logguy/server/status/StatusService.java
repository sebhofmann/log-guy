package de.gbv.logguy.server.status;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.json.BucketBasedJsonFacet;
import org.apache.solr.client.solrj.response.json.BucketJsonFacet;
import org.apache.solr.client.solrj.response.json.NestableJsonFacet;

import de.gbv.logguy.server.alert.Deliveries;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.config.LogGuyProperties.TokenKind;
import de.gbv.logguy.server.config.LogGuyProperties.TokenProperties;
import de.gbv.logguy.server.hot.FileMeta;
import de.gbv.logguy.server.hot.HotFile;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.archive.ArchiveFile;
import de.gbv.logguy.server.archive.ArchiveStore;
import de.gbv.logguy.server.backup.BackupService;
import de.gbv.logguy.server.archive.ArchiveWriter;
import de.gbv.logguy.server.index.Indexer;
import de.gbv.logguy.server.index.Indexer.Backlog;
import de.gbv.logguy.server.ingest.IngestService;
import de.gbv.logguy.server.solr.SolrService;
import de.gbv.logguy.server.solr.SolrService.ReplicaInfo;
import tools.jackson.databind.json.JsonMapper;

/**
 * Collects the numbers of the status page: system, agents, applications and Solr.
 */
public class StatusService {

    private static final Logger LOGGER = LogManager.getLogger();

    private final LogGuyProperties properties;

    private final HotStore store;

    private final Indexer indexer;

    private final ArchiveStore archive;

    private final ArchiveWriter writer;

    private final IngestService ingest;

    private final Deliveries deliveries;

    private final BackupService backup;

    private final SolrService solr;

    private final Clock clock;

    private final JsonMapper json;

    private final String version;

    /**
     * Creates the service.
     *
     * @param properties configuration
     * @param store the hot storage
     * @param indexer the indexer, {@code null} if it is disabled
     * @param archive the archive
     * @param writer the archive writer, {@code null} if it is disabled
     * @param ingest the stream API
     * @param deliveries the queue of mails and webhooks
     * @param backup the backup
     * @param solr connection to Solr
     * @param clock clock for the last hour
     * @param json mapper for facet requests
     * @param version version of log-guy, {@code null} if unknown
     */
    public StatusService(LogGuyProperties properties, HotStore store, Indexer indexer, ArchiveStore archive,
        ArchiveWriter writer, IngestService ingest, Deliveries deliveries, BackupService backup, SolrService solr,
        Clock clock, JsonMapper json, String version) {
        this.properties = properties;
        this.store = store;
        this.indexer = indexer;
        this.archive = archive;
        this.writer = writer;
        this.ingest = ingest;
        this.deliveries = deliveries;
        this.backup = backup;
        this.solr = solr;
        this.clock = clock;
        this.json = json;
        this.version = version;
    }

    /**
     * Collects the current status. Solr errors end up in the result, not as an exception.
     *
     * @return the status
     */
    public StatusView status() {
        Instant now = clock.instant();
        SolrView solrView;
        Map<String, AppCounts> counts = Map.of();
        try {
            counts = appCounts(now);
            List<ReplicaInfo> replicas = solr.replicas();
            long documents = counts.values().stream().mapToLong(AppCounts::indexed).sum();
            solrView = new SolrView(true, null, documents, replicas);
        } catch (Exception e) {
            LOGGER.warn("Solr status failed", e);
            solrView = new SolrView(false, e.getMessage(), -1, List.of());
        }
        return new StatusView(now, system(), indexerView(), archiveView(), deliveries.stats(), backup.status(),
            agents(),
            apps(counts),
            solrView);
    }

    private SystemView system() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Instant started = Instant.ofEpochMilli(runtime.getStartTime());
        long diskFree = -1;
        long diskTotal = -1;
        try {
            FileStore fileStore = Files.getFileStore(properties.dataDir());
            diskFree = fileStore.getUsableSpace();
            diskTotal = fileStore.getTotalSpace();
        } catch (IOException e) {
            LOGGER.warn("cannot read free space of {}", properties.dataDir(), e);
        }
        return new SystemView(version, Runtime.version().toString(), started,
            Duration.ofMillis(runtime.getUptime()), os.getAvailableProcessors(), os.getSystemLoadAverage(),
            heap.getUsed(), heap.getMax(), diskFree, diskTotal, directorySize(properties.hotDir()),
            store.files().size());
    }

    private static long directorySize(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            LOGGER.warn("cannot read size of {}", dir, e);
            return -1;
        }
    }

    private IndexerView indexerView() {
        if (indexer == null) {
            return new IndexerView(false, 0, 0, 0, 0, 0);
        }
        Map<String, Backlog> backlog = indexer.backlogByApp();
        long parseErrors = indexer.parseErrors().values().stream().mapToLong(Long::longValue).sum();
        return new IndexerView(true, indexer.indexedEvents(), indexer.indexedBytes(), parseErrors,
            backlog.values().stream().mapToLong(Backlog::files).sum(),
            backlog.values().stream().mapToLong(Backlog::bytes).sum());
    }

    private ArchiveView archiveView() {
        long bytes = archive.files().stream().mapToLong(ArchiveFile::compressedSize).sum();
        int files = archive.files().size();
        if (writer == null) {
            return new ArchiveView(false, 0, 0, 0, 0, List.of(), bytes, files);
        }
        return new ArchiveView(true, writer.archivedLines(), writer.droppedLines(), writer.filterErrors(),
            writer.backlog(), writer.pausedBy(), bytes, files);
    }

    private List<AgentView> agents() {
        Map<String, Instant> seen = ingest.lastSeen();
        Map<String, Instant> received = new HashMap<>();
        Map<String, Integer> open = new HashMap<>();
        for (HotFile file : store.files()) {
            FileMeta meta = file.meta();
            received.merge(meta.host(), meta.lastReceived(), (a, b) -> a.isAfter(b) ? a : b);
            if (!meta.closed()) {
                open.merge(meta.host(), 1, Integer::sum);
            }
        }
        List<AgentView> agents = new ArrayList<>();
        for (TokenProperties token : properties.tokens()) {
            if (token.kind() == TokenKind.INGEST) {
                agents.add(new AgentView(token.name(), token.host(), token.apps(), seen.get(token.name()),
                    received.get(token.host()), open.getOrDefault(token.host(), 0)));
            }
        }
        return agents;
    }

    private List<AppView> apps(Map<String, AppCounts> counts) {
        Map<String, Backlog> backlog = indexer == null ? Map.of() : indexer.backlogByApp();
        Map<String, Long> parseErrors = indexer == null ? Map.of() : indexer.parseErrors();
        Set<String> names = new TreeSet<>(properties.apps().keySet());
        names.addAll(counts.keySet());
        List<AppView> apps = new ArrayList<>();
        for (String name : names) {
            AppProperties app = properties.apps().get(name);
            AppCounts count = counts.getOrDefault(name, new AppCounts(null, 0, 0, 0));
            Backlog behind = backlog.getOrDefault(name, new Backlog(0, 0));
            String parser = app == null ? null : app.parser() != null ? app.parser() : name;
            int indexDays = app != null && app.indexDays() != null ? app.indexDays()
                : properties.indexer().indexDays();
            apps.add(new AppView(name, app != null, parser, indexDays, count.lastEvent(), count.lastHour(),
                count.indexed(), count.parseErrors(), parseErrors.getOrDefault(name, 0L), behind.files(),
                behind.bytes()));
        }
        return apps;
    }

    private Map<String, AppCounts> appCounts(Instant now) throws Exception {
        Map<String, Object> facet = Map.of("type", "terms", "field", "app", "limit", -1, "facet", Map.of(
            "last", "max(ts)",
            "hour", Map.of("type", "query", "q", "ts:[" + now.minus(Duration.ofHours(1)) + " TO *]"),
            "errors", Map.of("type", "query", "q", "parse_error:*")));
        SolrQuery query = new SolrQuery("*:*").setRows(0);
        query.set("json.facet", json.writeValueAsString(Map.of("apps", facet)));
        NestableJsonFacet response = solr.client().query(query).getJsonFacetingResponse();
        Map<String, AppCounts> counts = new HashMap<>();
        BucketBasedJsonFacet apps = response == null ? null : response.getBucketBasedFacets("apps");
        if (apps == null) {
            return counts;
        }
        for (BucketJsonFacet bucket : apps.getBuckets()) {
            Object last = bucket.getStatValue("last");
            Instant lastEvent = last instanceof Date date ? date.toInstant() : null;
            NestableJsonFacet hour = bucket.getQueryFacet("hour");
            NestableJsonFacet errors = bucket.getQueryFacet("errors");
            counts.put(String.valueOf(bucket.getVal()), new AppCounts(lastEvent, hour == null ? 0 : hour.getCount(),
                bucket.getCount(), errors == null ? 0 : errors.getCount()));
        }
        return counts;
    }

    private record AppCounts(Instant lastEvent, long lastHour, long indexed, long parseErrors) {
    }

    /**
     * The whole status.
     *
     * @param generated when it was collected
     * @param system the server
     * @param indexer the indexer
     * @param archive the archive writer
     * @param notifications mails and webhooks since the start
     * @param agents one entry per ingest token
     * @param apps one entry per configured or indexed application
     * @param solr the Solr collection
     */
    public record StatusView(Instant generated, SystemView system, IndexerView indexer, ArchiveView archive,
        Deliveries.Stats notifications, BackupService.Status backup, List<AgentView> agents, List<AppView> apps,
        SolrView solr) {
    }

    /**
     * The server.
     *
     * @param version version of log-guy, {@code null} if unknown
     * @param java version of Java
     * @param started start of the JVM
     * @param uptime time since the start
     * @param cpus available processors
     * @param load system load average of the last minute, negative if unknown
     * @param heapUsed used heap in bytes
     * @param heapMax largest heap in bytes
     * @param diskFree usable bytes on the disk of the data directory
     * @param diskTotal size of that disk
     * @param hotBytes bytes in {@code hot/}
     * @param hotFiles files in {@code hot/}
     */
    public record SystemView(String version, String java, Instant started, Duration uptime, int cpus, double load,
        long heapUsed, long heapMax, long diskFree, long diskTotal, long hotBytes, int hotFiles) {
    }

    /**
     * The indexer since the start.
     *
     * @param enabled whether it runs
     * @param events indexed events
     * @param bytes indexed bytes
     * @param parseErrors events with parse errors
     * @param backlogFiles files with a backlog
     * @param backlogBytes bytes not yet indexed
     */
    public record IndexerView(boolean enabled, long events, long bytes, long parseErrors, long backlogFiles,
        long backlogBytes) {
    }

    /**
     * The archive writer since the start and the size of {@code archive/}.
     *
     * @param enabled whether it runs
     * @param lines archived lines
     * @param dropped lines dropped by a filter
     * @param filterErrors lines replaced by the placeholder
     * @param backlogBytes original bytes not yet archived
     * @param pausedBy errors of filters that cannot be loaded, the writer is paused while there are any
     * @param bytes bytes in {@code archive/}
     * @param files files in {@code archive/}
     */
    public record ArchiveView(boolean enabled, long lines, long dropped, long filterErrors, long backlogBytes,
        List<String> pausedBy, long bytes, int files) {
    }

    /**
     * An agent, identified by its ingest token.
     *
     * @param token name of the token
     * @param host the host of the token
     * @param apps applications it may send
     * @param lastSeen last request since the start of log-guy, {@code null} if none
     * @param lastReceived last byte received from this host, {@code null} if none is in {@code hot/}
     * @param openFiles files of this host that are not yet closed
     */
    public record AgentView(String token, String host, List<String> apps, Instant lastSeen, Instant lastReceived,
        int openFiles) {
    }

    /**
     * An application.
     *
     * @param name the application
     * @param configured whether it is in the configuration, false if only old events are in the index
     * @param parser name of the parser script
     * @param indexDays days kept in the index
     * @param lastEvent time of the newest event in the index
     * @param lastHour events of the last hour in the index
     * @param indexed events in the index
     * @param parseErrorsIndexed events with parse errors in the index
     * @param parseErrors events with parse errors since the start
     * @param backlogFiles files not completely indexed
     * @param backlogBytes bytes not yet indexed
     */
    public record AppView(String name, boolean configured, String parser, int indexDays, Instant lastEvent,
        long lastHour, long indexed, long parseErrorsIndexed, long parseErrors, long backlogFiles,
        long backlogBytes) {
    }

    /**
     * The Solr collection.
     *
     * @param reachable whether Solr answered
     * @param error the error if not
     * @param documents documents in the collection
     * @param replicas the replicas
     */
    public record SolrView(boolean reachable, String error, long documents, List<ReplicaInfo> replicas) {
    }
}
