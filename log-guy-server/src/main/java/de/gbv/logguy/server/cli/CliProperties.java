package de.gbv.logguy.server.cli;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the command line.
 *
 * @param serverUrl base URL of the running server for {@code log-guy reload}
 */
@ConfigurationProperties("log-guy.cli")
public record CliProperties(URI serverUrl) {
}
