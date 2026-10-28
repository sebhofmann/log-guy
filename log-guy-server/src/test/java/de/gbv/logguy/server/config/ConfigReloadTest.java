package de.gbv.logguy.server.config;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;

import de.gbv.logguy.server.ServerConfig;
import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.config.LogGuyProperties.TokenKind;
import de.gbv.logguy.server.config.LogGuyProperties.TokenProperties;
import de.gbv.logguy.server.security.ControlToken;
import de.gbv.logguy.server.security.SecurityConfig;
import de.gbv.logguy.server.security.TokenRegistry;

/**
 * A reload of an emptied or incomplete {@code application.yml} takes over nothing, and a wrong configuration stops
 * the start of the server.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConfigReloadTest {

    private static final String LOCATION = "spring.config.additional-location";

    private static final String PASSWORD = "geheim-genug";

    private static final String HASH = SecurityConfig.passwordEncoder().encode(PASSWORD);

    private static final String INGEST_HASH = TokenRegistry.hash(TokenRegistry.generate(TokenKind.INGEST));

    private static final Path DIR;

    // the configuration file must be known before the environment is prepared, so no @TempDir here
    static {
        try {
            DIR = Files.createTempDirectory("log-guy-reload");
            write(config("root", "\"*\""));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.setProperty(LOCATION, "file:" + DIR.resolve("application.yml"));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("log-guy.data-dir", () -> DIR.resolve("data").toString());
        registry.add("log-guy.scripts.dir", () -> DIR.resolve("data/scripts").toString());
        registry.add("log-guy.indexer.enabled", () -> "false");
    }

    @AfterAll
    static void cleanUp() throws IOException {
        System.clearProperty(LOCATION);
        FileSystemUtils.deleteRecursively(DIR);
    }

    @Autowired
    MockMvc mvc;

    private static String config(String admin, String adminApps) {
        return """
            log-guy:
              apps:
                digibib:
                  parser: mycore-log4j
              users:
                - name: %s
                  password-hash: "%s"
                  grants:
                    - apps: [%s]
                      rights: [admin]
              tokens:
                - name: agent-app1
                  kind: ingest
                  host: app1
                  apps: [digibib]
                  hash: "%s"
            """.formatted(admin, HASH, adminApps, INGEST_HASH);
    }

    private static void write(String yaml) throws IOException {
        Files.writeString(DIR.resolve("application.yml"), yaml);
    }

    @Test
    void incompleteConfigurationIsRejected() throws Exception {
        String control = ControlToken.read(DIR.resolve("data"));
        write("log-guy: {}\n");
        mvc.perform(post("/admin/reload").header("Authorization", "Bearer " + control))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.errors").value(hasItem(containsString("keine Anwendungen"))))
            .andExpect(jsonPath("$.errors").value(hasItem(containsString("keine Nutzer"))))
            .andExpect(jsonPath("$.errors").value(hasItem(containsString("keine Tokens"))))
            .andExpect(jsonPath("$.errors").value(hasItem(containsString("kein Administrator"))));
        mvc.perform(formLogin("/login").user("root").password(PASSWORD)).andExpect(authenticated());

        write(config("root", "digibib"));
        mvc.perform(post("/admin/reload").header("Authorization", "Bearer " + control))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.errors.length()").value(1))
            .andExpect(jsonPath("$.errors[0]").value(containsString("kein Administrator")));

        write(config("chef", "\"*\""));
        mvc.perform(post("/admin/reload").header("Authorization", "Bearer " + control))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
        mvc.perform(formLogin("/login").user("chef").password(PASSWORD)).andExpect(authenticated());
        mvc.perform(formLogin("/login").user("root").password(PASSWORD)).andExpect(unauthenticated());
        write(config("root", "\"*\""));
        mvc.perform(post("/admin/reload").header("Authorization", "Bearer " + control))
            .andExpect(status().isOk());
    }

    @Test
    void wrongConfigurationStopsTheStart() {
        LogGuyProperties properties = new LogGuyProperties(Path.of("/tmp"), ZoneId.of("Europe/Berlin"),
            Map.of("digibib", new AppProperties(7, "mycore-log4j")),
            List.of(new TokenProperties("agent", TokenKind.INGEST, null, List.of("digibib"), null, INGEST_HASH,
                false)),
            List.of(), null, null, null, null, null, null, null, null, null, null, null, null, null);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new ServerConfig(properties));
        assertTrue(e.getMessage().contains("ingest token needs a valid host"),
            e.getMessage());
    }
}
