package de.gbv.logguy.server.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.gbv.logguy.server.alert.Alert.State;
import de.gbv.logguy.server.alert.AlertEngine.Notification.Kind;
import de.gbv.logguy.server.alert.RuleDefinition.Type;
import de.gbv.logguy.server.config.AppGroups;

class AlertEngineTest {

    private static final Instant T0 = Instant.parse("2026-10-24T10:00:00Z");

    private static final AlertEngine.Messages DEFAULT = (rule, event, count) -> null;

    private static AlertEngine engine() {
        return new AlertEngine(Duration.ofMinutes(15), Duration.ofDays(30), 500, T0);
    }

    private static RuleDefinition hit() {
        return new RuleDefinition("pool", "", "v1", List.of("digibib"), Type.HIT, Duration.ofMinutes(10),
            List.of("mail:it"), 0, null, null, null, false);
    }

    private static RuleDefinition threshold(String per) {
        return new RuleDefinition("fehler", "", "v1", List.of("*"), Type.THRESHOLD, null, List.of(), 3,
            Duration.ofMinutes(5), per, null, false);
    }

    private static RuleDefinition absence() {
        return new RuleDefinition("still", "", "v1", List.of("digi*"), Type.ABSENCE, null, List.of(), 0, null, null,
            Duration.ofMinutes(30), false);
    }

    private static Map<String, Object> event(String message) {
        return Map.of("app", "digibib", "message", message + "\n\tat x.y", "user", "alice");
    }

    @Test
    void hitOpensOneAlertAndSummarizesAfterTheCooldown() {
        AlertEngine engine = engine();
        RuleDefinition rule = hit();
        engine.match(rule, "digibib", "e1", T0, event("Pool voll"), T0, DEFAULT);
        engine.match(rule, "digibib", "e2", T0.plusSeconds(10), event("Pool voll"), T0.plusSeconds(10), DEFAULT);
        engine.match(rule, "digibib", "e3", T0.plusSeconds(20), event("Pool voll"), T0.plusSeconds(20), DEFAULT);
        AlertEngine.Changes changes = engine.drain();
        assertEquals(1, engine.alerts().size());
        Alert alert = engine.alerts().getFirst();
        assertEquals(3, alert.count());
        assertEquals("Pool voll", alert.message());
        assertEquals("e1", alert.eventId());
        assertEquals(List.of(Kind.OPENED), changes.notifications().stream().map(n -> n.kind()).toList());
        assertEquals(List.of("mail:it"), changes.notifications().getFirst().targets());

        engine.tick(T0.plus(Duration.ofMinutes(5)), List.of(rule), List.of("digibib"), AppGroups.NONE, DEFAULT);
        assertEquals(List.of(), engine.drain().notifications());
        engine.tick(T0.plus(Duration.ofMinutes(10)), List.of(rule), List.of("digibib"), AppGroups.NONE, DEFAULT);
        List<AlertEngine.Notification> summary = engine.drain().notifications();
        assertEquals(1, summary.size());
        assertEquals(Kind.SUMMARY, summary.getFirst().kind());
        assertEquals(2, summary.getFirst().count());
        assertEquals("2 weitere Treffer", summary.getFirst().text());
    }

    @Test
    void hitAfterAckOpensANewAlertButRespectsTheCooldown() {
        AlertEngine engine = engine();
        RuleDefinition rule = hit();
        engine.match(rule, "digibib", "e1", T0, event("a"), T0, DEFAULT);
        String first = engine.alerts().getFirst().id();
        assertEquals(State.ACKED, engine.ack(first, "admin", T0.plusSeconds(5)).state());
        engine.match(rule, "digibib", "e2", T0.plusSeconds(60), event("b"), T0.plusSeconds(60), DEFAULT);
        assertEquals(2, engine.alerts().size());
        Alert second = engine.alerts().get(1);
        assertEquals(State.OPEN, second.state());
        assertNull(second.notified());
        assertEquals(1, engine.drain().notifications().size());
        engine.tick(T0.plus(Duration.ofMinutes(10)), List.of(rule), List.of(), AppGroups.NONE, DEFAULT);
        List<AlertEngine.Notification> later = engine.drain().notifications();
        assertEquals(1, later.size());
        assertEquals(second.id(), later.getFirst().alert().id());
        assertEquals(AlertEngine.Notification.Kind.OPENED, later.getFirst().kind());
        assertEquals(second.message(), later.getFirst().text());
    }

