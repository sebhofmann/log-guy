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

import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.serverlog.ServerLog;
import de.gbv.logguy.server.solr.SolrService;

/**
 * The nightly cleanup: deletes events older than {@code index-days} from Solr, per application where configured,
 * then lets Solr remove the deleted documents physically with {@code expungeDeletes}. Also deletes old files of the
 * own log.
 */
public class RetentionService {

    private static final Logger LOGGER = LogManager.getLogger();

    private final LogGuyProperties properties;

    private final SolrService solr;

    private final ServerLog serverLog;

    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param properties configuration
     * @param solr connection to Solr
     * @param serverLog the own log, {@code null} if there is none
     * @param clock clock for the limits
     */
    public RetentionService(LogGuyProperties properties, SolrService solr, ServerLog serverLog, Clock clock) {
        this.properties = properties;
        this.solr = solr;
        this.serverLog = serverLog;
        this.clock = clock;
    }

    /**
     * The delete queries for the current time. Days are counted in the zone of log-guy, an event is kept if it is
     * from today or one of the {@code index-days} days before, so at least {@code index-days} full days are kept.
     *
     * @return the queries, the global one last
     */
    public List<String> queries() {
        Instant now = clock.instant();
        Map<Integer, List<String>> appsByDays = new TreeMap<>();
        for (Map.Entry<String, AppProperties> app : properties.apps().entrySet()) {
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
            queries.add(appClause(apps) + " AND ts:[* TO " + limit(now, group.getKey()) + "}");
        }
        String global = "ts:[* TO " + limit(now, properties.indexer().indexDays()) + "}";
        queries.add(special.isEmpty() ? global : "*:* -" + appClause(special) + " AND " + global);
        return queries;
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
