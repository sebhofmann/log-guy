package de.gbv.logguy.server.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits of the login form. Failed logins are counted per client address, a login from another address is not
 * affected.
 *
 * @param maxFailures failed logins of one address before it has to wait
 * @param window failures are forgotten after this time without a new failure
 * @param delay the first wait, it doubles with every further failure
 * @param maxDelay the wait grows up to this time
 * @param maxNameLength longer names of failed logins are cut in the audit log
 */
@ConfigurationProperties("log-guy.login")
public record LoginProperties(int maxFailures, Duration window, Duration delay, Duration maxDelay,
    int maxNameLength) {
}
