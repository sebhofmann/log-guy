package de.gbv.logguy.server.cli;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;

import de.gbv.logguy.server.LogGuyApplication;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.script.ParserRegistry;
import de.gbv.logguy.server.script.ScriptRuntime;
import de.gbv.logguy.server.solr.SolrService;

/**
 * Beans of the command line.
 */
@Configuration
@Profile(LogGuyApplication.CLI_PROFILE)
public class CliConfig {

    /**
     * Exit code of the process.
     *
     * @return the holder
     */
    @Bean
    public CliExitCode cliExitCode() {
        return new CliExitCode();
    }

    /**
     * The commands.
     *
     * @param properties configuration
     * @param parsers the parser scripts
     * @param solr connection to Solr
     * @param runtime the script runtime
     * @param exitCode exit code of the process
     * @return the commands
     */
    @Bean
    public Commands commands(LogGuyProperties properties, ParserRegistry parsers, @Lazy SolrService solr,
        @Lazy ScriptRuntime runtime, CliExitCode exitCode) {
        return new Commands(properties, parsers, solr, runtime, exitCode);
    }
}
