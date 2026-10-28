package de.gbv.logguy.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    private static final Instant T0 = Instant.parse("2026-10-28T10:00:00Z");

    private static final LoginProperties SETTINGS = new LoginProperties(3, Duration.ofMinutes(15),
        Duration.ofSeconds(30), Duration.ofMinutes(2), 100);

    private final MovingClock clock = new MovingClock(T0);

    private final LoginThrottle throttle = new LoginThrottle(SETTINGS, clock);

    @Test
    void waitStartsAfterMaxFailuresAndDoubles() {
        assertEquals(Optional.empty(), throttle.failure("10.0.0.1"));
        assertEquals(Optional.empty(), throttle.failure("10.0.0.1"));
        assertEquals(Optional.empty(), throttle.blocked("10.0.0.1"));
        assertEquals(Optional.of(Duration.ofSeconds(30)), throttle.failure("10.0.0.1"));
        assertEquals(Optional.of(Duration.ofSeconds(30)), throttle.blocked("10.0.0.1"));
        // another address is not affected
        assertEquals(Optional.empty(), throttle.blocked("10.0.0.2"));

        clock.now = T0.plusSeconds(30);
        assertEquals(Optional.empty(), throttle.blocked("10.0.0.1"));
        assertEquals(Optional.of(Duration.ofMinutes(1)), throttle.failure("10.0.0.1"));
        clock.now = clock.now.plusSeconds(60);
        assertEquals(Optional.of(Duration.ofMinutes(2)), throttle.failure("10.0.0.1"));
        clock.now = clock.now.plusSeconds(120);
        assertEquals(Optional.of(Duration.ofMinutes(2)), throttle.failure("10.0.0.1"), "never above max-delay");
    }

    @Test
    void successResetsTheAddress() {
        throttle.failure("10.0.0.1");
        throttle.failure("10.0.0.1");
        throttle.success("10.0.0.1");
        assertEquals(Optional.empty(), throttle.failure("10.0.0.1"));
        assertEquals(Optional.empty(), throttle.failure("10.0.0.1"));
        assertTrue(throttle.failure("10.0.0.1").isPresent());
    }

    @Test
    void failuresAreForgottenAfterTheWindow() {
        throttle.failure("10.0.0.1");
        throttle.failure("10.0.0.1");
        clock.now = T0.plus(Duration.ofMinutes(15));
        assertEquals(Optional.empty(), throttle.failure("10.0.0.1"));
        assertEquals(Optional.empty(), throttle.failure("10.0.0.1"));
        assertTrue(throttle.failure("10.0.0.1").isPresent());
    }

    private static final class MovingClock extends Clock {

        private Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
