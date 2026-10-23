package de.gbv.logguy.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.gbv.logguy.server.script.ParsedEvent;
import de.gbv.logguy.server.script.ParserScript;
import de.gbv.logguy.server.script.ScriptRuntime;
import de.gbv.logguy.server.script.ScriptStore;
import tools.jackson.databind.json.JsonMapper;

class LifecycleFiltersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String PARSER = """
        export const fields = { level: { type: "string" }, status: { type: "int" }, user: { type: "string" } };
        export function parse(text) { return { message: text }; }
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

    private LifecycleFilters filters(String name, String code) throws Exception {
        Path lifecycle = Files.createDirectories(dir.resolve("lifecycle"));
        Files.writeString(lifecycle.resolve(name + ".js"), code);
        ScriptStore store = new ScriptStore(dir, Clock.systemUTC(), JSON);
        store.setActive(store.scan());
        return new LifecycleFilters(runtime, store, JSON);
    }

    private static ParsedEvent event() {
        return new ParsedEvent(Instant.parse("2026-10-24T10:00:00Z"), "login user=alice password=geheim",
            Map.of("level", "INFO", "status", 200, "user", "alice"), null);
    }

    @Test
    void changesMessageAndFields() throws Exception {
        LifecycleFilters filters = filters("passwort-weg", """
            export const meta = { apps: ["digibib"] };
            export function fields(event) {
                event.message = event.message.replace(/password=\\S+/, "password=***");
                event.status = String(event.status + 1);
                delete event.user;
                return event;
            }
            """);
        ParserScript script = ParserScript.load(runtime, "p", PARSER);
        try (LifecycleFilters.Runner runner = filters.runner()) {
            ParsedEvent changed = runner.fields("digibib", "host1", event(), script, "[weg {filter}]");
            assertEquals("login user=alice password=***", changed.message());
            assertEquals(Map.of("level", "INFO", "status", 201), changed.fields());
            assertEquals(event().ts(), changed.ts());
            ParsedEvent other = event();
            assertSame(other, runner.fields("mir", "host1", other, script, "[weg {filter}]"));
        }
    }

    @Test
    void withholdsTheEventIfTheFilterFails() throws Exception {
        LifecycleFilters filters = filters("kaputt", """
            export const meta = { apps: ["*"] };
            export function fields(event) { throw new Error("kaputt"); }
            """);
        ParserScript script = ParserScript.load(runtime, "p", PARSER);
        try (LifecycleFilters.Runner runner = filters.runner()) {
            ParsedEvent changed = runner.fields("digibib", "host1", event(), script, "[weg {filter}]");
            assertEquals("[weg kaputt]", changed.message());
            assertEquals(Map.of(), changed.fields());
            assertTrue(changed.error().contains("kaputt"), changed.error());
        }
        assertEquals(List.of(), filters.errors());
    }

    @Test
    void testShowsChangedAndDroppedLinesAndEvents() throws Exception {
        LifecycleFilters filters = filters("leer", "export const meta = { apps: [\"*\"] };\n"
            + "export function line(text) { return text; }\n");
        String code = """
            export const meta = { apps: ["*"] };
            export function line(text) {
                if (text.includes("DEBUG")) { return null; }
                if (text.includes("boom")) { throw new Error("boom"); }
                return text.replace(/\\d+\\.\\d+\\.\\d+\\.\\d+/, "x.x.x.x");
            }
            export function fields(event) { delete event.user; return event; }
            """;
        LifecycleFilters.LifecycleTestResult result = filters.test("neu", code, "Test",
            List.of("INFO von 10.0.0.1", "DEBUG laut", "INFO gleich", "boom"),
            List.of(Map.of("message", "a", "user", "alice"), Map.of("message", "b")), 10);
        assertEquals(4, result.lines());
        assertEquals(1, result.changed());
        assertEquals(1, result.dropped());
        assertEquals(1, result.errors());
        assertEquals("INFO von x.x.x.x", result.samples().get(0).after());
        assertEquals(2, result.samples().get(1).line());
        assertEquals(4, result.failed().get(0).line());
        assertEquals(1, result.eventsChanged());
        assertEquals(Map.of("message", "a"), result.eventSamples().get(0).after());
    }
}
