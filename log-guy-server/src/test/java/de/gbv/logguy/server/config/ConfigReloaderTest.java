package de.gbv.logguy.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.config.LogGuyProperties.GrantProperties;
import de.gbv.logguy.server.config.LogGuyProperties.Right;
import de.gbv.logguy.server.config.LogGuyProperties.TokenKind;
import de.gbv.logguy.server.config.LogGuyProperties.TokenProperties;
import de.gbv.logguy.server.config.LogGuyProperties.UserProperties;

class ConfigReloaderTest {

    private static LogGuyProperties properties(Map<String, AppProperties> apps, List<UserProperties> users) {
        return properties(apps, List.of(), users);
    }

    private static LogGuyProperties properties(Map<String, AppProperties> apps, List<TokenProperties> tokens,
        List<UserProperties> users) {
        return new LogGuyProperties(Path.of("/tmp"), ZoneId.of("Europe/Berlin"), apps, tokens, users, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static UserProperties user(String name, String app, Right right) {
        return new UserProperties(name, "x", List.of(new GrantProperties(List.of(app), Set.of(right))));
    }

    @Test
    void emptyConfigurationIsRejected() {
        Map<String, AppProperties> apps = Map.of("digibib", new AppProperties(7, null));
        List<TokenProperties> tokens = List.of(new TokenProperties("agent", TokenKind.INGEST, "app1",
            List.of("digibib"), null, "sha256:x", false));
        LogGuyProperties running = properties(apps, tokens, List.of(user("root", "*", Right.ADMIN)));
        List<String> errors = ConfigReloader.plausibility(running, properties(Map.of(), List.of(), List.of()));
        assertEquals(4, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("keine Anwendungen"), errors.toString());
        assertTrue(errors.get(1).contains("keine Nutzer"), errors.toString());
        assertTrue(errors.get(2).contains("keine Tokens"), errors.toString());
        assertTrue(errors.get(3).contains("kein Administrator"), errors.toString());
        assertEquals(List.of(), ConfigReloader.plausibility(running, running));
        // a server that started without users or tokens may get its first ones, or stay without
        LogGuyProperties bare = properties(Map.of(), List.of(), List.of());
        assertEquals(List.of(), ConfigReloader.plausibility(bare, running));
        assertEquals(List.of(), ConfigReloader.plausibility(bare, bare));
    }

    @Test
    void lastAdministratorCannotBeRemoved() {
        Map<String, AppProperties> apps = Map.of("digibib", new AppProperties(7, null));
        LogGuyProperties running = properties(apps, List.of(user("root", "*", Right.ADMIN),
            user("anna", "digibib", Right.READ)));
        List<String> errors = ConfigReloader.plausibility(running, properties(apps, List.of(
            user("root", "digibib", Right.ADMIN), user("anna", "digibib", Right.READ))));
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("kein Administrator"), errors.toString());
        assertEquals(List.of(), ConfigReloader.plausibility(running, properties(apps, List.of(
            user("anna", "*", Right.ADMIN)))));
    }

    @Test
    void appsAndUsersAreLiveOtherSettingsNeedRestart() {
        Map<String, AppProperties> apps = Map.of("digibib", new AppProperties(7, null));
        LogGuyProperties before = properties(apps, List.of());
        assertEquals(List.of(), ConfigReloader.restartNeeded(before,
            properties(apps, List.of(new UserProperties("anna", "x", List.of())))));
        assertEquals(List.of(), ConfigReloader.restartNeeded(before,
            properties(Map.of("digibib", new AppProperties(3, null)), List.of())));
        LogGuyProperties zone = new LogGuyProperties(Path.of("/tmp"), ZoneId.of("UTC"), apps, List.of(), List.of(),
            null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertEquals(List.of("log-guy.zone"), ConfigReloader.restartNeeded(before, zone));
    }

    @Test
    void changedAppsAreListed() {
        Map<String, AppProperties> before = Map.of("digibib", new AppProperties(7, null), "old",
            new AppProperties(7, null));
        Map<String, AppProperties> after = Map.of("digibib", new AppProperties(3, null), "rolf",
            new AppProperties(7, null));
        assertEquals(List.of("~digibib", "-old", "+rolf"), ConfigReloader.changedApps(before, after));
        assertEquals(List.of(), ConfigReloader.changedApps(before, before));
    }
}
