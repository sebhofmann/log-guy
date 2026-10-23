package de.gbv.logguy.server.cli;

import org.springframework.boot.ExitCodeGenerator;

/**
 * Exit code of the command that ran, read by {@code SpringApplication.exit}.
 */
public class CliExitCode implements ExitCodeGenerator {

    private volatile int code;

    /**
     * Marks the command as failed.
     */
    public void fail() {
        code = 1;
    }

    @Override
    public int getExitCode() {
        return code;
    }
}
