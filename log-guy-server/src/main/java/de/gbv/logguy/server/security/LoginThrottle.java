package de.gbv.logguy.server.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts failed logins per client address. After {@code max-failures} failures the address has to wait before the
 * next login is checked at all, so it costs no password hash. The wait starts at {@code delay}, doubles with every
 * further failure up to {@code max-delay} and ends by itself. A successful login resets the address. Only the
 * address counts, not the name, so nobody can lock out another user.
 */
public class LoginThrottle {

    private final LoginProperties settings;

    private final Clock clock;

    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    /**
     * Creates the throttle.
     *
     * @param settings the limits
     * @param clock clock for failures and waits
     */
    public LoginThrottle(LoginProperties settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * How long an address still has to wait.
     *
     * @param address the client address
     * @return the remaining wait, empty if a login is checked now
     */
    public Optional<Duration> blocked(String address) {
        Failures entry = failures.get(address);
        Instant now = clock.instant();
        if (entry == null || entry.blockedUntil() == null || !now.isBefore(entry.blockedUntil())) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, entry.blockedUntil()));
    }

    /**
     * Counts a failed login.
     *
     * @param address the client address
     * @return the wait that starts now, empty if the address may try again at once
     */
    public Optional<Duration> failure(String address) {
        Instant now = clock.instant();
        failures.values().removeIf(entry -> expired(entry, now));
        Failures entry = failures.compute(address, (key, old) -> {
            int count = old == null ? 1 : old.count() + 1;
            Instant until = null;
            if (count >= settings.maxFailures()) {
                until = now.plus(wait(count - settings.maxFailures()));
            }
            return new Failures(count, now, until);
        });
        return entry.blockedUntil() == null ? Optional.empty()
            : Optional.of(Duration.between(now, entry.blockedUntil()));
    }

    /**
     * Forgets the failures of an address after a successful login.
     *
     * @param address the client address
     */
    public void success(String address) {
        failures.remove(address);
    }

    private Duration wait(int extra) {
        Duration wait = settings.delay();
        for (int i = 0; i < extra && wait.compareTo(settings.maxDelay()) < 0; i++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(settings.maxDelay()) < 0 ? wait : settings.maxDelay();
    }

    // an entry stays while its wait runs and for the window after its last failure
    private boolean expired(Failures entry, Instant now) {
        Instant end = entry.last().plus(settings.window());
        if (entry.blockedUntil() != null && entry.blockedUntil().isAfter(end)) {
            end = entry.blockedUntil();
        }
        return !now.isBefore(end);
    }

    private record Failures(int count, Instant last, Instant blockedUntil) {
    }
}
