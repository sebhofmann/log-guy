package de.gbv.logguy.server.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.common.TestLogs;
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
import io.modelcontextprotocol.spec.McpSchema.Tool;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpEndpointTest {

    private static final String MCP_TOKEN = "lg_mcp_test";

    private static final String ADMIN_MCP_TOKEN = "lg_mcp_admin";

    private static final String API_TOKEN = "lg_api_test";

    private static final byte[] SAMPLE = TestLogs.mycoreSample();

    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b");

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String hash = SecurityConfig.passwordEncoder().encode("geheim-genug");
        registry.add("log-guy.data-dir", dataDir::toString);
        registry.add("log-guy.scripts.dir", () -> dataDir.resolve("scripts").toString());
        registry.add("log-guy.indexer.enabled", () -> "false");
        registry.add("log-guy.apps.digibib.parser", () -> "mycore-log4j");
        registry.add("log-guy.apps.other.parser", () -> "mycore-log4j");
        registry.add("log-guy.users[0].name", () -> "anna");
        registry.add("log-guy.users[0].password-hash", () -> hash);
        registry.add("log-guy.users[0].grants[0].apps", () -> "digi*");
        registry.add("log-guy.users[0].grants[0].rights", () -> "read_pseudonymized");
        registry.add("log-guy.users[1].name", () -> "root");
        registry.add("log-guy.users[1].password-hash", () -> hash);
        registry.add("log-guy.users[1].grants[0].apps", () -> "*");
        registry.add("log-guy.users[1].grants[0].rights", () -> "admin");
        registry.add("log-guy.tokens[0].name", () -> "anna-mcp");
        registry.add("log-guy.tokens[0].kind", () -> "mcp");
        registry.add("log-guy.tokens[0].user", () -> "anna");
        registry.add("log-guy.tokens[0].hash", () -> TokenRegistry.hash(MCP_TOKEN));
        registry.add("log-guy.tokens[1].name", () -> "root-mcp");
        registry.add("log-guy.tokens[1].kind", () -> "mcp");
        registry.add("log-guy.tokens[1].user", () -> "root");
        registry.add("log-guy.tokens[1].hash", () -> TokenRegistry.hash(ADMIN_MCP_TOKEN));
        registry.add("log-guy.tokens[2].name", () -> "root-api");
        registry.add("log-guy.tokens[2].kind", () -> "api");
        registry.add("log-guy.tokens[2].user", () -> "root");
        registry.add("log-guy.tokens[2].hash", () -> TokenRegistry.hash(API_TOKEN));
    }

    @LocalServerPort
    int port;

    @Autowired
    HotStore hot;

    private String id;

    @BeforeEach
    void storeSample() throws Exception {
        String fingerprint = Fingerprint.of(SAMPLE);
        id = fingerprint + ":0";
        if (hot.find(fingerprint).isEmpty()) {
            HotFile file = hot.getOrCreate(fingerprint, "app1", "digibib");
            file.append(0, SAMPLE, SAMPLE.length, null, Instant.now());
            file.close(SAMPLE.length, null, Instant.now());
        }
    }

    private McpSyncClient client(String token) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
            .builder("http://localhost:" + port)
            .endpoint("/mcp")
            .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
            .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
        client.initialize();
        return client;
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    private int post(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    @Test
    void onlyMcpTokensReachTheEndpoint() throws Exception {
        assertEquals(401, post("/mcp", API_TOKEN));
        assertEquals(401, post("/mcp", "lg_mcp_wrong"));
        assertEquals(401, post("/api/v1/fields", MCP_TOKEN));
        assertThrows(RuntimeException.class, () -> client(API_TOKEN));
    }

    @Test
    void listsToolsAndApplications() {
        try (McpSyncClient client = client(MCP_TOKEN)) {
            List<String> names = client.listTools().tools().stream().map(Tool::name).toList();
            assertTrue(names.containsAll(List.of("list_applications", "search_logs", "get_histogram",
                "get_top_values", "get_context", "get_status", "get_server_log", "list_scripts", "get_script",
                "save_draft", "test_script", "activate_script", "rollback_script")), names.toString());
            CallToolResult apps = client.callTool(new CallToolRequest("list_applications", Map.of()));
            assertFalse(Boolean.TRUE.equals(apps.isError()), text(apps));
            assertTrue(text(apps).contains("\"digibib\""), text(apps));
            assertFalse(text(apps).contains("\"other\""), text(apps));
        }
    }

    @Test
    void contextIsPseudonymizedPerSession() {
        String first;
        try (McpSyncClient client = client(MCP_TOKEN)) {
            CallToolResult context = client.callTool(new CallToolRequest("get_context",
                Map.of("id", id, "before", 0, "after", 200)));
            first = text(context);
            assertFalse(Boolean.TRUE.equals(context.isError()), first);
            assertTrue(first.contains("$IP1"), first);
            assertFalse(IPV4.matcher(first).find(), first);
            assertTrue(new String(SAMPLE).contains("ip=66.249.64.0"));
        }
        try (McpSyncClient client = client(MCP_TOKEN)) {
            String second = text(client.callTool(new CallToolRequest("get_context",
                Map.of("id", id, "before", 0, "after", 200))));
            assertEquals(first, second, "a new session numbers again from 1");
        }
    }

    @Test
    void protectedFieldsOnlyAcceptPseudonyms() {
        try (McpSyncClient client = client(MCP_TOKEN)) {
            CallToolResult result = client.callTool(new CallToolRequest("search_logs",
                Map.of("query", "login:mueller")));
            assertTrue(result.isError());
            assertTrue(text(result).contains("Pseudonyme"), text(result));
            result = client.callTool(new CallToolRequest("search_logs",
                Map.of("filters", Map.of("login", List.of("$Nutzer9")))));
            assertTrue(result.isError(), text(result));
        }
    }

    @Test
    void adminToolsNeedAdmin() {
        try (McpSyncClient client = client(MCP_TOKEN)) {
            assertTrue(client.callTool(new CallToolRequest("get_server_log", Map.of())).isError());
            CallToolResult status = client.callTool(new CallToolRequest("get_status", Map.of()));
            assertFalse(text(status).contains("\"system\""), text(status));
        }
        try (McpSyncClient client = client(ADMIN_MCP_TOKEN)) {
            CallToolResult log = client.callTool(new CallToolRequest("get_server_log", Map.of("level", "INFO")));
            assertFalse(Boolean.TRUE.equals(log.isError()), text(log));
            assertFalse(IPV4.matcher(text(log)).find(), text(log));
        }
    }

    @Test
    void parserTestOutputIsPseudonymized() throws Exception {
        try (McpSyncClient client = client(ADMIN_MCP_TOKEN)) {
            CallToolResult result = client.callTool(new CallToolRequest("test_script", Map.of("kind", "parser",
                "name", "mycore-log4j", "app", "digibib", "text", new String(SAMPLE).lines().limit(50)
                    .reduce((a, b) -> a + "\n" + b).orElseThrow())));
            assertFalse(Boolean.TRUE.equals(result.isError()), text(result));
            assertFalse(IPV4.matcher(text(result)).find(), text(result));
            assertTrue(text(result).contains("\"events\""), text(result));
        }
    }

    @Test
    void draftsAreSavedButNotActivatedWithoutPermission() throws Exception {
        String code = Files.readString(Path.of("src/main/resources/templates/parser/mycore-log4j.js"));
        try (McpSyncClient client = client(ADMIN_MCP_TOKEN)) {
            CallToolResult draft = client.callTool(new CallToolRequest("save_draft", Map.of("kind", "parser",
                "name", "mcp-test", "code", code)));
            assertFalse(Boolean.TRUE.equals(draft.isError()), text(draft));
            CallToolResult activate = client.callTool(new CallToolRequest("activate_script", Map.of("kind",
                "parser", "name", "mcp-test")));
            assertTrue(activate.isError(), text(activate));
        }
        assertTrue(Files.readString(dataDir.resolve("audit").toFile().listFiles()[0].toPath())
            .contains("mcp.save_draft"));
    }
}
