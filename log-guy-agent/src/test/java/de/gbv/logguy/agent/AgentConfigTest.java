package de.gbv.logguy.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentConfigTest {

    @TempDir
    Path dir;

    @Test
    void readsEverythingFromTheEnvironment() throws IOException {
        AgentConfig config = AgentConfig.load(dir.resolve("missing.yml"), Map.of(
            "LOG_GUY_SERVER", "http://log-guy:8080/",
            "LOG_GUY_TOKEN", "lg_ingest_abc",
            "LOG_GUY_HOST", "mir",
            "LOG_GUY_SEND_INTERVAL", "PT2S",
            "LOG_GUY_SOURCES_1_APP", "apache",
            "LOG_GUY_SOURCES_1_ROTATED", "/logs/apache/*.gz",
            "LOG_GUY_SOURCES_0_APP", "mir",
            "LOG_GUY_SOURCES_0_LIVE", "/logs/mir/mycore-app.log",
            "LOG_GUY_SOURCES_0_ROTATED", "/logs/mir/*/mycore-app-*.log.gz",
            "PATH", "/usr/bin"), Map.of());

        assertEquals(URI.create("http://log-guy:8080"), config.server());
        assertEquals("lg_ingest_abc", config.token());
        assertEquals("mir", config.host());
        assertEquals(Duration.ofSeconds(2), config.sendInterval());
        assertEquals(Duration.ofSeconds(60), config.rotatedScanInterval());
        assertEquals(List.of(
            new AgentConfig.Source("mir", Path.of("/logs/mir/mycore-app.log"), "/logs/mir/*/mycore-app-*.log.gz"),
            new AgentConfig.Source("apache", null, "/logs/apache/*.gz")), config.sources());
    }

    @Test
    void environmentReplacesTheFileAndOverridesReplaceTheEnvironment() throws IOException {
        Path tokenFile = Files.writeString(dir.resolve("token"), "lg_ingest_file\n");
        Path file = Files.writeString(dir.resolve("agent.yml"), """
            server: http://file:8080
            token-file: %s
            host: file
            sources:
              - app: digibib
                live: /logs/digibib/mycore-app.log
            """.formatted(tokenFile));

        AgentConfig fileOnly = AgentConfig.load(file, Map.of("LOG_GUY_HOST", ""), Map.of());
        assertEquals("lg_ingest_file", fileOnly.token());
        assertEquals("file", fileOnly.host());
        assertEquals("digibib", fileOnly.sources().getFirst().app());

        AgentConfig config = AgentConfig.load(file, Map.of("LOG_GUY_SERVER", "http://env:8080",
            "LOG_GUY_HOST", "env", "LOG_GUY_SOURCES_0_APP", "mir"), Map.of("host", "cli"));
        assertEquals(URI.create("http://env:8080"), config.server());
        assertEquals("cli", config.host());
        assertEquals(List.of(new AgentConfig.Source("mir", null, null)), config.sources());
        assertNull(config.sources().getFirst().live());
    }
}
