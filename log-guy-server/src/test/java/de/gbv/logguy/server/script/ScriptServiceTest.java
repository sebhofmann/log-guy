package de.gbv.logguy.server.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.unit.DataSize;

import de.gbv.logguy.server.audit.Actor;
import de.gbv.logguy.server.audit.Actor.Via;
import de.gbv.logguy.server.audit.AuditLog;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.config.LogGuyProperties.GrantProperties;
import de.gbv.logguy.server.config.LogGuyProperties.Right;
import de.gbv.logguy.server.config.LogGuyProperties.ScriptProperties;
import de.gbv.logguy.server.script.ScriptRequestException.Reason;
import de.gbv.logguy.server.script.ScriptService.Caller;
import de.gbv.logguy.server.script.ScriptService.ReloadResult;
import de.gbv.logguy.server.script.ScriptService.ScriptDetail;
import de.gbv.logguy.server.script.ScriptStore.ScriptId;
import de.gbv.logguy.server.script.ScriptStore.TestSummary;
import de.gbv.logguy.server.script.ScriptStore.Version;
import de.gbv.logguy.server.security.LogGuyUser;
import tools.jackson.databind.json.JsonMapper;

class ScriptServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String LEO_PARSER = """
        export const fields = { level: { type: "string", facet: true } };
        export function parse(text) {
            return { level: text.substring(0, 4).trim(), message: text };
        }
        """;

    private static final String RULE = """
        export const meta = { apps: ["leo"] };
        export function check(event) { return event.fields.level === "ERROR"; }
        """;

    private static final String RULE_ALL = """
        export const meta = { apps: ["*"] };
        export function check(event) { return false; }
        """;

    private static final ScriptId LEO = new ScriptId(ScriptKind.PARSER, "leo");

    private static final ScriptId MYCORE = new ScriptId(ScriptKind.PARSER, "mycore-log4j");

    private static final ScriptId ERRORS = new ScriptId(ScriptKind.RULE, "errors");

    private static final LogGuyUser ROOT =
        LogGuyUser.of("root", List.of(new GrantProperties(List.of("*"), Set.of(Right.ADMIN))));

    private static final LogGuyUser LEO_ADMIN =
        LogGuyUser.of("lena", List.of(new GrantProperties(List.of("leo"), Set.of(Right.READ, Right.SCRIPTS))));

    private static final LogGuyUser NO_READ =
        LogGuyUser.of("max", List.of(new GrantProperties(List.of("leo"), Set.of(Right.SCRIPTS))));

    private static ScriptRuntime runtime;

    @TempDir
    Path dir;

    private ScriptStore store;

    private ParserRegistry parsers;

    private ScriptService service;

    private final List<ScriptKind> notified = new ArrayList<>();

    @BeforeAll
    static void startRuntime() {
        runtime = new ScriptRuntime(Duration.ofMillis(200));
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
            Map.of("digibib", new AppProperties(null, "mycore-log4j"), "leo", new AppProperties(null, null)),
            List.of(), List.of(), null, null,
            new ScriptProperties(scripts, Duration.ofMillis(200), DataSize.ofKilobytes(16), 1000, 100), null, null,
            null, null, null, null, null);
        store = new ScriptStore(scripts, Clock.systemUTC(), JSON);
        store.scan();
        parsers = new ParserRegistry(properties, runtime, store);
        assertEquals(List.of(), parsers.load());
        AuditLog audit = new AuditLog(dir.resolve("audit"), ZoneOffset.UTC, Clock.systemUTC(), JSON);
        service = new ScriptService(properties, store, runtime, parsers,
            List.of((kind, s) -> notified.add(kind)), audit);
        service.recordStartVersions();
    }

    private static Caller gui(LogGuyUser user) {
        return new Caller(user, new Actor(user.name(), null, Via.GUI), false);
    }

    private static Caller mcp(LogGuyUser user, boolean activate) {
        return new Caller(user, new Actor(user.name(), "mcp-token", Via.MCP), activate);
    }

    @Test
    void startRecordsFileSystemVersion() throws Exception {
        List<Version> history = store.history(LEO);
        assertEquals(1, history.size());
        assertEquals(Via.FILESYSTEM, history.getFirst().via());
        service.recordStartVersions();
        assertEquals(1, store.history(LEO).size());
    }

    @Test
    void draftTestActivateAndRollback() throws Exception {
        String changed = LEO_PARSER.replace("substring(0, 4)", "substring(0, 5)");
        ScriptDetail draft = service.saveDraft(gui(LEO_ADMIN), LEO, changed, null);
        assertEquals(ScriptStore.hash(LEO_PARSER), draft.draft().info().base());
        assertFalse(draft.draft().info().conflict());
        assertEquals(LEO_PARSER, store.readDisk(LEO).orElseThrow());

        ScriptDetail active = service.activate(gui(LEO_ADMIN), LEO, false, false);
        assertNull(active.draft());
        assertEquals(changed, Files.readString(dir.resolve("scripts/parser/leo.js")));
        assertEquals(changed, parsers.forApp("leo").orElseThrow().code());
        assertEquals(2, active.history().size());
        assertEquals("lena", active.history().getFirst().user());

        Instant first = active.history().getLast().at();
        assertEquals(LEO_PARSER, service.historyCode(LEO_ADMIN, LEO, first));
        assertThrows(ScriptRequestException.class, () -> service.historyCode(LEO_ADMIN, LEO,
            first.minusSeconds(1)));
        LogGuyUser stranger =
            LogGuyUser.of("eve", List.of(new GrantProperties(List.of("other"), Set.of(Right.SCRIPTS))));
        assertThrows(AccessDeniedException.class, () -> service.historyCode(stranger, LEO, first));
        ScriptDetail back = service.rollback(gui(LEO_ADMIN), LEO, first);
        assertEquals(LEO_PARSER, back.code());
        assertEquals(LEO_PARSER, parsers.forApp("leo").orElseThrow().code());
        assertEquals(3, back.history().size());

        List<String> audit = Files.readAllLines(Files.list(dir.resolve("audit")).findFirst().orElseThrow());
        assertEquals(3, audit.size());
        assertTrue(audit.get(1).contains("\"script.activate\""), audit.get(1));
    }

    @Test
    void conflictWhenFileChangedOnDisk() throws Exception {
        service.saveDraft(gui(LEO_ADMIN), LEO, LEO_PARSER + "\n// draft\n", null);
        Files.writeString(dir.resolve("scripts/parser/leo.js"), LEO_PARSER + "\n// by hand\n");
        ScriptRequestException e = assertThrows(ScriptRequestException.class,
            () -> service.activate(gui(LEO_ADMIN), LEO, false, false));
        assertEquals(Reason.CONFLICT, e.reason());
        service.activate(gui(LEO_ADMIN), LEO, true, false);
        assertTrue(Files.readString(dir.resolve("scripts/parser/leo.js")).contains("// draft"));
    }

    @Test
    void highErrorRateNeedsConfirmation() throws Exception {
        String code = LEO_PARSER + "\n// v2\n";
        service.saveDraft(gui(LEO_ADMIN), LEO, code, null);
        service.recordTest(LEO, code, new TestSummary(Instant.now(), "pasted", 100, 2));
        ScriptRequestException e = assertThrows(ScriptRequestException.class,
            () -> service.activate(gui(LEO_ADMIN), LEO, false, false));
        assertEquals(Reason.CONFIRM, e.reason());
        service.activate(gui(LEO_ADMIN), LEO, false, true);
    }

    @Test
    void brokenScriptIsNotActivated() throws Exception {
        service.saveDraft(gui(LEO_ADMIN), LEO, "export function parse(", null);
        ScriptRequestException e = assertThrows(ScriptRequestException.class,
            () -> service.activate(gui(LEO_ADMIN), LEO, false, false));
        assertEquals(Reason.INVALID, e.reason());
        assertEquals(LEO_PARSER, parsers.forApp("leo").orElseThrow().code());
        assertEquals(LEO_PARSER, Files.readString(dir.resolve("scripts/parser/leo.js")));
    }

    @Test
    void rightsFollowTheApplications() throws Exception {
        assertTrue(service.may(LEO_ADMIN, LEO, LEO_PARSER, null));
        assertFalse(service.may(NO_READ, LEO, LEO_PARSER, null));
        assertFalse(service.may(LEO_ADMIN, MYCORE, null, null));
        assertTrue(service.may(ROOT, MYCORE, null, null));
        assertTrue(service.may(LEO_ADMIN, ERRORS, null, RULE));
        assertFalse(service.may(LEO_ADMIN, ERRORS, null, RULE_ALL));
        assertFalse(service.may(LEO_ADMIN, new ScriptId(ScriptKind.PSEUDONYM, "standard"), null, null));
        assertThrows(AccessDeniedException.class, () -> service.saveDraft(gui(LEO_ADMIN), ERRORS, RULE_ALL, null));
        assertThrows(AccessDeniedException.class, () -> service.get(LEO_ADMIN, MYCORE));
        assertEquals(List.of("parser/leo"),
            service.list(LEO_ADMIN).stream().map(s -> s.kind() + "/" + s.name()).toList());
        assertEquals(4, service.list(ROOT).size(), "two parsers, the lifecycle and the pseudonym template");
    }

    @Test
    void templateCanBeEditedIntoAFile() throws Exception {
        ScriptDetail template = service.get(ROOT, MYCORE);
        assertEquals("template", template.origin());
        service.saveDraft(gui(ROOT), MYCORE, template.code() + "\n// local\n", null);
        ScriptDetail file = service.activate(gui(ROOT), MYCORE, false, false);
        assertEquals("file", file.origin());
        assertTrue(parsers.forApp("digibib").orElseThrow().code().endsWith("// local\n"));
    }

    @Test
    void mcpMayActivateOnlyWithFlagAndOnlyParsersAndRules() throws Exception {
        service.saveDraft(mcp(LEO_ADMIN, false), ERRORS, RULE, null);
        assertThrows(AccessDeniedException.class, () -> service.activate(mcp(LEO_ADMIN, false), ERRORS, false,
            false));
        service.activate(mcp(LEO_ADMIN, true), ERRORS, false, false);
        assertEquals(List.of(ScriptKind.RULE), notified);
        assertEquals(Via.MCP, store.history(ERRORS).getFirst().via());

        ScriptId lifecycle = new ScriptId(ScriptKind.LIFECYCLE, "cut");
        service.saveDraft(mcp(LEO_ADMIN, true), lifecycle,
            "export const meta = { apps: [\"leo\"] };\nexport function line(text) { return text; }\n", null);
        assertThrows(AccessDeniedException.class, () -> service.activate(mcp(LEO_ADMIN, true), lifecycle, false,
            false));
        service.activate(gui(LEO_ADMIN), lifecycle, false, false);
    }

    @Test
    void deleteKeepsParsers() throws Exception {
        assertThrows(ScriptRequestException.class, () -> service.delete(gui(ROOT), LEO));
        service.saveDraft(gui(LEO_ADMIN), ERRORS, RULE, null);
        service.activate(gui(LEO_ADMIN), ERRORS, false, false);
        service.delete(gui(LEO_ADMIN), ERRORS);
        assertFalse(Files.exists(dir.resolve("scripts/rule/errors.js")));
        assertTrue(store.active(ERRORS).isEmpty());
    }

    @Test
    void reloadChecksEverythingFirst() throws Exception {
        Path rule = dir.resolve("scripts/rule/errors.js");
        Files.createDirectories(rule.getParent());
        Files.writeString(rule, RULE);
        Files.writeString(dir.resolve("scripts/parser/leo.js"), "export function parse(");
        ReloadResult broken = service.reload(null);
        assertEquals(2, broken.changed().size());
        assertFalse(broken.errors().isEmpty());
        assertTrue(store.active(ERRORS).isEmpty());
        assertEquals(LEO_PARSER, parsers.forApp("leo").orElseThrow().code());

        ReloadResult single = service.reload(ERRORS);
        assertEquals(List.of(), single.errors());
        assertEquals(RULE, store.active(ERRORS).orElseThrow());
        assertEquals(LEO_PARSER, parsers.forApp("leo").orElseThrow().code());

        String fixed = LEO_PARSER + "\n// fixed\n";
        Files.writeString(dir.resolve("scripts/parser/leo.js"), fixed);
        ReloadResult all = service.reload(null);
        assertEquals(List.of("parser/leo"), all.changed());
        assertEquals(fixed, parsers.forApp("leo").orElseThrow().code());
        assertEquals(Via.FILESYSTEM, store.history(LEO).getFirst().via());
        assertEquals(2, store.history(LEO).size());
    }

    @Test
    void sizeIsLimited() {
        String big = "// " + "x".repeat(20 * 1024);
        ScriptRequestException e = assertThrows(ScriptRequestException.class,
            () -> service.saveDraft(gui(LEO_ADMIN), LEO, big, null));
        assertEquals(Reason.INVALID, e.reason());
    }
}
