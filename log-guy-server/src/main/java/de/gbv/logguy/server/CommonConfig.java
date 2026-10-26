package de.gbv.logguy.server;

import java.io.IOException;
import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import de.gbv.logguy.server.config.AppRegistry;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.script.ParserRegistry;
import de.gbv.logguy.server.script.ScriptRuntime;
import de.gbv.logguy.server.script.ScriptStore;
import de.gbv.logguy.server.solr.SolrService;
import tools.jackson.databind.json.JsonMapper;

/**
 * Beans of server and command line.
 */
@Configuration
public class CommonConfig {

    /**
     * GraalJS for all scripts.
     *
     * @param properties configuration
     * @return the runtime
     */
    @Bean(destroyMethod = "close")
    @Lazy
    public ScriptRuntime scriptRuntime(LogGuyProperties properties) {
        return new ScriptRuntime(properties.scripts().eventTimeout());
    }

    /**
     * Clock for receive times, history and audit.
     *
     * @return the system clock
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The script directory with history and drafts, read once at the start.
     *
     * @param properties configuration
     * @param clock clock for history and drafts
     * @param json mapper for the meta files
     * @return the store
     * @throws IOException if the script directory cannot be read
     */
    @Bean
    @Lazy
    public ScriptStore scriptStore(LogGuyProperties properties, Clock clock, JsonMapper json) throws IOException {
        ScriptStore store = new ScriptStore(properties.scripts().dir(), clock, json);
        store.scan();
        return store;
    }

    /**
     * The configured applications, replaced by a reload.
     *
     * @param properties configuration of the start
     * @return the registry
     */
    @Bean
    public AppRegistry appRegistry(LogGuyProperties properties) {
        return new AppRegistry(properties);
    }

    /**
     * The parser scripts, not yet loaded.
     *
     * @param apps the configured applications
     * @param runtime the script runtime
     * @param store the script directory
     * @return the registry
     */
    @Bean
    @Lazy
    public ParserRegistry parserRegistry(AppRegistry apps, ScriptRuntime runtime, ScriptStore store) {
        return new ParserRegistry(apps, runtime, store);
    }

    /**
     * Connection to the SolrCloud.
     *
     * @param properties configuration
     * @return the service
     * @throws IOException if the password file cannot be read
     */
    @Bean(destroyMethod = "close")
    @Lazy
    public SolrService solrService(LogGuyProperties properties) throws IOException {
        return new SolrService(properties.solr());
    }
}
