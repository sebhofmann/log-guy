package de.gbv.logguy.server.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import de.gbv.logguy.server.security.SecurityConfig;
import de.gbv.logguy.server.security.TokenRegistry;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AlertApiTest {

    private static final String API_TOKEN = "lg_api_alerts";

    private static final String PASSWORD = "geheim-genug";

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("log-guy.data-dir", dataDir::toString);
        registry.add("log-guy.scripts.dir", () -> dataDir.resolve("scripts").toString());
        registry.add("log-guy.indexer.enabled", () -> "false");
        registry.add("log-guy.apps.digibib.parser", () -> "mycore-log4j");
        registry.add("log-guy.users[0].name", () -> "root");
        registry.add("log-guy.users[0].password-hash", () -> SecurityConfig.passwordEncoder().encode(PASSWORD));
        registry.add("log-guy.users[0].grants[0].apps", () -> "*");
        registry.add("log-guy.users[0].grants[0].rights", () -> "admin");
        registry.add("log-guy.tokens[0].name", () -> "root-api");
        registry.add("log-guy.tokens[0].kind", () -> "api");
        registry.add("log-guy.tokens[0].user", () -> "root");
        registry.add("log-guy.tokens[0].hash", () -> TokenRegistry.hash(API_TOKEN));
    }

    @LocalServerPort
    int port;

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + API_TOKEN);
    }

    @Test
    void listsAndRejectsUnknownAlerts() throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> list = http.send(request("/api/v1/alerts?state=all").build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, list.statusCode());
            assertEquals("[]", list.body());
            HttpResponse<String> unknown = http.send(request("/api/v1/alerts?state=kaputt").build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(400, unknown.statusCode());
            HttpResponse<String> ack = http.send(request("/api/v1/alerts/nope/ack")
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, ack.statusCode());
            HttpResponse<String> rules = http.send(request("/api/v1/alerts/rules").build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals("{\"rules\":[],\"errors\":[]}", rules.body());
        }
    }

    @Test
    void streamStartsWithTheState() throws Exception {
        assertStreamStarts(request("/api/v1/alerts/stream").build());
    }

    @Test
    void streamStartsWithTheStateForASession() throws Exception {
        String base = "http://localhost:" + port;
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create(base + "/login")).build(),
                HttpResponse.BodyHandlers.ofString());
            String cookie = cookie(page);
            Matcher token = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(page.body());
            assertTrue(token.find());
            HttpResponse<String> login = http.send(HttpRequest.newBuilder(URI.create(base + "/login"))
                .header("Cookie", cookie).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=root&password=" + PASSWORD + "&_csrf="
                    + URLEncoder.encode(token.group(1), StandardCharsets.UTF_8)))
                .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(302, login.statusCode());
            assertStreamStarts(HttpRequest.newBuilder(URI.create(base + "/api/v1/alerts/stream"))
                .header("Cookie", cookie(login)).build());
        }
    }

    private static String cookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("JSESSIONID="))
            .map(c -> c.substring(0, c.indexOf(';'))).findFirst().orElseThrow();
    }

    private void assertStreamStarts(HttpRequest streamRequest) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        try {
            HttpResponse<InputStream> response = http.sendAsync(streamRequest,
                HttpResponse.BodyHandlers.ofInputStream()).get(10, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(),
                StandardCharsets.UTF_8))) {
                String first = CompletableFuture.supplyAsync(() -> {
                    try {
                        return reader.readLine() + "\n" + reader.readLine();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).get(Duration.ofSeconds(10).toSeconds(), TimeUnit.SECONDS);
                assertTrue(first.startsWith("event: state\ndata: {\"seq\":"), first);
            }
        } finally {
            // the stream stays open, close would wait for it
            http.shutdownNow();
        }
    }
}
