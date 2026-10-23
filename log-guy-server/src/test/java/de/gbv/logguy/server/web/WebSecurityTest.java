package de.gbv.logguy.server.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.gbv.logguy.server.security.SecurityConfig;
import de.gbv.logguy.server.security.TokenRegistry;

@SpringBootTest
@AutoConfigureMockMvc
class WebSecurityTest {

    private static final String API_TOKEN = "lg_api_test";

    private static final String INGEST_TOKEN = "lg_ingest_test";

    private static final String LIMITED_TOKEN = "lg_api_limited";

    private static final String PASSWORD = "geheim-genug";

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String hash = SecurityConfig.passwordEncoder().encode(PASSWORD);
        registry.add("log-guy.data-dir", dataDir::toString);
        registry.add("log-guy.indexer.enabled", () -> "false");
        registry.add("log-guy.apps.digibib.parser", () -> "mycore-log4j");
        registry.add("log-guy.apps.other.parser", () -> "mycore-log4j");
        registry.add("log-guy.users[0].name", () -> "anna");
        registry.add("log-guy.users[0].password-hash", () -> hash);
        registry.add("log-guy.users[0].grants[0].apps", () -> "digi*");
        registry.add("log-guy.users[0].grants[0].rights", () -> "read");
        registry.add("log-guy.users[1].name", () -> "root");
        registry.add("log-guy.users[1].password-hash", () -> hash);
        registry.add("log-guy.users[1].grants[0].apps", () -> "*");
        registry.add("log-guy.users[1].grants[0].rights", () -> "admin");
        registry.add("log-guy.tokens[0].name", () -> "anna-api");
        registry.add("log-guy.tokens[0].kind", () -> "api");
        registry.add("log-guy.tokens[0].user", () -> "anna");
        registry.add("log-guy.tokens[0].hash", () -> TokenRegistry.hash(API_TOKEN));
        registry.add("log-guy.tokens[1].name", () -> "agent-app1");
        registry.add("log-guy.tokens[1].kind", () -> "ingest");
        registry.add("log-guy.tokens[1].host", () -> "app1");
        registry.add("log-guy.tokens[1].apps", () -> "digibib");
        registry.add("log-guy.tokens[1].hash", () -> TokenRegistry.hash(INGEST_TOKEN));
        registry.add("log-guy.tokens[2].name", () -> "root-other");
        registry.add("log-guy.tokens[2].kind", () -> "api");
        registry.add("log-guy.tokens[2].user", () -> "root");
        registry.add("log-guy.tokens[2].apps", () -> "other");
        registry.add("log-guy.tokens[2].hash", () -> TokenRegistry.hash(LIMITED_TOKEN));
    }

    @Autowired
    MockMvc mvc;

    @Test
    void apiNeedsTokenOrSession() throws Exception {
        mvc.perform(get("/api/v1/fields")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/fields").header("Authorization", "Bearer lg_api_wrong"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/fields").header("Authorization", "Bearer " + INGEST_TOKEN))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/fields").header("Authorization", "Bearer " + API_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.apps[0]").value("digibib"))
            .andExpect(jsonPath("$.apps.length()").value(1));
        mvc.perform(get("/api/v1/fields").param("apps", "other").header("Authorization", "Bearer " + API_TOKEN))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(containsString("other")));
        mvc.perform(get("/api/v1/fields").header("Authorization", "Bearer " + LIMITED_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.apps.length()").value(1))
            .andExpect(jsonPath("$.apps[0]").value("other"));
    }

    @Test
    void guiNeedsLogin() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(content().string(containsString("Anmelden")));
        mvc.perform(get("/static/css/log-guy.css")).andExpect(status().isOk());
        mvc.perform(formLogin("/login").user("anna").password("falsch")).andExpect(unauthenticated());
        MvcResult login = mvc.perform(formLogin("/login").user("anna").password(PASSWORD))
            .andExpect(authenticated())
            .andExpect(redirectedUrl("/"))
            .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession();
        mvc.perform(get("/").session(session))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("anna")));
        mvc.perform(get("/api/v1/fields").session(session)).andExpect(status().isOk());
        mvc.perform(get("/admin/status").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/loggers").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/logout").session(session).with(csrf())).andExpect(redirectedUrl("/login?logout"));
    }

    @Test
    void adminSeesActuator() throws Exception {
        MvcResult login = mvc.perform(formLogin("/login").user("root").password(PASSWORD))
            .andExpect(authenticated())
            .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession();
        mvc.perform(get("/actuator/loggers").session(session)).andExpect(status().isOk());
        mvc.perform(get("/admin/status").session(session))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("status.js")));
        mvc.perform(get("/admin/log").session(session)).andExpect(status().isOk());
        LogManager.getLogger("de.gbv.logguy.test").warn("marker for the ring buffer");
        mvc.perform(get("/admin/api/log").param("level", "WARN").param("logger", "logguy.test").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entries[-1:].message").value(hasItem("marker for the ring buffer")));
        mvc.perform(get("/admin/api/log").param("level", "LOUD").session(session))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/api/log").header("Authorization", "Bearer " + LIMITED_TOKEN))
            .andExpect(result -> assertNotEquals(200, result.getResponse().getStatus()));
    }
}
