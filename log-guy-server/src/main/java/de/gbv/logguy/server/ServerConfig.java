package de.gbv.logguy.server;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import de.gbv.logguy.server.archive.ArchiveStore;
import de.gbv.logguy.server.archive.search.ArchiveSearch;
import de.gbv.logguy.server.archive.ArchiveWriter;
import de.gbv.logguy.server.archive.LifecycleFilters;
import de.gbv.logguy.server.audit.AuditLog;
import de.gbv.logguy.server.config.ConfigReloader;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.index.Indexer;
import de.gbv.logguy.server.ingest.IngestService;
import de.gbv.logguy.server.pseudonym.PseudonymService;
import de.gbv.logguy.server.reindex.ReindexService;
import de.gbv.logguy.server.retention.HotCleaner;
import de.gbv.logguy.server.retention.RetentionService;
import de.gbv.logguy.server.script.ParserRegistry;
import de.gbv.logguy.server.script.ScriptListener;
import de.gbv.logguy.server.script.ScriptRuntime;
import de.gbv.logguy.server.script.ScriptService;
import de.gbv.logguy.server.script.ScriptTester;
import de.gbv.logguy.server.script.ScriptStore;
import de.gbv.logguy.server.search.ContextService;
import de.gbv.logguy.server.search.SearchService;
import de.gbv.logguy.server.security.TokenRegistry;
import de.gbv.logguy.server.security.UserRegistry;
import de.gbv.logguy.server.serverlog.ServerLog;
import de.gbv.logguy.server.solr.SolrService;
import de.gbv.logguy.server.status.StatusService;
import tools.jackson.databind.json.JsonMapper;

/**
 * Beans of the running server, not created for the command line.
 */
@Configuration
@Profile("!" + LogGuyApplication.CLI_PROFILE)
@EnableScheduling
public class ServerConfig {

    /**
     * The hot storage.
     *
     * @param properties configuration
     * @param json mapper for meta files
     * @param clock clock for receive times
     * @return the store with all files loaded
     * @throws IOException if {@code hot/} cannot be read
     */
    @Bean(destroyMethod = "close")
    public HotStore hotStore(LogGuyProperties properties, JsonMapper json, Clock clock) throws IOException {
        return new HotStore(properties, json, clock);
    }

    /**
     * The indexer, started with the server unless {@code log-guy.indexer.enabled} is false. Fails the start if a
     * parser script has errors.
     *
     * @param properties configuration
     * @param store the hot storage
     * @param parsers the parser scripts
     * @param runtime the script runtime
     * @param filters change the fields of events with {@code fields(event)}
     * @param solr connection to Solr
     * @param clock clock for provisional events
     * @param json mapper for state files
     * @return the running indexer
     * @throws IOException if the state directory cannot be created
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnBooleanProperty("log-guy.indexer.enabled")
    public Indexer indexer(LogGuyProperties properties, HotStore store, ParserRegistry parsers,
        ScriptRuntime runtime, LifecycleFilters filters, SolrService solr, Clock clock, JsonMapper json)
        throws IOException {
        List<String> errors = parsers.load();
        if (!errors.isEmpty()) {
            throw new IllegalStateException("parser scripts have errors: " + String.join("; ", errors));
        }
        return new Indexer(properties, store, parsers, runtime, filters, solr, clock, json);
    }

    /**
     * The files in {@code archive/}.
     *
     * @param properties configuration
     * @param json mapper for the meta data
     * @return the loaded archive
     * @throws IOException if the archive cannot be read
     */
    @Bean
    public ArchiveStore archiveStore(LogGuyProperties properties, JsonMapper json) throws IOException {
        return new ArchiveStore(properties, json);
    }

    /**
     * The lifecycle filters, reloaded when scripts change.
     *
     * @param runtime the script runtime
     * @param store the scripts
     * @param json for passing events to {@code fields(event)}
     * @return the filters
     */
    @Bean
    public LifecycleFilters lifecycleFilters(ScriptRuntime runtime, ScriptStore store, JsonMapper json) {
        return new LifecycleFilters(runtime, store, json);
    }

    /**
     * Copies lines from {@code hot/} to {@code archive/} through the lifecycle filters.
     *
     * @param properties configuration
     * @param hot the originals
     * @param archive the archive
     * @param filters the lifecycle filters
     * @param parsers the parsers, for the time range of the frames
     * @param runtime the script runtime
     * @return the running writer
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnBooleanProperty("log-guy.archive.enabled")
    public ArchiveWriter archiveWriter(LogGuyProperties properties, HotStore hot, ArchiveStore archive,
        LifecycleFilters filters, ParserRegistry parsers, ScriptRuntime runtime) {
        parsers.ensureLoaded();
        return new ArchiveWriter(properties, hot, archive, filters, parsers, runtime);
    }

    /**
     * The archive search with its worker threads.
     *
     * @param properties configuration
     * @param archive the archive
     * @param pseudonyms pseudonymizes the hits of pseudonymized jobs
     * @param clock clock for the limits
     * @return the search
     */
    @Bean(destroyMethod = "close")
    public ArchiveSearch archiveSearch(LogGuyProperties properties, ArchiveStore archive, PseudonymService pseudonyms,
        Clock clock) {
        return new ArchiveSearch(properties, archive, pseudonyms, clock);
    }

