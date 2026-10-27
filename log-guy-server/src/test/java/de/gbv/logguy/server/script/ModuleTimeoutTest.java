package de.gbv.logguy.server.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import de.gbv.logguy.server.alert.AlertRules;
import de.gbv.logguy.server.archive.LifecycleFilters;
import de.gbv.logguy.server.config.AppGroups;
import de.gbv.logguy.server.pseudonym.PseudonymService;
import de.gbv.logguy.server.script.ScriptStore.ScriptId;
import tools.jackson.databind.json.JsonMapper;

/**
 * Top level code of every kind of script runs under the watchdog with {@code load-timeout}.
 */
class ModuleTimeoutTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Duration LOAD_TIMEOUT = Duration.ofMillis(300);

    /** Long enough for a slow machine, far shorter than an endless loop. */
    private static final Duration GIVE_UP = Duration.ofSeconds(10);

    private static final String LOOP = "while (true) {}\n";

    private static final String PARSER = LOOP + """
        export const fields = { level: { type: "string" } };
        export function parse(text) { return { message: text }; }
        """;

    private static final String RULE = LOOP + """
        export const meta = { apps: ["leo"] };
        export function check(event) { return false; }
        """;

    private static final String LIFECYCLE = LOOP + """
        export const meta = { apps: ["leo"] };
        export function line(text) { return text; }
        """;

    private static final String PSEUDONYM = LOOP + """
        export const meta = { apps: ["leo"], fields: [] };
        export function apply(event) { return event; }
        """;

    private static ScriptRuntime runtime;

    @TempDir
    Path dir;

    @BeforeAll
    static void startRuntime() {
        runtime = new ScriptRuntime(Duration.ofMillis(50), LOAD_TIMEOUT);
    }

    @AfterAll
    static void stopRuntime() {
        runtime.close();
    }

    private static void timesOut(Executable load) {
        ScriptException e = assertTimeoutPreemptively(GIVE_UP, () -> assertThrows(ScriptException.class, load));
        assertTrue(e.getMessage().contains("took longer than " + LOAD_TIMEOUT.toMillis() + " ms"), e.getMessage());
    }

    @Test
    void parser() {
        timesOut(() -> ParserScript.load(runtime, "loop", PARSER));
    }

    @Test
    void parserInstance() throws Exception {
        String code = """
            export const fields = { level: { type: "string" } };
            export function parse(text) { return { message: text }; }
            """;
        ParserScript script = ParserScript.load(runtime, "loop", code);
        ParserScript looping = new ParserScript(script.name(), LOOP + code, script.version(), script.fields(),
            script.hasIsStart());
        timesOut(() -> new Parser(runtime, looping));
    }

    @Test
    void rule() {
        timesOut(() -> AlertRules.define(runtime, "loop", RULE));
        timesOut(() -> ModuleInfo.check(runtime, ScriptKind.RULE, "loop", RULE));
    }

    @Test
    void lifecycle() throws Exception {
        timesOut(() -> ModuleInfo.check(runtime, ScriptKind.LIFECYCLE, "loop", LIFECYCLE));
        LifecycleFilters filters = new LifecycleFilters(runtime, store(), JSON, () -> AppGroups.NONE);
        timesOut(() -> filters.test("loop", LIFECYCLE, "pasted", List.of("a line"), List.of(), 10));
    }

    @Test
    void pseudonym() throws Exception {
        timesOut(() -> ModuleInfo.check(runtime, ScriptKind.PSEUDONYM, "loop", PSEUDONYM));
        PseudonymService pseudonyms = new PseudonymService(runtime, store(), JSON, () -> AppGroups.NONE);
        timesOut(() -> pseudonyms.test(new ScriptId(ScriptKind.PSEUDONYM, "loop"), PSEUDONYM,
            List.of(Map.of("app", "leo", "message", "a line")), 10));
    }

    @Test
    void activeScriptsThatLoopAreReportedAsErrors() throws Exception {
        Files.createDirectories(dir.resolve("rule"));
        Files.writeString(dir.resolve("rule/loop.js"), RULE);
        Files.createDirectories(dir.resolve("lifecycle"));
        Files.writeString(dir.resolve("lifecycle/loop.js"), LIFECYCLE);
        ScriptStore store = store();
        AlertRules rules = assertTimeoutPreemptively(GIVE_UP, () -> new AlertRules(runtime, store,
            () -> AppGroups.NONE));
        assertEquals(1, rules.errors().size(), rules.errors().toString());
        LifecycleFilters filters = assertTimeoutPreemptively(GIVE_UP, () -> new LifecycleFilters(runtime, store,
            JSON, () -> AppGroups.NONE));
        assertEquals(1, filters.errors().size(), filters.errors().toString());
    }

    @Test
    void runtimeStaysUsableAfterTimeout() throws Exception {
        timesOut(() -> ParserScript.load(runtime, "loop", PARSER));
        ParserScript script = ParserScript.load(runtime, "fine", PARSER.substring(LOOP.length()));
        assertEquals("fine", script.name());
    }

    private ScriptStore store() throws Exception {
        ScriptStore store = new ScriptStore(dir, Clock.systemUTC(), JSON);
        store.setActive(store.scan());
        return store;
    }
}
