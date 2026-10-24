package de.gbv.logguy.server.alert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.gbv.logguy.server.config.LogGuyProperties.GrantProperties;
import de.gbv.logguy.server.config.LogGuyProperties.Right;
import de.gbv.logguy.server.security.LogGuyUser;

class AlertVisibilityTest {

    private static Alert alert(String app) {
        Instant now = Instant.now();
        return new Alert("a", "pool", RuleDefinition.Type.HIT, app, null, Alert.State.OPEN, now, now, now, now, 1,
            "x", null, null, null, null, null, null, 1);
    }

    @Test
    void pseudonymizedReadersSeeTheAlerts() {
        LogGuyUser user = LogGuyUser.of("pseudo", List.of(new GrantProperties(List.of("mir"), Set.of(Right.READ)),
            new GrantProperties(List.of("digibib"), Set.of(Right.READ_PSEUDONYMIZED))));
        assertTrue(AlertService.maySee(user, alert("mir")));
        assertTrue(AlertService.maySee(user, alert("digibib")));
        assertFalse(AlertService.maySee(user, alert("apache")));
        LogGuyUser archiveOnly =
            LogGuyUser.of("max", List.of(new GrantProperties(List.of("digibib"), Set.of(Right.ARCHIVE))));
        assertFalse(AlertService.maySee(archiveOnly, alert("digibib")));
    }
}
