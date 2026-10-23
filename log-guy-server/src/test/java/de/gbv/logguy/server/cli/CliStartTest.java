package de.gbv.logguy.server.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import de.gbv.logguy.server.LogGuyApplicationAccess;

/**
 * Starts the command line as {@code log-guy check} does, so beans of the server that the command line lacks show
 * up as a failed start.
 */
class CliStartTest {

    @TempDir
    Path dataDir;

    @Test
    void checkStartsWithoutTheServerBeans() {
        String[] args = { "check", "--log-guy.data-dir=" + dataDir,
            "--log-guy.scripts.dir=" + dataDir.resolve("scripts"), "--log-guy.apps.digibib.parser=mycore-log4j" };
        try (ConfigurableApplicationContext context = LogGuyApplicationAccess.createApplication(args).run(args)) {
            assertEquals(0, SpringApplication.exit(context));
        }
    }
}
