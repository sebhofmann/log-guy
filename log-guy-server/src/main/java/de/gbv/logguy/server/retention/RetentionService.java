package de.gbv.logguy.server.retention;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest.ACTION;
import org.apache.solr.client.solrj.request.UpdateRequest;

import de.gbv.logguy.server.config.AppRegistry;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.serverlog.ServerLog;
import de.gbv.logguy.server.solr.SolrService;

/**
 * The nightly cleanup: deletes events older than {@code index-days} from Solr, per application where configured,
 * then lets Solr remove the deleted documents physically with {@code expungeDeletes}. Also deletes originals from
 * {@code hot/} whose days are over and old files of the own log.
 */
public class RetentionService {

    private static final Logger LOGGER = LogManager.getLogger();

    private final LogGuyProperties properties;

    private final AppRegistry appRegistry;

    private final SolrService solr;

    private final ServerLog serverLog;

    private final Clock clock;

    private final HotCleaner hotCleaner;

    /**
     * Creates the service.
     *
     * @param properties configuration
     * @param appRegistry the configured applications as they are now
     * @param solr connection to Solr
     * @param serverLog the own log, {@code null} if there is none
     * @param clock clock for the limits
     * @param hotCleaner deletes old originals from {@code hot/}, {@code null} if the archive is disabled
     */
    public RetentionService(LogGuyProperties properties, AppRegistry appRegistry, SolrService solr, ServerLog serverLog,
        Clock clock,
        HotCleaner hotCleaner) {
        this.properties = properties;
        this.appRegistry = appRegistry;
        this.solr = solr;
        this.serverLog = serverLog;
        this.clock = clock;
        this.hotCleaner = hotCleaner;
    }

    /**
     * The delete queries for the current time. Days are counted in the zone of log-guy, an event is kept if it is
     * from today or one of the {@code index-days} days before, so at least {@code index-days} full days are kept.
     * Events loaded from the archive are kept until their {@code expires} and then deleted.
     *
     * @return the queries, the global one before the one for expired events
     */
    public List<String> queries() {
        Instant now = clock.instant();
        Map<Integer, List<String>> appsByDays = new TreeMap<>();
        for (Map.Entry<String, AppProperties> app : appRegistry.apps().entrySet()) {
            Integer days = app.getValue().indexDays();
            if (days != null && days != properties.indexer().indexDays()) {
                appsByDays.computeIfAbsent(days, d -> new ArrayList<>()).add(app.getKey());
            }
        }
        List<String> queries = new ArrayList<>();
        List<String> special = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> group : appsByDays.entrySet()) {
            List<String> apps = group.getValue().stream().sorted().toList();
            special.addAll(apps);
            queries.add(keepLoaded(appClause(apps) + " AND ts:[* TO " + limit(now, group.getKey()) + "}", now));
        }
        String global = "ts:[* TO " + limit(now, properties.indexer().indexDays()) + "}";
        queries.add(keepLoaded(special.isEmpty() ? global : "*:* -" + appClause(special) + " AND " + global, now));
        queries.add(expiredQuery(now));
        return queries;
    }

    /**
     * The query for events loaded from the archive whose time in the index is over.
     *
     * @param now the current time
     * @return the query
     */
    public static String expiredQuery(Instant now) {
        return "expires:[* TO " + now + "]";
    }

    private static String keepLoaded(String query, Instant now) {
        return "+(" + query + ") -expires:[" + now + " TO *]";
    }

    /**
     * The start of the index window of an application: events from there on stay in the index.
     *
     * @param app the application
     * @return the start of the oldest day kept
     */
    public Instant windowStart(String app) {
        AppProperties settings = appRegistry.get(app);
        Integer days = settings == null ? null : settings.indexDays();
        return Instant.parse(limit(clock.instant(), days != null ? days : properties.indexer().indexDays()));
    }

    private String limit(Instant now, int days) {
        ZoneId zone = properties.zone();
        LocalDate first = LocalDate.ofInstant(now, zone).minusDays(days);
        return first.atStartOfDay(zone).toInstant().toString();
    }

    private static String appClause(List<String> apps) {
        return apps.stream().map(a -> "\"" + a + "\"").collect(Collectors.joining(" OR ", "app:(", ")"));
    }

    /**
     * Runs the cleanup. Errors are logged, the next run tries again.
     */
    public void run() {
        try {
            long before = count();
            UpdateRequest delete = new UpdateRequest();
            for (String query : queries()) {
                delete.deleteByQuery(query);
            }
            delete.setAction(ACTION.COMMIT, true, true);
            delete.process(solr.client());
            long after = count();
            LOGGER.info("retention deleted {} events, {} left", before - after, after);
            if (properties.retention().expungeDeletes()) {
                UpdateRequest expunge = new UpdateRequest();
                expunge.setAction(ACTION.COMMIT, true, true);
                expunge.setParam("expungeDeletes", "true");
                expunge.process(solr.client());
                LOGGER.info("retention expunged deleted documents");
            }
        } catch (IOException | SolrServerException | RuntimeException e) {
            LOGGER.error("retention failed", e);
        }
        if (hotCleaner != null) {
            try {
                hotCleaner.run();
            } catch (RuntimeException e) {
                LOGGER.error("cleanup of hot/ failed", e);
            }
        }
        if (serverLog != null) {
            try {
                int deleted = serverLog.deleteOldFiles();
                if (deleted > 0) {
                    LOGGER.info("deleted {} old files of the own log", deleted);
                }
            } catch (IOException e) {
                LOGGER.error("cannot delete old files of the own log", e);
            }
        }
    }

    private long count() throws IOException, SolrServerException {
        return solr.client().query(new SolrQuery("*:*").setRows(0)).getResults()
            .getNumFound();
    }
}
