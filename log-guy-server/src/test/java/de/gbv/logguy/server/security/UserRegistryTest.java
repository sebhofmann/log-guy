package de.gbv.logguy.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import de.gbv.logguy.server.config.AppGroups;
import de.gbv.logguy.server.config.LogGuyProperties.GrantProperties;
import de.gbv.logguy.server.config.LogGuyProperties.Right;
import de.gbv.logguy.server.config.LogGuyProperties.UserProperties;

class UserRegistryTest {

    private static final List<String> APPS = List.of("apache", "digibib", "rolf");

    private final AtomicReference<AppGroups> groups = new AtomicReference<>(
        new AppGroups(Map.of("digibib", Set.of("produktiv"))));

    private final UserRegistry registry = new UserRegistry(List.of(new UserProperties("anna", null,
        List.of(new GrantProperties(List.of("@produktiv"), Set.of(Right.READ))))), groups::get);

    @Test
    void grantsFollowTheGroups() {
        assertEquals(Set.of("digibib"), registry.find("anna", null).orElseThrow().readableApps(APPS));
        groups.set(new AppGroups(Map.of("digibib", Set.of("produktiv"), "rolf", Set.of("produktiv"))));
        assertEquals(Set.of("digibib", "rolf"), registry.find("anna", null).orElseThrow().readableApps(APPS));
    }

    @Test
    void tokenLimitedToAnEmptyGroupReadsNothing() {
        CurrentUser.ApiTokenDetails token = new CurrentUser.ApiTokenDetails("anna-api", List.of("@leer"), false,
            false);
        assertEquals(Set.of(), registry.find("anna", token).orElseThrow().readableApps(APPS));
        CurrentUser.ApiTokenDetails produktiv = new CurrentUser.ApiTokenDetails("anna-api", List.of("@produktiv"),
            false, false);
        assertEquals(Set.of("digibib"), registry.find("anna", produktiv).orElseThrow().readableApps(APPS));
    }
}
