package de.gbv.logguy.server.pseudonym;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.gbv.logguy.server.config.AppGroups;
import de.gbv.logguy.server.script.ScriptKind;
import de.gbv.logguy.server.script.ScriptRuntime;
import de.gbv.logguy.server.script.ScriptStore;
import de.gbv.logguy.server.script.ScriptStore.ScriptId;
import de.gbv.logguy.server.pseudonym.PseudonymService.PseudonymTestResult;
import tools.jackson.databind.json.JsonMapper;

class PseudonymServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ScriptRuntime runtime;

    @TempDir
    Path dir;

    private ScriptStore store;

    private PseudonymService service;

    @BeforeAll
    static void startRuntime() {
        runtime = new ScriptRuntime(Duration.ofMillis(200), Duration.ofSeconds(5));
    }

    @AfterAll
    static void stopRuntime() {
        runtime.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(dir.resolve("scripts"));
        store = new ScriptStore(dir.resolve("scripts"), Clock.systemUTC(), JSON);
        store.scan();
        service = new PseudonymService(runtime, store, JSON, () -> AppGroups.NONE);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    private static Map<String, Object> event(String app, String message, Object... fields) {
        Map<String, Object> event = new HashMap<>();
        event.put("app", app);
        event.put("ts", "2026-10-01T10:00:00Z");
        event.put("message", message);
        for (int i = 0; i < fields.length; i += 2) {
            event.put((String) fields[i], fields[i + 1]);
        }
        return event;
    }

    @Test
    void standardTemplateReplacesLoginsIpsAndMails() {
        assertEquals(List.of(), service.errors());
        Pseudonyms session = new Pseudonyms();
        String line = "2026-10-01T00:00:00,152 INFO  mueller MCRServlet: login from 203.0.113.12 "
            + "mail=anna@example.org user=schulz";
        Map<String, Object> result = service.apply(session, event("digibib", line, "login", "mueller", "level",
            "INFO", "exc", List.of("x 10.0.0.1"))).orElseThrow();
        assertEquals("2026-10-01T00:00:00,152 INFO  $Nutzer1 MCRServlet: login from $IP1 mail=$Mail1 user=$Nutzer2",
            result.get("message"));
        assertEquals("$Nutzer1", result.get("login"));
        assertEquals("INFO", result.get("level"));
        assertEquals("digibib", result.get("app"));
        assertEquals("2026-10-01T10:00:00Z", result.get("ts"));
        assertEquals(List.of("x $IP2"), result.get("exc"));

        Map<String, Object> guest = service.apply(session, event("digibib", "2026-10-01T00:00:00,152 INFO  guest "
            + "MCRServlet: ok", "login", "guest")).orElseThrow();
        assertEquals("guest", guest.get("login"));
        Map<String, Object> detected = service.apply(session, event("digibib", "2026-10-01T00:00:00,152 INFO  "
            + "MCRSessionFilter: user detected: anna.beispiel@example.org")).orElseThrow();
        assertEquals("2026-10-01T00:00:00,152 INFO  MCRSessionFilter: user detected: $Nutzer3",
            detected.get("message"));
        assertEquals("$Nutzer3", service.apply(session, event("digibib", "x", "login", "anna.beispiel@example.org"))
            .orElseThrow().get("login"));
        assertEquals(Set.of("ip", "login"), service.protectedFields("digibib"));
        assertEquals(Optional.of("$IP1"), service.text(session, "digibib", "again 203.0.113.12")
            .map(text -> text.substring(6)));
    }

    @Test
    void standardTemplateReplacesUsersInAccessLogs() {
        Pseudonyms session = new Pseudonyms();
        String line = "203.0.113.7 - mueller [01/Oct/2026:00:00:05 +0200] \"GET /mir/ HTTP/1.1\" 200 5120";
        Map<String, Object> result = service.apply(session, event("digibib-apache", line, "ip", "203.0.113.7",
            "login", "mueller")).orElseThrow();
        assertEquals("$IP1 - $Nutzer1 [01/Oct/2026:00:00:05 +0200] \"GET /mir/ HTTP/1.1\" 200 5120",
            result.get("message"));
        assertEquals("$IP1", result.get("ip"));
        String anonymous = "203.0.113.8 - - [01/Oct/2026:00:00:05 +0200] \"GET / HTTP/1.1\" 200 1";
        assertEquals("$IP2 - - [01/Oct/2026:00:00:05 +0200] \"GET / HTTP/1.1\" 200 1",
            service.apply(session, event("digibib-apache", anonymous)).orElseThrow().get("message"));
    }

    @Test
    void standardTemplateReplacesUsersInApacheErrorLogs() {
        Pseudonyms session = new Pseudonyms();
        String line = "[Sat Oct 24 05:04:22.669917 2026] [auth_basic:error] [pid 8:tid 20] [client 172.17.0.1:36010]"
            + " AH01617: user mueller: authentication failure for \"/geheim/\": Password Mismatch";
        assertEquals("[Sat Oct 24 05:04:22.669917 2026] [auth_basic:error] [pid 8:tid 20] [client $IP1:36010]"
            + " AH01617: user $Nutzer1: authentication failure for \"/geheim/\": Password Mismatch",
            service.apply(session, event("digibib-apache", line, "login", "mueller")).orElseThrow().get("message"));
        assertEquals("[Sat Oct 24 05:04:22.674854 2026] [auth_basic:error] [pid 9:tid 30] [client $IP1:36024]"
            + " AH01618: user $Nutzer2 not found: /geheim/",
            service.apply(session, event("digibib-apache", "[Sat Oct 24 05:04:22.674854 2026] [auth_basic:error]"
                + " [pid 9:tid 30] [client 172.17.0.1:36024] AH01618: user schulze not found: /geheim/"))
                .orElseThrow().get("message"));
    }

    @Test
    void additionalScriptsRunInNameOrderAndOnlyForTheirApps() {
        Map<ScriptId, String> active = Map.of(new ScriptId(ScriptKind.PSEUDONYM, "zz-leo"), """
            export const meta = { apps: ["leo"], fields: ["session"] };
            export function apply(event) {
                event.session = ps("Sitzung", event.session);
                event.message = event.message.replace("$Nutzer1", "[user]");
                return event;
            }
            """);
        assertEquals(List.of(), service.load(active));
        Pseudonyms session = new Pseudonyms();
        Map<String, Object> leo = service.apply(session, event("leo", "user=mueller", "session", "ABC"))
            .orElseThrow();
        assertEquals("$Sitzung1", leo.get("session"));
        assertEquals("user=[user]", leo.get("message"));
        Map<String, Object> other = service.apply(session, event("digibib", "user=mueller", "session", "ABC"))
            .orElseThrow();
        assertEquals("ABC", other.get("session"));
        assertEquals(Set.of("ip", "login", "session"), service.protectedFields("leo"));
        assertEquals(Set.of("ip", "login"), service.protectedFields("digibib"));
    }

    @Test
    void archiveTokensStayAsTheyAre() {
        Pseudonyms session = new Pseudonyms();
        Map<String, Object> event = service.apply(session,
            event("digibib", "user detected: [Nutzer#3f9a1c2e]", "login", "[Nutzer#3f9a1c2e]")).orElseThrow();
        assertEquals("[Nutzer#3f9a1c2e]", event.get("login"));
        assertEquals("user detected: [Nutzer#3f9a1c2e]", event.get("message"));
    }

    @Test
    void failingScriptHoldsEventsBack() {
        assertEquals(List.of(), service.load(Map.of(new ScriptId(ScriptKind.PSEUDONYM, "fail"), """
            export const meta = { apps: ["*"], fields: [] };
            export function apply(event) {
                if (event.message === "spin") { while (true) {} }
                if (event.message === "fail") { throw new Error("no"); }
                return event;
            }
            """)));
        Pseudonyms session = new Pseudonyms();
        assertTrue(service.apply(session, event("a", "fail")).isEmpty());
        assertTrue(service.apply(session, event("a", "spin")).isEmpty());
        assertTrue(service.apply(session, event("a", "ok")).isPresent());

        List<String> errors = service.load(Map.of(new ScriptId(ScriptKind.PSEUDONYM, "broken"), "export const"));
        assertEquals(1, errors.size());
        assertFalse(service.errors().isEmpty());
        assertTrue(service.apply(session, event("a", "ok")).isEmpty());
    }

    @Test
    void fileReplacesTemplate() {
        assertEquals(List.of(), service.load(Map.of(new ScriptId(ScriptKind.PSEUDONYM, "standard"), """
            export const meta = { apps: ["*"], fields: [] };
            export function apply(event) { event.message = maskIp(event.message); return event; }
            """)));
        Map<String, Object> result = service.apply(new Pseudonyms(), event("a", "10.1.2.3", "login", "mueller"))
            .orElseThrow();
        assertEquals("10.1.2.0", result.get("message"));
        assertEquals("mueller", result.get("login"));
        assertEquals(Set.of(), service.protectedFields("a"));
    }

    @Test
    void testCountsPseudonymsAndFindsLeftovers() throws Exception {
        PseudonymTestResult result = service.test(new ScriptId(ScriptKind.PSEUDONYM, "draft"), """
            export const meta = { apps: ["*"], fields: [] };
            export function apply(event) {
                if (event.message === "fail") { throw new Error("no"); }
                event.message = replaceIps(event.message, ip => ps("IP", ip));
                return event;
            }
            """, List.of(event("a", "from 10.0.0.1"), event("a", "fail"), event("a", "mail anna@example.org"),
            event("a", "from 10.0.0.1 and 10.0.0.2")), 2);
        assertEquals(4, result.events());
        assertEquals(1, result.heldBack());
        assertEquals(Map.of("IP", 2), result.pseudonyms());
        assertEquals(2, result.sample().size());
        assertEquals(1, result.suspicious().size());
        assertEquals("Mail", result.suspicious().getFirst().category());
        assertEquals(3, result.suspicious().getFirst().event());
    }
}