    /**
     * The audit log in {@code audit/}.
     *
     * @param properties configuration
     * @param clock clock for the time stamps
     * @param json mapper for the lines
     * @return the log
     */
    @Bean
    public AuditLog auditLog(LogGuyProperties properties, Clock clock, JsonMapper json) {
        return new AuditLog(properties.dataDir().resolve("audit"), properties.zone(), clock, json);
    }

    /**
     * Drafts, activation and reload of scripts. Records the active scripts in the history at the start.
     *
     * @param properties configuration
     * @param store the script directory
     * @param runtime the script runtime
     * @param parsers the parser scripts
     * @param listeners take over scripts of other kinds
     * @param audit the audit log
     * @return the service
     * @throws IOException if the history cannot be written
     */
    @Bean
    public ScriptService scriptService(LogGuyProperties properties, ScriptStore store, ScriptRuntime runtime,
        ParserRegistry parsers, List<ScriptListener> listeners, AuditLog audit) throws IOException {
        ScriptService service = new ScriptService(properties, store, runtime, parsers, listeners, audit);
        service.recordStartVersions();
        return service;
    }

    /**
     * Reads configuration and scripts again on {@code POST /admin/reload}.
     *
     * @param environment the environment of the server
     * @param properties the configuration the server started with
     * @param tokens the tokens
     * @param users the users
     * @param scripts the scripts
     * @param audit the audit log
     * @return the reloader
     */
    @Bean
    public ConfigReloader configReloader(ConfigurableEnvironment environment, LogGuyProperties properties,
        TokenRegistry tokens, UserRegistry users, ScriptService scripts, AuditLog audit) {
        return new ConfigReloader(environment, properties, tokens, users, scripts, audit);
    }

    /**
     * The search over Solr.
     *
     * @param properties configuration
     * @param parsers the parser scripts with the declared fields
     * @param solr connection to Solr
     * @param clock clock for the default range
     * @param json mapper for facet requests
     * @return the service
     */
    @Bean
    public SearchService searchService(LogGuyProperties properties, ParserRegistry parsers, SolrService solr,
        Clock clock, JsonMapper json) {
        return new SearchService(properties, parsers, solr, clock, json);
    }

    /**
     * Pseudonymizes everything that leaves the MCP endpoint.
     *
     * @param runtime the script runtime
     * @param store the scripts
     * @param json for passing events to the scripts
     * @return the service
     */
    @Bean(destroyMethod = "close")
    public PseudonymService pseudonymService(ScriptRuntime runtime, ScriptStore store, JsonMapper json) {
        return new PseudonymService(runtime, store, json);
    }

    /**
     * Runs scripts against test sources.
     *
     * @param properties configuration
     * @param store the hot storage
     * @param runtime the script runtime
     * @param scripts the scripts
     * @param search the search
     * @param parsers the parsers, to turn lines into events for other kinds
     * @param pseudonyms runs pseudonymization tests
     * @param lifecycle runs tests of lifecycle filters
     * @param clock the clock
     * @return the tester
     */
    @Bean
    public ScriptTester scriptTester(LogGuyProperties properties, HotStore store, ScriptRuntime runtime,
        ScriptService scripts, SearchService search, ParserRegistry parsers, PseudonymService pseudonyms,
        LifecycleFilters lifecycle, Clock clock) {
        return new ScriptTester(properties, store, runtime, scripts, search, parsers, pseudonyms, lifecycle, clock);
    }

    /**
     * Reads the lines around an event.
     *
     * @param properties configuration
     * @param store the hot storage
     * @param archive the archive, for events loaded from it
     * @return the service
     */
    @Bean
    public ContextService contextService(LogGuyProperties properties, HotStore store, ArchiveStore archive) {
        return new ContextService(properties, store, archive);
    }

    /**
     * The logic of the stream API.
     *
     * @param store the hot storage
     * @param archive the archive, for files already deleted from {@code hot/}
     * @return the service
     */
    @Bean
    public IngestService ingestService(HotStore store, ArchiveStore archive) {
        return new IngestService(store, archive);
    }

    /**
     * The own log: a ring buffer for the server log page and a daily rotated file in {@code logs/}.
     *
     * @param properties configuration
     * @param clock clock for the age of old files
     * @return the attached log
     * @throws IOException if {@code logs/} cannot be created
     */
    @Bean(destroyMethod = "close")
    public ServerLog serverLog(LogGuyProperties properties, Clock clock) throws IOException {
        return new ServerLog(properties, clock);
    }

