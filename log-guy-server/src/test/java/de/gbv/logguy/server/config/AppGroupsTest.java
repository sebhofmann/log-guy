package de.gbv.logguy.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;

class AppGroupsTest {

    private static final AppGroups GROUPS = AppGroups.of(Map.of(
        "digibib", new AppProperties(null, null, List.of("mir", "produktiv")),
        "rolf", new AppProperties(null, null, List.of("mir")),
        "apache", new AppProperties(null, null)));

    @Test
    void matchesNamesPatternsAndGroups() {
        assertTrue(GROUPS.matches("@mir", "rolf"));
        assertFalse(GROUPS.matches("@produktiv", "rolf"));
        assertFalse(GROUPS.matches("@mir", "apache"));
        assertFalse(GROUPS.matches("@unbekannt", "digibib"));
        assertTrue(GROUPS.matches("digi*", "digibib"));
        assertTrue(GROUPS.matches("*", "apache"));
        assertTrue(GROUPS.matchesAny(List.of("apache", "@produktiv"), "digibib"));
    }

    @Test
    void listsMembersAndNames() {
        assertEquals(Set.of("digibib", "rolf"), GROUPS.members("mir"));
        assertEquals(Set.of(), GROUPS.members("unbekannt"));
        assertEquals(Set.of("mir", "produktiv"), GROUPS.names());
    }

    @Test
    void expandsGroupsAndKeepsThem() {
        assertEquals(List.of("apache", "digi*"), GROUPS.expand(List.of("apache", "digi*")));
        assertEquals(List.of("@mir", "apache", "digibib", "rolf"), GROUPS.expand(List.of("@mir", "apache")));
        // an empty group must not turn a token limit into no limit
        assertEquals(List.of("@leer"), GROUPS.expand(List.of("@leer")));
    }
}
