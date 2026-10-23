package de.gbv.logguy.server;

import org.springframework.boot.SpringApplication;

/**
 * Gives tests in other packages access to {@link LogGuyApplication#createApplication(String[])}.
 */
public final class LogGuyApplicationAccess {

    private LogGuyApplicationAccess() {
    }

    /**
     * Creates the application as {@code main} does.
     *
     * @param args the program arguments
     * @return the application
     */
    public static SpringApplication createApplication(String[] args) {
        return LogGuyApplication.createApplication(args);
    }
}
