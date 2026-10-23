package de.gbv.logguy.server;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.script.ParserRegistry;
import de.gbv.logguy.server.script.ScriptRuntime;
import de.gbv.logguy.server.solr.SolrService;

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
     * The parser scripts, not yet loaded.
     *
     * @param properties configuration
     * @param runtime the script runtime
     * @return the registry
     */
    @Bean
    @Lazy
    public ParserRegistry parserRegistry(LogGuyProperties properties, ScriptRuntime runtime) {
        return new ParserRegistry(properties, runtime);
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
