package de.gbv.logguy.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.config.LogGuyProperties.GrantProperties;
import de.gbv.logguy.server.config.LogGuyProperties.MailTarget;
import de.gbv.logguy.server.config.LogGuyProperties.NotifyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.RetentionProperties;
import de.gbv.logguy.server.config.LogGuyProperties.Right;
import de.gbv.logguy.server.config.LogGuyProperties.TokenKind;
import de.gbv.logguy.server.config.LogGuyProperties.TokenProperties;
import de.gbv.logguy.server.config.LogGuyProperties.UserProperties;
import de.gbv.logguy.server.config.LogGuyProperties.WebhookTarget;
import de.gbv.logguy.server.security.TokenRegistry;

class ConfigCheckerTest {

    private static LogGuyProperties properties(List<TokenProperties> tokens) {
        return properties(tokens, null);
    }

    private static LogGuyProperties properties(List<TokenProperties> tokens, List<UserProperties> users) {
        return new LogGuyProperties(Path.of("/tmp"), ZoneId.of("Europe/Berlin"),
            Map.of("digibib", new AppProperties(7, "mycore-log4j"), "digibib-apache", new AppProperties(7, null)),
            tokens, users, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void users() {
        String hash = "$argon2id$v=19$m=16384,t=2,p=1$c2FsdA$aGFzaA";
        List<GrantProperties> grants = List.of(new GrantProperties(List.of("digi*"), Set.of(Right.READ)));
        String token = TokenRegistry.hash(TokenRegistry.generate(TokenKind.API));
        assertEquals(List.of(), ConfigChecker.check(properties(
            List.of(new TokenProperties("anna-api", TokenKind.API, null, null, "anna", token, false)),
            List.of(new UserProperties("anna", hash, grants)))));
        List<String> errors = ConfigChecker.check(properties(
            List.of(new TokenProperties("ben-api", TokenKind.API, null, null, "ben", token, false)),
            List.of(new UserProperties("anna", "{noop}x", grants), new UserProperties("anna", hash,
                List.of(new GrantProperties(List.of("leopoldina*"), Set.of(Right.READ)))))));
        assertEquals(4, errors.size(), errors.toString());
    }

    @Test
    void groups() {
        String hash = "$argon2id$v=19$m=16384,t=2,p=1$c2FsdA$aGFzaA";
        Map<String, AppProperties> apps = Map.of("digibib", new AppProperties(7, null, List.of("mir", "produktiv")),
            "rolf", new AppProperties(7, null, List.of("bad group")));
        List<UserProperties> users = List.of(new UserProperties("anna", hash, List.of(
            new GrantProperties(List.of("@produktiv"), Set.of(Right.READ)),
            new GrantProperties(List.of("@test"), Set.of(Right.READ)))));
        List<String> errors = ConfigChecker.check(new LogGuyProperties(Path.of("/tmp"), ZoneId.of("Europe/Berlin"),
            apps, null, users, null, null, null, null, null, null, null, null, null, null, null, null, null));
        assertEquals(List.of("application rolf: invalid group bad group",
            "user anna: pattern @test matches no application"), errors);
    }

    @Test
    void validTokens() {
        String hash = TokenRegistry.hash(TokenRegistry.generate(TokenKind.INGEST));
        assertEquals(List.of(), ConfigChecker.check(properties(List.of(
            new TokenProperties("agent-app1", TokenKind.INGEST, "app1", List.of("digibib", "digibib-apache"), null,
                hash, false)))));
    }

    @Test
    void reportsProblems() {
        String hash = TokenRegistry.hash("x");
        List<String> errors = ConfigChecker.check(properties(List.of(
            new TokenProperties("a", TokenKind.INGEST, "app1", List.of("leopoldina"), null, hash, false),
            new TokenProperties("a", TokenKind.MCP, null, null, null, "sha256:short", false),
            new TokenProperties("b", TokenKind.INGEST, "bad host", List.of(), null, hash, false))));
        assertTrue(errors.contains("token a: application leopoldina is not configured under log-guy.apps"), errors
            .toString());
        assertTrue(errors.contains("token a: name used twice"), errors.toString());
        assertTrue(errors.contains("token a: hash must look like sha256:<64 hex digits>"), errors.toString());
        assertTrue(errors.contains("token a: mcp token needs a user"), errors.toString());
        assertTrue(errors.contains("token b: hash used twice"), errors.toString());
        assertTrue(errors.contains("token b: ingest token needs a valid host"), errors.toString());
        assertTrue(errors.contains("token b: ingest token needs apps"), errors.toString());
    }

    @Test
    void tokenMistakesThatBreakOperation() {
        String hash = "$argon2id$v=19$m=16384,t=2,p=1$c2FsdA$aGFzaA";
        List<UserProperties> users = List.of(new UserProperties("anna", hash,
            List.of(new GrantProperties(List.of("digi*"), Set.of(Right.READ)))));
        String ingest = TokenRegistry.hash(TokenRegistry.generate(TokenKind.INGEST));
        String api = TokenRegistry.hash(TokenRegistry.generate(TokenKind.API));
        List<String> errors = ConfigChecker.check(properties(List.of(
            new TokenProperties("no-host", TokenKind.INGEST, null, List.of("digibib"), null, ingest, false),
            new TokenProperties("typo", TokenKind.INGEST, "app2", List.of("digibip"), null, api, false),
            new TokenProperties("twice", TokenKind.API, null, null, "anna", api, false),
            new TokenProperties("anna-api", TokenKind.API, null, List.of("digibib", "leo*"), "anna",
                TokenRegistry.hash(TokenRegistry.generate(TokenKind.API)), false),
            new TokenProperties("anna-mcp", TokenKind.MCP, null, List.of("digi*"), "anna",
                TokenRegistry.hash(TokenRegistry.generate(TokenKind.MCP)), false)),
            users));
        assertEquals(List.of("token no-host: ingest token needs a valid host",
            "token typo: application digibip is not configured under log-guy.apps",
            "token twice: hash used twice",
            "token anna-api: pattern leo* matches no application"), errors);
    }

    @Test
    void retentionCron() {
        for (String cron : List.of("0 30 2 * * *", "-")) {
            assertEquals(List.of(), ConfigChecker.check(retention(cron)), cron);
        }
        assertEquals(List.of("retention: cron 30 2 * * is not a valid cron expression"),
            ConfigChecker.check(retention("30 2 * *")));
    }

    private static LogGuyProperties retention(String cron) {
        return new LogGuyProperties(Path.of("/tmp"), ZoneId.of("Europe/Berlin"), Map.of(), List.of(), List.of(), null,
            null, null, null, null, null, new RetentionProperties(cron, true), null, null, null, null, null, null);
    }

    @Test
    void notifications() {
        assertEquals(List.of(), ConfigChecker.check(notifications(new NotifyProperties("https://log-guy.example.org/",
            "log-guy@example.org", Map.of("it", new MailTarget(List.of("it@example.org"), null)),
            Map.of("chat", new WebhookTarget("https://chat.example.org/hooks/x", null, false)), 5,
            Duration.ofMinutes(1), Duration.ofSeconds(10)))));
        assertEquals(List.of("notifications: base-url log-guy.example.org is not an http or https address",
            "notifications: mail targets need from", "notifications mail it: to is missing",
            "notifications webhook chat: url must be an http or https address"),
            ConfigChecker.check(notifications(new NotifyProperties("log-guy.example.org", null,
                Map.of("it", new MailTarget(null, null)), Map.of("chat", new WebhookTarget("ftp://x", null, null)), 5,
                Duration.ofMinutes(1), Duration.ofSeconds(10)))));
    }

    private static LogGuyProperties notifications(NotifyProperties notifications) {
        return new LogGuyProperties(Path.of("/tmp"), ZoneId.of("Europe/Berlin"), Map.of(), List.of(), List.of(), null,
            null, null, null, null, null, null, null, null, null, null, null, notifications);
    }
}
