package de.gbv.logguy.server.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.gbv.logguy.server.alert.RuleDefinition.Type;
import de.gbv.logguy.server.script.ScriptException;
import de.gbv.logguy.server.script.ScriptRuntime;
import de.gbv.logguy.server.script.ScriptStore;
import tools.jackson.databind.json.JsonMapper;

class AlertRulesTest {

    private static final String POOL = """
        export const meta = { apps: ["digibib", "leo*"], cooldown: "15m", notify: ["mail:it"] };
        export function check(event) {
            return event.message.includes("Connection is not available") && event.level === "WARN";
        }
        export function message(event, count) { return `${event.app}: Pool voll, ${count} Treffer`; }
        """;

    private static ScriptRuntime runtime;

    @TempDir
    Path dir;

    @BeforeAll
    static void startRuntime() {
        runtime = new ScriptRuntime(Duration.ofMillis(200));
    }

    @AfterAll
    static void stopRuntime() {
        runtime.close();
    }

    @Test
    void readsTheThreeKindsOfRules() throws Exception {
        RuleDefinition pool = AlertRules.define(runtime, "pool", POOL);
        assertEquals(Type.HIT, pool.type());
        assertEquals(Duration.ofMinutes(15), pool.cooldown());
        assertTrue(pool.appliesTo("leopoldina"));
        assertTrue(pool.hasMessage());
        RuleDefinition threshold = AlertRules.define(runtime, "fehler", """
            export const meta = { apps: ["*"], threshold: { count: 50, within: "5m", per: "app" } };
            export function check(event) { return event.level === "ERROR"; }
            """);
        assertEquals(Type.THRESHOLD, threshold.type());
        assertEquals(50, threshold.count());
        assertEquals(null, threshold.per());
        RuleDefinition silence = AlertRules.define(runtime, "still", """
            export const meta = { apps: ["digibib"], absentFor: "30m" };
            """);
        assertEquals(Type.ABSENCE, silence.type());
        assertEquals(Duration.ofMinutes(30), silence.absentFor());
        assertThrows(ScriptException.class, () -> AlertRules.define(runtime, "kaputt", """
            export const meta = { apps: ["*"], threshold: { count: 5 } };
            export function check(event) { return true; }
            """));
        assertThrows(ScriptException.class, () -> AlertRules.define(runtime, "ohne", """
            export const meta = { apps: ["*"] };
            """));
    }

    @Test
    void runnerChecksEventsAndBuildsTexts() throws Exception {
        Path rules = Files.createDirectories(dir.resolve("rule"));
        Files.writeString(rules.resolve("pool.js"), POOL);
        Files.writeString(rules.resolve("boom.js"), """
            export const meta = { apps: ["*"] };
            export function check(event) { throw new Error("boom"); }
            """);
        ScriptStore store = new ScriptStore(dir, Clock.systemUTC(), JsonMapper.builder().build());
        store.setActive(store.scan());
        AlertRules alertRules = new AlertRules(runtime, store);
        assertEquals(2, alertRules.rules().size());
        Map<String, Object> event = Map.of("app", "digibib", "level", "WARN", "message",
            "HikariPool-1 - Connection is not available, request timed out", "tags", List.of("a", "b"));
        try (AlertRules.Runner runner = alertRules.runner()) {
            List<RuleDefinition> matched = runner.check("digibib", event);
            assertEquals(List.of("pool"), matched.stream().map(RuleDefinition::name).toList());
            assertEquals("digibib: Pool voll, 3 Treffer", runner.message(matched.getFirst(), event, 3));
            assertEquals(List.of(), runner.check("mir", Map.of("app", "mir", "message", "x")).stream()
                .filter(rule -> rule.name().equals("pool")).toList());
        }
        assertEquals(2L, alertRules.checkErrors().get("boom"));
        assertTrue(alertRules.lastErrors().get("boom").contains("boom"));
    }
}
