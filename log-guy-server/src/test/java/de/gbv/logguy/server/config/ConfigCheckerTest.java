package de.gbv.logguy.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.config.LogGuyProperties.TokenKind;
import de.gbv.logguy.server.config.LogGuyProperties.TokenProperties;
import de.gbv.logguy.server.security.TokenRegistry;

class ConfigCheckerTest {

    private static LogGuyProperties properties(List<TokenProperties> tokens) {
        return new LogGuyProperties(Path.of("/tmp"), ZoneId.of("Europe/Berlin"),
            Map.of("digibib", new AppProperties(7, "mycore-log4j"), "digibib-apache", new AppProperties(7, null)),
            tokens, null, null, null, null, null);
    }

    @Test
    void validTokens() {
        String hash = TokenRegistry.hash(TokenRegistry.generate(TokenKind.INGEST));
        assertEquals(List.of(), ConfigChecker.check(properties(List.of(
            new TokenProperties("agent-app1", TokenKind.INGEST, "app1", List.of("digibib", "digibib-apache"), null,
                hash)))));
    }

    @Test
    void reportsProblems() {
        String hash = TokenRegistry.hash("x");
        List<String> errors = ConfigChecker.check(properties(List.of(
            new TokenProperties("a", TokenKind.INGEST, "app1", List.of("leopoldina"), null, hash),
            new TokenProperties("a", TokenKind.MCP, null, null, null, "sha256:short"),
            new TokenProperties("b", TokenKind.INGEST, "bad host", List.of(), null, hash))));
        assertTrue(errors.contains("token a: application leopoldina is not configured under log-guy.apps"), errors
            .toString());
        assertTrue(errors.contains("token a: name used twice"), errors.toString());
        assertTrue(errors.contains("token a: hash must look like sha256:<64 hex digits>"), errors.toString());
        assertTrue(errors.contains("token a: mcp token needs a user"), errors.toString());
        assertTrue(errors.contains("token b: hash used twice"), errors.toString());
        assertTrue(errors.contains("token b: ingest token needs a valid host"), errors.toString());
        assertTrue(errors.contains("token b: ingest token needs apps"), errors.toString());
    }
}
