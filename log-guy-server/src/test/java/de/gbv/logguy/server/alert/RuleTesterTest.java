package de.gbv.logguy.server.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import de.gbv.logguy.server.alert.AlertEngine.Notification.Kind;
import de.gbv.logguy.server.alert.RuleTester.RuleTestResult;
import de.gbv.logguy.server.config.AppGroups;
import de.gbv.logguy.server.config.LogGuyProperties.AlertProperties;
import de.gbv.logguy.server.script.ScriptRuntime;

class RuleTesterTest {

    private static final Instant T0 = Instant.parse("2026-10-24T00:00:00Z");

    private static final AlertProperties SETTINGS = new AlertProperties(true, Duration.ofHours(1),
        Duration.ofMinutes(15), Duration.ofSeconds(2), Duration.ofDays(30), 500, Duration.ofHours(24), 1000, 10,
        Duration.ofMinutes(10), Duration.ofSeconds(15));

    private static ScriptRuntime runtime;

    @BeforeAll
    static void startRuntime() {
        runtime = new ScriptRuntime(Duration.ofMillis(200), Duration.ofSeconds(5));
    }

    @AfterAll
    static void stopRuntime() {
        runtime.close();
    }

    private static Map<String, Object> event(String level, String message) {
        return Map.of("app", "digibib", "level", level, "message", message);
    }

    @Test
    void replaysHitsWithTheCooldownOfTheEventTimes() throws Exception {
        RuleDefinition rule = AlertRules.define(runtime, "pool", """
            export const meta = { apps: ["digibib"], cooldown: "10m" };
            export function check(event) { return event.message.includes("not available"); }
            """);
        RuleTestResult result;
        try (RuleTester tester = new RuleTester(runtime, rule, SETTINGS, T0, T0.plus(Duration.ofHours(24)),
            List.of("digibib"), AppGroups.NONE)) {
            for (int i = 0; i < 20; i++) {
                Instant ts = T0.plus(Duration.ofMinutes(i));
                tester.accept("digibib", "e" + i, ts, event("WARN", i % 2 == 0 ? "Connection is not available\n\tat x"
                    : "fine"));
            }
            result = tester.finish("Test", false);
        }
        assertEquals(20, result.events());
        assertEquals(10, result.hits());
        assertEquals(1, result.alerts());
        assertEquals(Map.of(Kind.OPENED, 1, Kind.SUMMARY, 2), result.notifications());
        assertEquals("Connection is not available", result.hitSamples().getFirst().line());
        assertEquals(10, result.alertSamples().getFirst().count());
    }

    @Test
    void sendsTheSummaryWhenTheCooldownEndsInAQuietStretch() throws Exception {
        RuleDefinition rule = AlertRules.define(runtime, "pool", """
            export const meta = { apps: ["digibib"], cooldown: "2m" };
            export function check(event) { return event.level === "WARN"; }
            """);
        try (RuleTester tester = new RuleTester(runtime, rule, SETTINGS, T0, T0.plus(Duration.ofHours(1)),
            List.of("digibib"), AppGroups.NONE)) {
            for (int i = 0; i < 3; i++) {
                tester.accept("digibib", "a" + i, T0.plusSeconds(i), event("WARN", "x"));
            }
            tester.accept("digibib", "b", T0.plus(Duration.ofMinutes(30)), event("WARN", "x"));
            RuleTestResult result = tester.finish("Test", false);
            List<RuleTester.Sent> sent = result.sentSamples();
            assertEquals(Kind.SUMMARY, sent.get(1).kind());
            assertEquals(2, sent.get(1).count());
            assertEquals(T0.plus(Duration.ofMinutes(2)), sent.get(1).at());
            assertEquals(Map.of(Kind.OPENED, 1, Kind.SUMMARY, 2), result.notifications());
        }
    }

    @Test
    void replaysThresholdAndSilence() throws Exception {
        RuleDefinition threshold = AlertRules.define(runtime, "fehler", """
            export const meta = { apps: ["*"], threshold: { count: 3, within: "5m" } };
            export function check(event) { return event.level === "ERROR"; }
            """);
        try (RuleTester tester = new RuleTester(runtime, threshold, SETTINGS, T0, T0.plus(Duration.ofHours(2)),
            List.of("digibib"), AppGroups.NONE)) {
            for (int i = 0; i < 3; i++) {
                tester.accept("digibib", "a" + i, T0.plusSeconds(i), event("ERROR", "x"));
            }
            for (int i = 0; i < 3; i++) {
                tester.accept("digibib", "b" + i, T0.plus(Duration.ofHours(1)).plusSeconds(i * 200L),
                    event("ERROR", "x"));
            }
            RuleTestResult result = tester.finish("Test", false);
            assertEquals(6, result.hits());
            assertEquals(1, result.alerts());
            assertEquals(1, result.notifications().get(Kind.RESOLVED));
        }
        RuleDefinition silence = AlertRules.define(runtime, "still", """
            export const meta = { apps: ["digibib"], absentFor: "30m" };
            """);
        try (RuleTester tester = new RuleTester(runtime, silence, SETTINGS, T0, T0.plus(Duration.ofHours(3)),
            List.of("digibib"), AppGroups.NONE)) {
            tester.accept("digibib", "a", T0.plus(Duration.ofMinutes(10)), event("INFO", "x"));
            tester.accept("digibib", "b", T0.plus(Duration.ofMinutes(90)), event("INFO", "x"));
            RuleTestResult result = tester.finish("Test", false);
            assertEquals(0, result.hits());
            assertEquals(2, result.alerts());
            assertEquals(1, result.notifications().get(Kind.RESOLVED));
        }
    }
}
