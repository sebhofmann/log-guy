package de.gbv.logguy.server;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.index.Indexer;
import de.gbv.logguy.server.ingest.IngestService;
import de.gbv.logguy.server.script.ParserRegistry;
import de.gbv.logguy.server.script.ScriptRuntime;
import de.gbv.logguy.server.search.ContextService;
import de.gbv.logguy.server.search.SearchService;
import de.gbv.logguy.server.solr.SolrService;
import tools.jackson.databind.json.JsonMapper;

/**
 * Beans of the running server, not created for the command line.
 */
@Configuration
@Profile("!" + LogGuyApplication.CLI_PROFILE)
@EnableScheduling
public class ServerConfig {

    /**
     * Clock for receive times.
     *
     * @return the system clock
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

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
     * @param solr connection to Solr
     * @param clock clock for provisional events
     * @param json mapper for state files
     * @return the running indexer
     * @throws IOException if the state directory cannot be created
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnBooleanProperty("log-guy.indexer.enabled")
    public Indexer indexer(LogGuyProperties properties, HotStore store, ParserRegistry parsers,
        ScriptRuntime runtime, SolrService solr, Clock clock, JsonMapper json) throws IOException {
        List<String> errors = parsers.load();
        if (!errors.isEmpty()) {
            throw new IllegalStateException("parser scripts have errors: " + String.join("; ", errors));
        }
        return new Indexer(properties, store, parsers, runtime, solr::add, clock, json);
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
     * Reads the lines around an event from {@code hot/}.
     *
     * @param properties configuration
     * @param store the hot storage
     * @return the service
     */
    @Bean
    public ContextService contextService(LogGuyProperties properties, HotStore store) {
        return new ContextService(properties, store);
    }

    /**
     * The logic of the stream API.
     *
     * @param store the hot storage
     * @return the service
     */
    @Bean
    public IngestService ingestService(HotStore store) {
        return new IngestService(store);
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