    /**
     * The nightly cleanup of the index, of {@code hot/} and of the own log.
     *
     * @param properties configuration
     * @param solr connection to Solr
     * @param serverLog the own log
     * @param clock clock for the limits
     * @param hot the originals
     * @param archive the archive
     * @param indexer the indexer, missing if indexing is disabled
     * @param parsers the parsers
     * @return the service
     */
    @Bean
    public RetentionService retentionService(LogGuyProperties properties, SolrService solr, ServerLog serverLog,
        Clock clock, HotStore hot, ArchiveStore archive, ObjectProvider<Indexer> indexer, ParserRegistry parsers) {
        HotCleaner cleaner = properties.archive().enabled()
            ? new HotCleaner(properties, hot, archive, indexer.getIfAvailable(), parsers, clock)
            : null;
        return new RetentionService(properties, solr, serverLog, clock, cleaner);
    }

    /**
     * Runs the cleanup at {@code log-guy.retention.cron} in the zone of log-guy, never if the cron is {@code -}.
     *
     * @param properties configuration
     * @param retention the cleanup
     * @return the scheduling
     */
    @Bean
    public SchedulingConfigurer retentionSchedule(LogGuyProperties properties, RetentionService retention) {
        return (ScheduledTaskRegistrar registrar) -> {
            if (!Scheduled.CRON_DISABLED.equals(properties.retention().cron())) {
                registrar.addTriggerTask(retention::run,
                    new CronTrigger(properties.retention().cron(), properties.zone()));
            }
        };
    }

    /**
     * Indexing again from {@code hot/} and loading older days from {@code archive/}.
     *
     * @param properties configuration
     * @param hot the originals
     * @param archive the archive
     * @param indexer the indexer, missing if indexing is disabled
     * @param parsers the parsers
     * @param runtime the script runtime
     * @param filters change the fields of loaded events
     * @param solr connection to Solr
     * @param retention knows the index windows
     * @param clock clock for the expiry
     * @return the service
     */
    @Bean(destroyMethod = "close")
    public ReindexService reindexService(LogGuyProperties properties, HotStore hot, ArchiveStore archive,
        ObjectProvider<Indexer> indexer, ParserRegistry parsers, ScriptRuntime runtime, LifecycleFilters filters,
        SolrService solr, RetentionService retention, Clock clock) {
        return new ReindexService(properties, hot, archive, indexer.getIfAvailable(), parsers, runtime, filters, solr,
            solr::oldestEvent, retention, clock);
    }

    /**
     * Deletes loaded events whose expiry date has passed at {@code log-guy.reindex.expire-cron}, never if the cron is
     * {@code -}.
     *
     * @param properties configuration
     * @param reindex the reindex service
     * @return the scheduling
     */
    @Bean
    public SchedulingConfigurer expireSchedule(LogGuyProperties properties, ReindexService reindex) {
        return (ScheduledTaskRegistrar registrar) -> {
            if (!Scheduled.CRON_DISABLED.equals(properties.reindex().expireCron())) {
                registrar.addTriggerTask(reindex::expire,
                    new CronTrigger(properties.reindex().expireCron(), properties.zone()));
            }
        };
    }

    /**
     * Collects the numbers of the status page.
     *
     * @param properties configuration
     * @param store the hot storage
     * @param indexer the indexer if it runs
     * @param archive the archive
     * @param writer the archive writer if it runs
     * @param ingest the stream API
     * @param solr connection to Solr
     * @param clock clock for the last hour
     * @param json mapper for facet requests
     * @param build build information if packaged with it
     * @return the service
     */
    @Bean
    public StatusService statusService(LogGuyProperties properties, HotStore store, ObjectProvider<Indexer> indexer,
        ArchiveStore archive, ObjectProvider<ArchiveWriter> writer, IngestService ingest, SolrService solr, Clock clock,
        JsonMapper json, ObjectProvider<BuildProperties> build) {
        BuildProperties info = build.getIfAvailable();
        return new StatusService(properties, store, indexer.getIfAvailable(), archive, writer.getIfAvailable(), ingest,
            solr, clock, json,
            info == null ? null : info.getVersion());
    }

    /**
     * Replaces the runner of Spring Shell, the server does not execute commands.
     *
     * @return a runner that does nothing
     */
    @Bean
    public ApplicationRunner springShellApplicationRunner() {
        return args -> {
        };
    }

    /**
     * Writes frames for pending bytes that became too old.
     *
     * @param store the store
     * @return the flusher
     */
    @Bean
    public HotFlusher hotFlusher(HotStore store) {
        return new HotFlusher(store);
    }

    /**
     * Calls {@link HotStore#flushAged()} regularly.
     *
     * @param store the store
     */
    public record HotFlusher(HotStore store) {

        /**
         * Flushes aged pending bytes.
         */
        @Scheduled(fixedDelayString = "${log-guy.hot.flush-interval}")
        public void flush() {
            store.flushAged();
        }
    }
}
