package de.gbv.logguy.server;

import java.io.IOException;
import java.time.Clock;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.ingest.IngestService;
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
