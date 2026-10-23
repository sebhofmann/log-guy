package de.gbv.logguy.server;

import java.util.Map;
import java.util.Set;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point. Without arguments, or with {@code serve}, log-guy starts as server. With a command such as
 * {@code check} or {@code solr init}, it runs the command with the profile {@code cli} and without web server.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LogGuyApplication {

    /** Profile of the command line. */
    public static final String CLI_PROFILE = "cli";

    private static final Set<String> SERVE = Set.of("serve");

    /**
     * Starts log-guy.
     *
     * @param args nothing or {@code serve} for the server, otherwise a command
     */
    public static void main(String[] args) {
        createApplication(args).run(serverArgs(args));
    }

    /**
     * Creates the application for the given arguments.
     *
     * @param args the program arguments
     * @return the configured application
     */
    static SpringApplication createApplication(String[] args) {
        SpringApplication application = new SpringApplication(LogGuyApplication.class);
        application.setDefaultProperties(Map.of("spring.config.additional-location", "optional:file:/etc/log-guy/"));
        if (isCommand(args)) {
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setAdditionalProfiles(CLI_PROFILE);
        }
        return application;
    }

    private static boolean isCommand(String[] args) {
        return args.length > 0 && !args[0].startsWith("-") && !SERVE.contains(args[0]);
    }

    private static String[] serverArgs(String[] args) {
        if (args.length > 0 && SERVE.contains(args[0])) {
            String[] rest = new String[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            return rest;
        }
        return args;
    }
}
