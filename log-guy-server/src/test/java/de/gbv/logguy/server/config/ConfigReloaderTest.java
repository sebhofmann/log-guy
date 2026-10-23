package de.gbv.logguy.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.config.LogGuyProperties.UserProperties;

class ConfigReloaderTest {

    private static LogGuyProperties properties(Map<String, AppProperties> apps, List<UserProperties> users) {
        return new LogGuyProperties(Path.of("/tmp"), ZoneId.of("Europe/Berlin"), apps, List.of(), users, null, null,
            null, null, null, null, null, null);
    }

    @Test
    void usersAreLiveAppsNeedRestart() {
        Map<String, AppProperties> apps = Map.of("digibib", new AppProperties(7, null));
        LogGuyProperties before = properties(apps, List.of());
        assertEquals(List.of(), ConfigReloader.restartNeeded(before,
            properties(apps, List.of(new UserProperties("anna", "x", List.of())))));
        assertEquals(List.of("log-guy.apps"), ConfigReloader.restartNeeded(before,
            properties(Map.of("digibib", new AppProperties(3, null)), List.of())));
    }
}
