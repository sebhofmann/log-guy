package de.gbv.logguy.server.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.gbv.logguy.server.alert.Alert.State;
import de.gbv.logguy.server.alert.RuleDefinition.Type;
import tools.jackson.databind.json.JsonMapper;

class AlertStoreTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Instant T0 = Instant.parse("2026-10-24T10:00:00Z");

    @TempDir
    Path dir;

    private static Alert alert(String id, long count, long seq) {
        return new Alert(id, "pool", Type.HIT, "digibib", null, State.OPEN, T0, T0, T0, T0, count, "pool exhausted",
            "f00:0", "ERROR pool", null, null, null, null, seq);
    }

    /**
     * A backup tool copies {@code state/} while log-guy appends, so the copy may end in half a line. Loading it
     * keeps the complete lines and drops the broken one.
     */
    @Test
    void loadsACopyThatEndsInHalfALine() throws Exception {
        String complete = JSON.writeValueAsString(alert("a1", 1, 1)) + "\n"
            + JSON.writeValueAsString(alert("a2", 1, 2)) + "\n";
        String half = JSON.writeValueAsString(alert("a1", 2, 3));
        Files.writeString(dir.resolve("alerts.ndjson"), complete + half.substring(0, half.length() / 2),
            StandardCharsets.UTF_8);

        try (AlertStore store = new AlertStore(dir, JSON)) {
            List<Alert> alerts = store.load();

            assertEquals(List.of(alert("a1", 1, 1), alert("a2", 1, 2)), alerts);
            store.append(List.of(alert("a2", 2, 4)));
        }
        try (AlertStore store = new AlertStore(dir, JSON)) {
            assertEquals(List.of(alert("a1", 1, 1), alert("a2", 2, 4)), store.load());
        }
    }
}