    @Test
    void thresholdOpensAtTheLimitAndResolvesBelowIt() {
        AlertEngine engine = engine();
        RuleDefinition rule = threshold(null);
        for (int i = 0; i < 2; i++) {
            engine.match(rule, "digibib", "e" + i, T0.plusSeconds(i), event("x"), T0.plusSeconds(i), DEFAULT);
        }
        assertEquals(0, engine.alerts().size());
        engine.match(rule, "digibib", "e2", T0.plusSeconds(2), event("x"), T0.plusSeconds(2), DEFAULT);
        Alert alert = engine.alerts().getFirst();
        assertEquals("3 Treffer in 5m", alert.message());
        assertEquals(T0, alert.firstTs());
        engine.ack(alert.id(), "admin", T0.plusSeconds(3));
        engine.tick(T0.plus(Duration.ofMinutes(4)), List.of(rule), List.of(), AppGroups.NONE, DEFAULT);
        assertEquals(State.ACKED, engine.alert(alert.id()).state());
        engine.tick(T0.plus(Duration.ofMinutes(6)), List.of(rule), List.of(), AppGroups.NONE, DEFAULT);
        assertEquals(State.RESOLVED, engine.alert(alert.id()).state());
        assertEquals(Kind.RESOLVED, engine.drain().notifications().getLast().kind());
    }

    @Test
    void thresholdCountsPerGroup() {
        AlertEngine engine = engine();
        RuleDefinition rule = threshold("user");
        for (int i = 0; i < 3; i++) {
            engine.match(rule, "digibib", "a" + i, T0, Map.of("message", "x", "user", "alice"), T0, DEFAULT);
            if (i < 2) {
                engine.match(rule, "digibib", "b" + i, T0, Map.of("message", "x", "user", "bob"), T0, DEFAULT);
            }
        }
        assertEquals(1, engine.alerts().size());
        assertEquals("alice", engine.alerts().getFirst().group());
    }

    @Test
    void silenceOpensAndResolves() {
        AlertEngine engine = engine();
        RuleDefinition rule = absence();
        List<String> apps = List.of("digibib", "mir");
        engine.tick(T0.plus(Duration.ofMinutes(29)), List.of(rule), apps, AppGroups.NONE, DEFAULT);
        assertEquals(0, engine.alerts().size());
        engine.tick(T0.plus(Duration.ofMinutes(30)), List.of(rule), apps, AppGroups.NONE, DEFAULT);
        assertEquals(1, engine.alerts().size());
        Alert alert = engine.alerts().getFirst();
        assertEquals("digibib", alert.app());
        assertEquals("Seit mindestens 30m kein Event", alert.message());
        engine.seen("digibib", T0.plus(Duration.ofMinutes(31)));
        engine.tick(T0.plus(Duration.ofMinutes(32)), List.of(rule), apps, AppGroups.NONE, DEFAULT);
        assertEquals(State.RESOLVED, engine.alert(alert.id()).state());
    }

    @Test
    void removedRulesResolveTheirThresholdAndSilenceAlerts() {
        AlertEngine engine = engine();
        engine.tick(T0.plus(Duration.ofHours(1)), List.of(absence()), List.of("digibib"), AppGroups.NONE, DEFAULT);
        engine.match(hit(), "digibib", "e1", T0, event("a"), T0, DEFAULT);
        engine.tick(T0.plus(Duration.ofHours(2)), List.of(), List.of("digibib"), AppGroups.NONE, DEFAULT);
        assertEquals(List.of(State.RESOLVED, State.OPEN), engine.alerts().stream().map(Alert::state).toList());
    }

    @Test
    void restoredAlertsKeepCounting() {
        AlertEngine engine = engine();
        engine.match(hit(), "digibib", "e1", T0, event("a"), T0, DEFAULT);
        AlertEngine restored = engine();
        restored.restore(engine.alerts());
        restored.match(hit(), "digibib", "e2", T0.plusSeconds(1), event("a"), T0.plusSeconds(1), DEFAULT);
        assertEquals(1, restored.alerts().size());
        assertEquals(2, restored.alerts().getFirst().count());
        assertEquals(List.of(), restored.drain().notifications());
    }

    @Test
    void ruleTextsReplaceTheDefaults() {
        AlertEngine engine = engine();
        engine.match(hit(), "digibib", "e1", T0, event("a"), T0, (rule, event, count) -> event.get("app") + ": "
            + count);
        assertEquals("digibib: 1", engine.alerts().getFirst().message());
    }
}
