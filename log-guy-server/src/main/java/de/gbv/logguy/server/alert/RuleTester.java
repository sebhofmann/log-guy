package de.gbv.logguy.server.alert;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import de.gbv.logguy.server.alert.AlertEngine.Notification;
import de.gbv.logguy.server.config.AppGroups;
import de.gbv.logguy.server.config.LogGuyProperties.AlertProperties;
import de.gbv.logguy.server.script.ScriptRuntime;

/**
 * Replays past events through a rule, with the times of the events instead of the clock: which events match,
 * which alerts open and which notifications would be sent with the cooldown. Not thread safe, one test per
 * instance.
 */
public final class RuleTester implements AutoCloseable {

    private static final int MAX_ERRORS = 20;

    private final RuleDefinition rule;

    private final AlertProperties settings;

    private final Instant to;

    private final Collection<String> apps;

    private final AppGroups groups;

    private final AlertEngine engine;

    private final AlertRules.Runner runner;

    private final List<String> errorSamples = new ArrayList<>();

    private final List<Hit> hitSamples = new ArrayList<>();

    private final List<Alert> opened = new ArrayList<>();

    private final Map<String, Alert> alerts = new TreeMap<>();

    private final Map<Notification.Kind, Integer> notifications = new TreeMap<>();

    private final List<Sent> sentSamples = new ArrayList<>();

    private Instant nextTick;

    private long events;

    private long hits;

    private long errors;

    /**
     * Starts a test.
     *
     * @param runtime the script runtime
     * @param rule the rule
     * @param settings configuration, for the default cooldown, texts, tick interval and the number of samples
     * @param from start of the replayed range
     * @param to end of the replayed range
     * @param apps the applications of the test, for silence rules
     * @param groups the application groups
     */
    public RuleTester(ScriptRuntime runtime, RuleDefinition rule, AlertProperties settings, Instant from,
        Instant to, Collection<String> apps, AppGroups groups) {
        this.rule = rule;
        this.settings = settings;
        this.to = to;
        this.apps = List.copyOf(apps);
        this.groups = groups;
        this.engine = new AlertEngine(settings.defaultCooldown(), Duration.ofDays(36_500), settings.maxText(), from);
        this.runner = AlertRules.runner(runtime, List.of(rule), groups, (failed, error) -> {
            errors++;
            if (errorSamples.size() < MAX_ERRORS) {
                errorSamples.add(error);
            }
        });
        this.nextTick = from.plus(settings.tickInterval());
    }

    /**
     * Replays one event. Events have to come oldest first.
     *
     * @param app the application
     * @param id id of the event
     * @param ts time of the event
     * @param event the event as {@code check(event)} sees it
     */
    public void accept(String app, String id, Instant ts, Map<String, Object> event) {
        tickUntil(ts);
        events++;
        engine.seen(app, ts);
        if (rule.type() == RuleDefinition.Type.ABSENCE) {
            return;
        }
        if (!runner.check(app, event).isEmpty()) {
            hits++;
            if (hitSamples.size() < settings.testSamples()) {
                Object message = event.get("message");
                String text = message == null ? "" : message.toString();
                int newline = text.indexOf('\n');
                text = newline < 0 ? text : text.substring(0, newline);
                hitSamples.add(new Hit(id, ts, app,
                    text.length() > settings.maxText() ? text.substring(0, settings.maxText()) + " …" : text));
            }
            engine.match(rule, app, id, ts, event, ts, runner::message);
            collect();
        }
    }

    private void tickUntil(Instant ts) {
        // every interval, as live: jumping over quiet stretches would delay summaries and silence
        while (!nextTick.isAfter(ts)) {
            engine.tick(nextTick, List.of(rule), apps, groups, runner::message);
            collect();
            nextTick = nextTick.plus(settings.tickInterval());
        }
    }

    private void collect() {
        AlertEngine.Changes changes = engine.drain();
        for (Alert alert : changes.alerts()) {
            if (!alerts.containsKey(alert.id()) && opened.size() < settings.testSamples()) {
                opened.add(alert);
            }
            alerts.put(alert.id(), alert);
        }
        for (Notification notification : changes.notifications()) {
            notifications.merge(notification.kind(), 1, Integer::sum);
            if (sentSamples.size() < settings.testSamples()) {
                sentSamples.add(new Sent(notification.alert().updated(), notification.kind(),
                    notification.alert().app(), notification.count(), notification.text()));
            }
        }
    }

    /**
     * Finishes the test at the end of the range.
     *
     * @param source description of the replayed events
     * @param truncated whether the events were cut at the limit
     * @return the result
     */
    public RuleTestResult finish(String source, boolean truncated) {
        tickUntil(to);
        List<Alert> samples = opened.stream().map(alert -> alerts.get(alert.id())).toList();
        return new RuleTestResult(source, rule.label(), rule.type(), apps, events, truncated, hits, alerts.size(),
            Map.copyOf(notifications), errors, List.copyOf(errorSamples), List.copyOf(hitSamples), samples,
            List.copyOf(sentSamples));
    }

    @Override
    public void close() {
        runner.close();
    }

    /**
     * An event that matched.
     *
     * @param id id of the event
     * @param ts time
     * @param app the application
     * @param line first line of the message
     */
    public record Hit(String id, Instant ts, String app, String line) {
    }

    /**
     * A notification that would have been sent.
     *
     * @param at when
     * @param kind opened, summary or resolved
     * @param app the application
     * @param count hits it is about
     * @param text the text
     */
    public record Sent(Instant at, Notification.Kind kind, String app, long count, String text) {
    }

    /**
     * The result of a rule test.
     *
     * @param source description of the replayed events
     * @param rule name and version of the rule
     * @param type kind of the rule
     * @param apps applications of the test
     * @param events replayed events
     * @param truncated whether the events were cut at the limit
     * @param hits events that matched
     * @param alerts alerts that would have opened
     * @param notifications notifications by kind
     * @param errors failed calls of the rule
     * @param errorSamples the first errors
     * @param hitSamples the first matching events
     * @param alertSamples the first alerts in their last state
     * @param sentSamples the first notifications
     */
    public record RuleTestResult(String source, String rule, RuleDefinition.Type type, Collection<String> apps,
        long events, boolean truncated, long hits, int alerts, Map<Notification.Kind, Integer> notifications,
        long errors, List<String> errorSamples, List<Hit> hitSamples, List<Alert> alertSamples,
        List<Sent> sentSamples) {
    }
}
