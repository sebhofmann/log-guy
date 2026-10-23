package de.gbv.logguy.server.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.unit.DataSize;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.common.TestLogs;
import de.gbv.logguy.server.audit.Actor;
import de.gbv.logguy.server.audit.Actor.Via;
import de.gbv.logguy.server.audit.AuditLog;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.config.LogGuyProperties.GrantProperties;
import de.gbv.logguy.server.config.LogGuyProperties.HotProperties;
import de.gbv.logguy.server.config.LogGuyProperties.IndexerProperties;
import de.gbv.logguy.server.config.LogGuyProperties.Right;
import de.gbv.logguy.server.config.LogGuyProperties.ScriptProperties;
import de.gbv.logguy.server.hot.HotFile;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.pseudonym.PseudonymService;
import de.gbv.logguy.server.pseudonym.PseudonymService.PseudonymTestResult;
import de.gbv.logguy.server.script.ScriptStore.ScriptId;
import de.gbv.logguy.server.script.ScriptTester.ParserTestResult;
import de.gbv.logguy.server.script.ScriptTester.TestSource;
import de.gbv.logguy.server.security.LogGuyUser;
import tools.jackson.databind.json.JsonMapper;

class ScriptTesterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final byte[] SAMPLE = TestLogs.mycoreSample();

    private static final String LEO_PARSER = """
        export const fields = { level: { type: "string", facet: true } };
        export function isStart(line) { return /^\\d{4}-/.test(line); }
        export function parse(text) {
            const match = /^(\\S+) (\\w+) (.*)$/s.exec(text);
            if (!match) {
                throw new Error("unknown format");
            }
            return { ts: match[1], level: match[2], message: match[3], extra: 1 };
        }
        """;

    private static final ScriptId LEO = new ScriptId(ScriptKind.PARSER, "leo");

    private static final ScriptId MYCORE = new ScriptId(ScriptKind.PARSER, "mycore-log4j");

    private static final LogGuyUser LEO_ADMIN =
        LogGuyUser.of("lena", List.of(new GrantProperties(List.of("leo"), Set.of(Right.READ, Right.SCRIPTS))));

    private static final LogGuyUser DIGI_ADMIN =
        LogGuyUser.of("dora", List.of(new GrantProperties(List.of("digibib"), Set.of(Right.READ, Right.SCRIPTS))));

    private static final LogGuyUser ADMIN =
        LogGuyUser.of("anna", List.of(new GrantProperties(List.of("*"), Set.of(Right.ADMIN, Right.READ))));

    private static ScriptRuntime runtime;

    @TempDir
    Path dir;

    private HotStore hot;

    private ScriptStore store;

    private ScriptTester tester;

    private PseudonymService pseudonyms;

    @BeforeAll
    static void startRuntime() {
        runtime = new ScriptRuntime(Duration.ofMillis(500));
    }

    @AfterAll
    static void stopRuntime() {
        runtime.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        Path scripts = dir.resolve("scripts");
        Files.createDirectories(scripts.resolve("parser"));
        Files.writeString(scripts.resolve("parser/leo.js"), LEO_PARSER);
        LogGuyProperties properties = new LogGuyProperties(dir.resolve("data"), ZoneId.of("Europe/Berlin"),
            Map.of("digibib", new AppProperties(null, "mycore-log4j"), "leo", new AppProperties(null, "leo")),
            List.of(), List.of(), null,
            new HotProperties(DataSize.ofKilobytes(64), Duration.ofSeconds(60), Duration.ofSeconds(5), 3),
            new ScriptProperties(scripts, Duration.ofMillis(500), DataSize.ofKilobytes(16), 1000, 20), null,
            new IndexerProperties(true, 2, Duration.ofMillis(100), 500, DataSize.ofKilobytes(64),
                DataSize.ofMegabytes(64), Duration.ofSeconds(5), DataSize.ofMegabytes(1), 7, Duration.ofSeconds(10)),
            null, null, null, null, null, null);
        hot = new HotStore(properties, JSON, Clock.systemUTC());
        HotFile file = hot.getOrCreate(Fingerprint.of(SAMPLE), "app1", "digibib");
        file.append(0, SAMPLE, SAMPLE.length, null, Instant.now());
        store = new ScriptStore(scripts, Clock.systemUTC(), JSON);
        store.scan();
        ParserRegistry parsers = new ParserRegistry(properties, runtime, store);
        assertEquals(List.of(), parsers.load());
        AuditLog audit = new AuditLog(dir.resolve("audit"), ZoneOffset.UTC, Clock.systemUTC(), JSON);
        ScriptService service = new ScriptService(properties, store, runtime, parsers, List.of(), audit);
        pseudonyms = new PseudonymService(runtime, store, JSON);
        tester =
            new ScriptTester(properties, hot, runtime, service, null, parsers, pseudonyms, null, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() throws Exception {
        pseudonyms.close();
        hot.close();
    }

    @Test
    void pastedLinesShowEventsErrorsAndUndeclaredFields() throws Exception {
        String text = """
            garbage
            2026-10-01T10:00:00Z INFO started
            2026-10-01T10:00:01Z ERROR failed
            \tat some.Class
            2026-10-01T10:00:02Z WARN slow
            """;
        ParserTestResult result = tester.testParser(LEO_ADMIN, LEO, null, false,
            new TestSource(null, null, null, null, null, text));
        assertEquals(5, result.lines());
        assertEquals(4, result.events());
        assertEquals(1, result.errors());
        assertEquals(List.of("extra"), result.undeclared());
        assertEquals(List.of("level"), result.declared());
        assertEquals("failed\n\tat some.Class", result.sample().get(2).message());
        assertEquals(3, result.sample().get(2).line());
        assertEquals(1, result.failed().getFirst().line());
        assertTrue(result.errorCounts().keySet().iterator().next().contains("unknown format"));
    }

    @Test
    void pseudonymTestParsesLinesWithTheParserOfTheApp() throws Exception {
        ScriptId standard = new ScriptId(ScriptKind.PSEUDONYM, "standard");
        PseudonymTestResult result = tester.testPseudonym(ADMIN, standard, null, false,
            new TestSource("digibib", 300, null, null, null, null));
        assertEquals(300, result.events(), result.toString());
        assertEquals(0, result.heldBack());
        assertTrue(result.pseudonyms().getOrDefault("IP", 0) > 0, result.pseudonyms().toString());
        assertEquals(List.of(), result.suspicious());
        assertTrue(result.sample().getFirst().containsKey("level"), result.sample().getFirst().toString());
        assertThrows(AccessDeniedException.class, () -> tester.testPseudonym(DIGI_ADMIN, standard, null, false,
            new TestSource("digibib", 10, null, null, null, null)));
    }

    @Test
    void storesSummaryWithDraft() throws Exception {
        store.saveDraft(LEO, LEO_PARSER + "\n// draft\n", ScriptStore.hash(LEO_PARSER),
            new Actor(LEO_ADMIN.name(), null, Via.GUI));
        tester.testParser(LEO_ADMIN, LEO, null, false,
            new TestSource(null, null, null, null, null, "bad\n2026-10-01T10:00:00Z INFO a\n"));
        ScriptStore.TestSummary summary = store.draft(LEO).orElseThrow().meta().test();
        assertEquals(2, summary.events());
        assertEquals(1, summary.errors());
        assertEquals("eingefügte Zeilen", summary.source());
    }

    @Test
    void appSourceReadsLastLinesFromHot() throws Exception {
        ParserTestResult result = tester.testParser(DIGI_ADMIN, MYCORE, null, false,
            new TestSource("digibib", 300, null, null, null, null));
        assertEquals(300, result.lines());
        assertTrue(result.events() > 0);
        assertEquals(0, result.errors(), result.errorCounts().toString());
        String[] all = new String(SAMPLE, StandardCharsets.UTF_8).split("\n");
        assertTrue(result.source().contains("letzte 300"));
        assertEquals(20, result.sample().size());
        assertTrue(result.truncated());
        assertTrue(all.length > 300);
    }

    @Test
    void fileSourceNeedsReadRight() throws Exception {
        ParserTestResult result = tester.testParser(DIGI_ADMIN, MYCORE, null, false,
            new TestSource(null, 50, null, null, Fingerprint.of(SAMPLE), null));
        assertEquals(50, result.lines());
        assertThrows(AccessDeniedException.class, () -> tester.testParser(LEO_ADMIN, LEO, null, false,
            new TestSource(null, 50, null, null, Fingerprint.of(SAMPLE), null)));
        assertThrows(AccessDeniedException.class, () -> tester.testParser(LEO_ADMIN, LEO, null, false,
            new TestSource("digibib", 50, null, null, null, null)));
    }

    @Test
    void rejectsBrokenCodeAndMissingSource() throws Exception {
        ScriptRequestException broken = assertThrows(ScriptRequestException.class, () -> tester.testParser(
            LEO_ADMIN, LEO, "export function parse(", false, new TestSource(null, null, null, null, null, "x")));
        assertEquals(ScriptRequestException.Reason.INVALID, broken.reason());
        assertThrows(ScriptRequestException.class, () -> tester.testParser(LEO_ADMIN, LEO, null, false,
            new TestSource(null, null, null, null, null, null)));
        assertFalse(store.draft(LEO).isPresent());
    }
}
