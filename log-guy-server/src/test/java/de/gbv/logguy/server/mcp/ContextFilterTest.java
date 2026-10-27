package de.gbv.logguy.server.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.server.hot.HotFile;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.security.SecurityConfig;
import de.gbv.logguy.server.security.TokenRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * A password that a {@code fields(event)} filter removes from the index does not leave through the context, neither
 * for a reader over the API nor for an MCP token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContextFilterTest {

    private static final String READER_API = "lg_api_reader";

    private static final String READER_MCP = "lg_mcp_reader";

    private static final String ADMIN_API = "lg_api_root";

    private static final byte[] LOG = """
        2026-10-01T00:00:00,000 INFO  guest MCRServlet: before password=vorher
        2026-10-01T00:00:01,000 WARN  mueller MCRServlet: login failed password=geheim
        2026-10-01T00:00:02,000 INFO  guest MCRServlet: after
        """.getBytes(StandardCharsets.UTF_8);

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path lifecycle = dataDir.resolve("scripts/lifecycle");
        Files.createDirectories(lifecycle);
        Files.writeString(lifecycle.resolve("passwort.js"), """
            export const meta = { apps: ["digibib"] };
            export function fields(event) {
                event.message = event.message.replace(/password=\\S+/g, "password=***");
                return event;
            }
            """);
        String hash = SecurityConfig.passwordEncoder().encode("geheim-genug");
        registry.add("log-guy.data-dir", dataDir::toString);
        registry.add("log-guy.scripts.dir", () -> dataDir.resolve("scripts").toString());
        registry.add("log-guy.indexer.enabled", () -> "false");
        registry.add("log-guy.apps.digibib.parser", () -> "mycore-log4j");
        registry.add("log-guy.users[0].name", () -> "reader");
        registry.add("log-guy.users[0].password-hash", () -> hash);
        registry.add("log-guy.users[0].grants[0].apps", () -> "digibib");
        registry.add("log-guy.users[0].grants[0].rights", () -> "read");
        registry.add("log-guy.users[1].name", () -> "root");
        registry.add("log-guy.users[1].password-hash", () -> hash);
        registry.add("log-guy.users[1].grants[0].apps", () -> "*");
        registry.add("log-guy.users[1].grants[0].rights", () -> "admin");
        registry.add("log-guy.tokens[0].name", () -> "reader-api");
        registry.add("log-guy.tokens[0].kind", () -> "api");
        registry.add("log-guy.tokens[0].user", () -> "reader");
        registry.add("log-guy.tokens[0].hash", () -> TokenRegistry.hash(READER_API));
        registry.add("log-guy.tokens[1].name", () -> "reader-mcp");
        registry.add("log-guy.tokens[1].kind", () -> "mcp");
        registry.add("log-guy.tokens[1].user", () -> "reader");
        registry.add("log-guy.tokens[1].hash", () -> TokenRegistry.hash(READER_MCP));
        registry.add("log-guy.tokens[2].name", () -> "root-api");
        registry.add("log-guy.tokens[2].kind", () -> "api");
        registry.add("log-guy.tokens[2].user", () -> "root");
        registry.add("log-guy.tokens[2].hash", () -> TokenRegistry.hash(ADMIN_API));
    }

    @LocalServerPort
    int port;

    @Autowired
    HotStore hot;

    private String id;

    @BeforeEach
    void storeSample() throws Exception {
        String fingerprint = Fingerprint.of(LOG);
        id = fingerprint + ":" + new String(LOG, StandardCharsets.UTF_8).indexOf("2026-10-01T00:00:01");
        if (hot.find(fingerprint).isEmpty()) {
            HotFile file = hot.getOrCreate(fingerprint, "app1", "digibib");
            file.append(0, LOG, LOG.length, null, Instant.now());
            file.close(LOG.length, null, Instant.now());
        }
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + token)
            .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void readerSeesNoPassword() throws Exception {
        HttpResponse<String> response = get("/api/v1/events/" + id + "/context?before=5&after=5", READER_API);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("login failed password=***"), response.body());
        assertFalse(response.body().contains("geheim"), response.body());
        assertFalse(response.body().contains("vorher"), response.body());

        assertEquals(403, get("/api/v1/events/" + id + "/context?original=true", READER_API).statusCode());
        HttpResponse<String> original = get("/api/v1/events/" + id + "/context?original=true", ADMIN_API);
        assertEquals(200, original.statusCode(), original.body());
        assertTrue(original.body().contains("password=geheim"), original.body());
    }

    @Test
    void mcpTokenSeesNoPassword() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
            .builder("http://localhost:" + port)
            .endpoint("/mcp")
            .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + READER_MCP))
            .build();
        try (McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build()) {
            client.initialize();
            CallToolResult result = client.callTool(new CallToolRequest("get_context",
                Map.of("id", id, "before", 5, "after", 5)));
            String text = ((TextContent) result.content().getFirst()).text();
            assertFalse(Boolean.TRUE.equals(result.isError()), text);
            assertTrue(text.contains("password=***"), text);
            assertFalse(text.contains("geheim"), text);
            assertFalse(text.contains("vorher"), text);
        }
    }
}
