package de.gbv.logguy.server.search;

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
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.unit.DataSize;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.server.archive.LifecycleFilters;
import de.gbv.logguy.server.config.AppGroups;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.config.LogGuyProperties.ArchiveProperties;
import de.gbv.logguy.server.config.LogGuyProperties.ArchivePseudonymizeProperties;
import de.gbv.logguy.server.config.LogGuyProperties.GrantProperties;
import de.gbv.logguy.server.config.LogGuyProperties.HotProperties;
import de.gbv.logguy.server.config.LogGuyProperties.Right;
import de.gbv.logguy.server.config.LogGuyProperties.ScriptProperties;
import de.gbv.logguy.server.config.LogGuyProperties.SearchProperties;
import de.gbv.logguy.server.hot.HotFile;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.pseudonym.ArchiveTokens;
import de.gbv.logguy.server.pseudonym.PseudonymService;
import de.gbv.logguy.server.script.ScriptKind;
import de.gbv.logguy.server.script.ScriptRuntime;
import de.gbv.logguy.server.script.ScriptStore;
import de.gbv.logguy.server.script.ScriptStore.ScriptId;
import de.gbv.logguy.server.search.ContextService.ContextView;
import de.gbv.logguy.server.security.LogGuyUser;
import tools.jackson.databind.json.JsonMapper;

/**
 * The context passes the lifecycle filters and the pseudonymization like the archive and the index.
 */
class ContextServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String PASSWORD_FILTER = """
        export const meta = { apps: ["digibib"] };
        export function fields(event) {
            event.message = event.message.replace(/password=\\S+/g, "password=***");
            return event;
        }
        """;

    private static final String LINE_FILTER = """
        export const meta = { apps: ["digibib"] };
        export function line(text) {
            if (text.includes("GET /health")) {
                return null;
            }
            if (text.includes("boom")) {
                throw new Error("kaputt");
            }
            if (text.includes("forever")) {
                while (true) {}
            }
            return text;
        }
        """;

    private static final byte[] LOG = ("""
        2026-10-01T00:00:00,000 INFO  guest MCRServlet: start from 192.168.17.20
        2026-10-01T00:00:01,000 INFO  guest MCRServlet: GET /health
        2026-10-01T00:00:02,000 WARN  mueller MCRServlet: login failed password=geheim
        2026-10-01T00:00:03,000 INFO  guest MCRServlet: boom
        2026-10-01T00:00:04,000 INFO  guest MCRServlet: forever
        \u001B[32m2026-10-01T00:00:05,000 INFO  guest MCRServlet: password=auch-geheim end\u001B[0m\r
        """).getBytes(StandardCharsets.UTF_8);

    private static final LogGuyUser READER =
        LogGuyUser.of("reader", List.of(new GrantProperties(List.of("digibib"), Set.of(Right.READ))));

    private static final LogGuyUser ADMIN =
        LogGuyUser.of("admin", List.of(new GrantProperties(List.of("digibib"), Set.of(Right.ADMIN))));

    @TempDir
    Path dataDir;

    private ScriptRuntime runtime;

    private ScriptStore scripts;

    private LifecycleFilters filters;

    private ContextService context;

    private String fingerprint;

    private String mueller;

    private long passwordOffset;

    @BeforeEach
    void setUp() throws Exception {
        runtime = new ScriptRuntime(Duration.ofMillis(200), Duration.ofSeconds(5));
        Path scriptDir = dataDir.resolve("scripts");
        Files.createDirectories(scriptDir.resolve("lifecycle"));
        Files.writeString(scriptDir.resolve("lifecycle/passwort.js"), PASSWORD_FILTER);
        Files.writeString(scriptDir.resolve("lifecycle/zeilen.js"), LINE_FILTER);
        LogGuyProperties properties = new LogGuyProperties(dataDir, ZoneId.of("Europe/Berlin"),
            Map.of("digibib", new AppProperties(null, "mycore-log4j")), List.of(), List.of(), null,
            new HotProperties(DataSize.ofKilobytes(16), Duration.ofSeconds(60), Duration.ofSeconds(5), 3),
            new ScriptProperties(
                scriptDir, Duration.ofMillis(200), Duration.ofSeconds(5), 100, DataSize.ofKilobytes(256), 1000, 100),
            null,
            null, new SearchProperties(Duration.ofHours(24), 100, 1000, 300, 8, 10, 100, 20, 500,
                DataSize.ofKilobytes(512)),
            null, null, null,
            new ArchiveProperties(true, 2, Duration.ofMillis(50), 3, 7, "[line {filter} {offset}]", "[field {filter}]",
                null, new ArchivePseudonymizeProperties(List.of("IP"))),
            null, null, null, null);
        HotStore hot = new HotStore(properties, JSON, Clock.systemUTC());
        fingerprint = Fingerprint.of(LOG);
        HotFile file = hot.getOrCreate(fingerprint, "app1", "digibib");
        file.append(0, LOG, LOG.length, "/logs/mycore.log", Instant.now());
        file.close(LOG.length, "/logs/mycore.log", Instant.now());
        mueller = new ArchiveTokens(file.meta().key(), Set.of("IP")).token("Nutzer", "mueller");
        passwordOffset = new String(LOG, StandardCharsets.UTF_8).indexOf("2026-10-01T00:00:02");
        scripts = new ScriptStore(scriptDir, Clock.systemUTC(), JSON);
        scripts.scan();
        filters = new LifecycleFilters(runtime, scripts, JSON, () -> AppGroups.NONE);
        context = new ContextService(properties, hot, null, filters,
            new PseudonymService(runtime, scripts, JSON, () -> AppGroups.NONE));
    }

    @AfterEach
    void tearDown() {
        context.close();
        runtime.close();
    }

    @Test
    void contextIsFilteredLikeArchiveAndIndex() throws Exception {
        ContextView view = context.context(READER, fingerprint + ":" + passwordOffset, 5, 5);
        List<String> all = new java.util.ArrayList<>(view.before());
        all.addAll(view.lines());
        String text = String.join("\n", all);
        assertFalse(text.contains("geheim"), text);
        assertFalse(text.contains("GET /health"), text);
        assertFalse(text.contains("192.168.17.20"), text);
        assertFalse(text.contains("\u001B"), text);
        assertFalse(text.contains("\r"), text);
        assertEquals(List.of("2026-10-01T00:00:00,000 INFO  guest MCRServlet: start from 192.168.17.0"),
            view.before());
        // pseudonymized with the key of the file, the same token as in the archive
        assertTrue(mueller.matches("\\[Nutzer#[0-9a-f]{8}]"), mueller);
        assertEquals("2026-10-01T00:00:02,000 WARN  " + mueller + " MCRServlet: login failed password=***",
            view.lines().get(0));
        long boom = new String(LOG, StandardCharsets.UTF_8).indexOf("2026-10-01T00:00:03");
        assertEquals("[line zeilen " + boom + "]", view.lines().get(1));
        // the watchdog stops the endless filter, the line is withheld as well
        assertTrue(view.lines().get(2).startsWith("[line zeilen "), view.lines().get(2));
        assertEquals("2026-10-01T00:00:05,000 INFO  guest MCRServlet: password=*** end", view.lines().get(3));
        assertTrue(view.start());
        assertTrue(view.end());

        // a dropped line does not count, the line before it is shown instead
        ContextView one = context.context(READER, fingerprint + ":" + passwordOffset, 1, 1);
        assertEquals(1, one.before().size());
        assertTrue(one.before().getFirst().contains("start from"), one.before().toString());
        assertTrue(one.start());
        assertFalse(one.end());
    }

    @Test
    void originalOnlyForAdministrators() throws Exception {
        String id = fingerprint + ":" + passwordOffset;
        assertThrows(AccessDeniedException.class, () -> context.context(READER, id, 1, 1, true));
        ContextView original = context.context(ADMIN, id, 1, 1, true);
        assertEquals("2026-10-01T00:00:01,000 INFO  guest MCRServlet: GET /health", original.before().getFirst());
        assertTrue(original.lines().getFirst().contains("password=geheim"));
        assertFalse(context.context(ADMIN, id, 1, 1).lines().getFirst().contains("geheim"));
    }

    @Test
    void brokenFilterStopsTheContext() throws Exception {
        Map<ScriptId, String> active = new java.util.HashMap<>(scripts.active());
        active.put(new ScriptId(ScriptKind.LIFECYCLE, "kaputt"), "export const meta = { apps: [\"*\"] ;");
        assertFalse(filters.load(active).isEmpty());
        assertThrows(ContextUnavailableException.class,
            () -> context.context(READER, fingerprint + ":" + passwordOffset, 1, 1));
        assertTrue(filters.load(scripts.active()).isEmpty());
        assertTrue(context.context(READER, fingerprint + ":" + passwordOffset, 1, 1).lines().getFirst()
            .contains("password=***"));
    }
}
