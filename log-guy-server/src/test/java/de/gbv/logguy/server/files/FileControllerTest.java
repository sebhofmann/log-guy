package de.gbv.logguy.server.files;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.server.archive.ArchiveStore;
import de.gbv.logguy.server.hot.HotFile;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.security.SecurityConfig;

/**
 * The log browser for users who may only read pseudonymized.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FileControllerTest {

    private static final String PASSWORD = "geheim-genug";

    private static final byte[] LOG = """
        2026-10-01T00:00:00,152 INFO  mueller MCRServlet: login from 192.168.17.20
        2026-10-01T00:00:01,000 INFO  guest MCRServlet: withhold-me please
        2026-10-01T00:00:02,000 INFO  guest MCRServlet: done
        """.getBytes(StandardCharsets.UTF_8);

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path pseudonym = dataDir.resolve("scripts/pseudonym");
        Files.createDirectories(pseudonym);
        Files.writeString(pseudonym.resolve("withhold.js"), """
            export const meta = { apps: ["*"] };
            export function apply(event) {
                if (String(event.message).includes("withhold-me")) {
                    throw new Error("held back");
                }
                return event;
            }
            """);
        String hash = SecurityConfig.passwordEncoder().encode(PASSWORD);
        registry.add("log-guy.data-dir", dataDir::toString);
        registry.add("log-guy.scripts.dir", () -> dataDir.resolve("scripts").toString());
        registry.add("log-guy.indexer.enabled", () -> "false");
        registry.add("log-guy.archive.poll-interval", () -> "50ms");
        registry.add("log-guy.apps.digibib.parser", () -> "mycore-log4j");
        registry.add("log-guy.users[0].name", () -> "pia");
        registry.add("log-guy.users[0].password-hash", () -> hash);
        registry.add("log-guy.users[0].grants[0].apps", () -> "digibib");
        registry.add("log-guy.users[0].grants[0].rights", () -> "archive,read_pseudonymized");
        registry.add("log-guy.users[1].name", () -> "paul");
        registry.add("log-guy.users[1].password-hash", () -> hash);
        registry.add("log-guy.users[1].grants[0].apps", () -> "digibib");
        registry.add("log-guy.users[1].grants[0].rights", () -> "archive,read");
        registry.add("log-guy.users[2].name", () -> "olga");
        registry.add("log-guy.users[2].password-hash", () -> hash);
        registry.add("log-guy.users[2].grants[0].apps", () -> "digibib");
        registry.add("log-guy.users[2].grants[0].rights", () -> "read_pseudonymized");
        registry.add("log-guy.users[3].name", () -> "root");
        registry.add("log-guy.users[3].password-hash", () -> hash);
        registry.add("log-guy.users[3].grants[0].apps", () -> "*");
        registry.add("log-guy.users[3].grants[0].rights", () -> "admin");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    HotStore hot;

    @Autowired
    ArchiveStore archive;

    private String fingerprint;

    @BeforeEach
    void archiveSample() throws Exception {
        fingerprint = Fingerprint.of(LOG);
        if (hot.find(fingerprint).isEmpty()) {
            HotFile file = hot.getOrCreate(fingerprint, "app1", "digibib");
            file.append(0, LOG, LOG.length, "/logs/mycore.log", Instant.now());
            file.close(LOG.length, "/logs/mycore.log", Instant.now());
        }
        Instant deadline = Instant.now().plusSeconds(30);
        while (!archive.find(fingerprint).map(a -> a.meta().closed()).orElse(false)) {
            assertTrue(Instant.now().isBefore(deadline), "archive not written");
            Thread.sleep(20);
        }
    }

    private MockHttpSession login(String name) throws Exception {
        return (MockHttpSession) mvc.perform(formLogin("/login").user(name).password(PASSWORD)).andReturn()
            .getRequest().getSession();
    }

    @Test
    void pseudonymizedReaderSeesPseudonymizedArchive() throws Exception {
        MockHttpSession pia = login("pia");
        mvc.perform(get("/api/v1/files").session(pia))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.files[*].fingerprint").value(hasItem(fingerprint)))
            .andExpect(jsonPath("$.apps").value(hasItem("digibib")));
        mvc.perform(get("/api/v1/files/" + fingerprint).session(pia))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.may.pseudonymized").value(true))
            .andExpect(jsonPath("$.may.download").value(false))
            .andExpect(jsonPath("$.may.hot").value(false));
        mvc.perform(get("/api/v1/files/" + fingerprint + "/content").session(pia))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines.length()").value(3))
            .andExpect(jsonPath("$.lines[0].text").value(containsString("$Nutzer")))
            .andExpect(jsonPath("$.lines[0].text").value(containsString("$IP")))
            .andExpect(jsonPath("$.lines[0].text").value(not(containsString("mueller"))))
            .andExpect(jsonPath("$.lines[1].text").value("[zurückgehalten]"))
            .andExpect(jsonPath("$.lines[2].text").value(containsString("done")));
        mvc.perform(get("/api/v1/files/" + fingerprint + "/search").param("q", "done").session(pia))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("done")));
        mvc.perform(get("/api/v1/files/" + fingerprint + "/download").session(pia))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/files/" + fingerprint + "/content").param("source", "HOT").session(pia))
            .andExpect(status().isForbidden());
    }

    @Test
    void plainReaderAndUserWithoutArchive() throws Exception {
        MockHttpSession paul = login("paul");
        mvc.perform(get("/api/v1/files/" + fingerprint + "/content").session(paul))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines[0].text").value(containsString("mueller")))
            .andExpect(jsonPath("$.lines[0].text").value(containsString("192.168.17.0")))
            .andExpect(jsonPath("$.lines[1].text").value(containsString("withhold-me")));
        mvc.perform(get("/api/v1/files/" + fingerprint + "/download").session(paul))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/files/" + fingerprint + "/content").param("source", "HOT").session(paul))
            .andExpect(status().isForbidden());

        MockHttpSession olga = login("olga");
        mvc.perform(get("/api/v1/files").session(olga))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.files.length()").value(0));
        mvc.perform(get("/api/v1/files/" + fingerprint + "/content").session(olga))
            .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/files/" + fingerprint + "/content").param("source", "HOT").session(login("root")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines[0].text").value(containsString("192.168.17.20")));
    }
}
