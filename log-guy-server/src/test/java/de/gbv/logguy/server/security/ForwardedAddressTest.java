package de.gbv.logguy.server.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The client address of the login limit comes from {@code X-Forwarded-For} when nginx on the same host sends the
 * request, as configured in {@code application.yml}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ForwardedAddressTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("log-guy.data-dir", dataDir::toString);
        registry.add("log-guy.scripts.dir", () -> dataDir.resolve("scripts").toString());
        registry.add("log-guy.indexer.enabled", () -> "false");
    }

    @LocalServerPort
    int port;

    @Autowired
    LoginThrottle throttle;

    @Autowired
    LoginProperties settings;

    private String loginPage(String forwardedFor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login"));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).body();
        }
    }

    @Test
    void addressOfTheProxiedClientCounts() throws Exception {
        for (int i = 0; i < settings.maxFailures(); i++) {
            throttle.failure("10.7.7.7");
        }
        assertTrue(loginPage("10.7.7.7").contains("Zu viele Fehlversuche"));
        assertFalse(loginPage("10.7.7.8").contains("Zu viele Fehlversuche"));
        assertFalse(loginPage(null).contains("Zu viele Fehlversuche"));
    }
}
