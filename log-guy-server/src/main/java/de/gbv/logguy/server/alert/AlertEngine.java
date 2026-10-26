package de.gbv.logguy.server.alert;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

import de.gbv.logguy.server.alert.Alert.State;
import de.gbv.logguy.server.alert.RuleDefinition.Type;
import de.gbv.logguy.server.config.AppGroups;

/**
 * Counters, timers and states of the alerts, without I/O. The time is passed in, so the same logic runs live with
 * the clock and in a rule test with the times of past events. Thread safe.
 */
public final class AlertEngine {

    private final Duration defaultCooldown;

    private final Duration keep;

    private final int maxText;

    private final Instant started;

    private final Map<String, Alert> alerts = new LinkedHashMap<>();

    private final Map<Key, String> active = new HashMap<>();

    private final Map<Key, Window> windows = new HashMap<>();

    private final Map<Key, Pending> pending = new HashMap<>();

    private final Map<String, Instant> lastSeen = new HashMap<>();

    private final List<Alert> changed = new ArrayList<>();

    private final List<Notification> notifications = new ArrayList<>();

    private long seq;

    /**
     * Creates an engine without alerts.
     *
     * @param defaultCooldown cooldown of rules without {@code meta.cooldown}
     * @param keep acknowledged and resolved alerts are dropped after this time without change
     * @param maxText characters of the event line and the message at most
     * @param started applications without events count as silent from here on
     */
    public AlertEngine(Duration defaultCooldown, Duration keep, int maxText, Instant started) {
        this.defaultCooldown = defaultCooldown;
        this.keep = keep;
        this.maxText = maxText;
        this.started = started;
    }

    /**
     * Texts of the rules, from {@code message(event, count)} or {@code null} for the default text.
     */
    @FunctionalInterface
    public interface Messages {

        /**
         * The text of an alert or a summary.
         *
         * @param rule the rule
         * @param event the event, for silence only {@code app} and {@code lastSeen}
         * @param count the count
         * @return the text, {@code null} for the default
         */
        String text(RuleDefinition rule, Map<String, Object> event, long count);
    }

    /**
     * Something to send to the targets of a rule.
     *
     * @param alert the alert
     * @param kind opened, summary of further hits, or resolved
     * @param count hits the notification is about
     * @param text the text
     * @param targets {@code meta.notify} of the rule
     */
    public record Notification(Alert alert, Kind kind, long count, String text, List<String> targets) {

        /**
         * Kind of a notification.
         */
        public enum Kind {
            /** A new alert. */
            OPENED,
            /** Further hits after the cooldown. */
            SUMMARY,
            /** Back below the limit. */
            RESOLVED
        }
    }

    /**
     * What changed since the last {@link #drain()}.
     *
     * @param alerts the changed alerts in their new state
     * @param notifications the notifications to send
     */
    public record Changes(List<Alert> alerts, List<Notification> notifications) {
    }

    private record Key(String rule, String app, String group) {
    }

    private static final class Pending {

        private Instant lastSent;

        private long count;

        private Map<String, Object> event;

        private String alertId;
    }

    /**
     * Counts per second of the matching events of a threshold.
     */
    private static final class Window {

        private final TreeMap<Long, long[]> seconds = new TreeMap<>();

        private long total;

        void add(Instant ts) {
            seconds.computeIfAbsent(ts.getEpochSecond(), s -> new long[1])[0]++;
            total++;
        }

        long count(Instant now, Duration within) {
            long lower = now.getEpochSecond() - within.toSeconds();
            Iterator<Map.Entry<Long, long[]>> head = seconds.headMap(lower, true).entrySet().iterator();
            while (head.hasNext()) {
                total -= head.next().getValue()[0];
                head.remove();
            }
            return total;
        }

        Instant first() {
            return seconds.isEmpty() ? null : Instant.ofEpochSecond(seconds.firstKey());
        }
    }

    /**
     * Takes over alerts read from {@code state/alerts.ndjson}.
     *
     * @param restored the alerts
     */
    public synchronized void restore(Collection<Alert> restored) {
        for (Alert alert : restored) {
            alerts.put(alert.id(), alert);
            seq = Math.max(seq, alert.seq());
            if (alert.active()) {
                Key key = new Key(alert.rule(), alert.app(), alert.group());
                active.put(key, alert.id());
                Pending state = pending.computeIfAbsent(key, k -> new Pending());
                state.lastSent = alert.notified();
                state.alertId = alert.id();
            }
        }
    }

    /**
     * Notes an event of an application for silence rules.
     *
     * @param app the application
     * @param ts time of the event
     */
    public synchronized void seen(String app, Instant ts) {
        lastSeen.merge(app, ts, (a, b) -> a.isAfter(b) ? a : b);
    }

    /**
     * Counts an event that matched a hit or threshold rule.
     *
     * @param rule the rule
     * @param app the application
     * @param eventId id of the event in the index
     * @param ts time of the event
     * @param event the event as the rule saw it
     * @param now the current time
     * @param messages texts of the rule
     */
    public synchronized void match(RuleDefinition rule, String app, String eventId, Instant ts,
        Map<String, Object> event, Instant now, Messages messages) {
        String group = null;
        if (rule.type() == Type.THRESHOLD && rule.per() != null) {
            Object value = event.get(rule.per());
            group = value == null ? "" : String.valueOf(value);
        }
        Key key = new Key(rule.name(), app, group);
        Alert current = current(key);
        if (rule.type() == Type.THRESHOLD) {
            Window window = windows.computeIfAbsent(key, k -> new Window());
            window.add(ts);
            long count = window.count(now, rule.within());
            if (current == null && count >= rule.count()) {
                String text = text(messages.text(rule, event, count), count + " Treffer in "
                    + RuleDefinition.format(rule.within()));
                open(key, rule, new Alert(UUID.randomUUID().toString(), rule.name(), rule.type(), app, group,
                    State.OPEN, now, now, window.first(), ts, count, text, eventId, line(event), null, null, null,
                    null, ++seq), event, now);
                return;
            }
        } else if (rule.type() == Type.HIT && current == null) {
            String text = text(messages.text(rule, event, 1), line(event));
            open(key, rule, new Alert(UUID.randomUUID().toString(), rule.name(), rule.type(), app, null, State.OPEN,
                now, now, ts, ts, 1, text, eventId, line(event), null, null, null, null, ++seq), event, now);
            return;
        }
        if (current != null) {
            put(current.counted(ts, now, ++seq));
            Pending state = pending.computeIfAbsent(key, k -> new Pending());
            state.count++;
            state.event = event;
            state.alertId = current.id();
        }
    }

    private Alert current(Key key) {
        String id = active.get(key);
        Alert alert = id == null ? null : alerts.get(id);
        if (alert != null && !alert.active()) {
            active.remove(key);
            return null;
        }
        return alert;
    }

    private void open(Key key, RuleDefinition rule, Alert alert, Map<String, Object> event, Instant now) {
        active.put(key, alert.id());
        Pending state = pending.computeIfAbsent(key, k -> new Pending());
        Duration cooldown = rule.cooldown() != null ? rule.cooldown() : defaultCooldown;
        Alert stored = alert;
        if (state.lastSent == null || !now.isBefore(state.lastSent.plus(cooldown))) {
            stored = alert.withNotified(now, alert.seq());
            notifications.add(new Notification(stored, Notification.Kind.OPENED, alert.count(), alert.message(),
                rule.targets()));
            state.lastSent = now;
        } else {
            state.count += alert.count();
            state.event = event;
        }
        state.alertId = alert.id();
        put(stored);
    }

    /**
     * Evaluates thresholds, silence and cooldowns. Called regularly, live every few seconds.
     *
     * @param now the current time
     * @param rules the active rules
     * @param apps the configured applications, for silence rules
     * @param groups the application groups, for silence rules
     * @param messages texts of the rules
     */
    public synchronized void tick(Instant now, List<RuleDefinition> rules, Collection<String> apps,
        AppGroups groups, Messages messages) {
        Map<String, RuleDefinition> byName = new HashMap<>();
        rules.forEach(rule -> byName.put(rule.name(), rule));
        for (Map.Entry<Key, String> entry : new ArrayList<>(active.entrySet())) {
            Alert alert = alerts.get(entry.getValue());
            RuleDefinition rule = byName.get(entry.getKey().rule());
            if (alert == null || !alert.active()) {
                active.remove(entry.getKey());
            } else if (rule == null || rule.type() != alert.type()) {
                if (alert.type() != Type.HIT) {
                    resolve(entry.getKey(), alert, rule, now);
                }
            } else if (alert.type() == Type.THRESHOLD) {
                Window window = windows.get(entry.getKey());
                if (window == null || window.count(now, rule.within()) < rule.count()) {
                    resolve(entry.getKey(), alert, rule, now);
                }
            }
        }
        windows.entrySet().removeIf(entry -> {
            RuleDefinition rule = byName.get(entry.getKey().rule());
            return rule == null || rule.type() != Type.THRESHOLD || entry.getValue().count(now, rule.within()) == 0;
        });
        for (RuleDefinition rule : rules) {
            if (rule.type() != Type.ABSENCE) {
                continue;
            }
            for (String app : apps) {
                if (!rule.appliesTo(app, groups)) {
                    continue;
                }
                Key key = new Key(rule.name(), app, null);
                Instant last = lastSeen.getOrDefault(app, started);
                boolean silent = !now.isBefore(last.plus(rule.absentFor()));
                Alert current = current(key);
                if (silent && current == null) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("app", app);
                    event.put("lastSeen", last.toString());
                    String text = text(messages.text(rule, event, 0), "Seit mindestens "
                        + RuleDefinition.format(rule.absentFor()) + " kein Event");
                    open(key, rule, new Alert(UUID.randomUUID().toString(), rule.name(), rule.type(), app, null,
                        State.OPEN, now, now, last, last, 0, text, null, null, null, null, null, null, ++seq), event,
                        now);
                } else if (!silent && current != null) {
                    resolve(key, current, rule, now);
                }
            }
        }
        for (Iterator<Map.Entry<Key, Pending>> it = pending.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Key, Pending> entry = it.next();
            Pending state = entry.getValue();
            RuleDefinition rule = byName.get(entry.getKey().rule());
            Duration cooldown = rule == null || rule.cooldown() == null ? defaultCooldown : rule.cooldown();
            boolean over = state.lastSent == null || !now.isBefore(state.lastSent.plus(cooldown));
            if (state.count > 0 && over && rule != null) {
                Alert alert = alerts.get(state.alertId);
                if (alert != null && alert.notified() == null) {
                    // opened during the cooldown of an earlier alert, its receivers have not heard of it yet
                    Alert notified = alert.withNotified(now, ++seq);
                    put(notified);
                    notifications.add(new Notification(notified, Notification.Kind.OPENED, alert.count(),
                        alert.message(), rule.targets()));
                } else if (alert != null) {
                    String text = text(messages.text(rule, state.event, state.count), state.count
                        + " weitere Treffer");
                    Alert notified = alert.withNotified(now, ++seq);
                    put(notified);
                    notifications.add(new Notification(notified, Notification.Kind.SUMMARY, state.count, text,
                        rule.targets()));
                }
                state.lastSent = now;
                state.count = 0;
                state.event = null;
            } else if (state.count == 0 && over && !active.containsKey(entry.getKey()) || rule == null) {
                it.remove();
            }
        }
        Instant dropBefore = now.minus(keep);
        alerts.values().removeIf(alert -> !alert.active() && alert.state() != State.OPEN
            && alert.updated().isBefore(dropBefore));
    }

    private void resolve(Key key, Alert alert, RuleDefinition rule, Instant now) {
        Alert resolved = alert.resolve(now, ++seq);
        put(resolved);
        active.remove(key);
        notifications.add(new Notification(resolved, Notification.Kind.RESOLVED, resolved.count(), "erledigt",
            rule == null ? List.of() : rule.targets()));
    }

    /**
     * Acknowledges an open alert. A hit alert is finished by this, a later hit opens a new alert.
     *
     * @param id the alert
     * @param user who acknowledges
     * @param now the current time
     * @return the alert, {@code null} if there is none with this id
     */
    public synchronized Alert ack(String id, String user, Instant now) {
        Alert alert = alerts.get(id);
        if (alert == null || alert.state() != State.OPEN) {
            return alert;
        }
        Alert acked = alert.ack(user, now, ++seq);
        put(acked);
        if (acked.type() == Type.HIT) {
            active.remove(new Key(acked.rule(), acked.app(), acked.group()), acked.id());
        }
        return acked;
    }

    private void put(Alert alert) {
        alerts.put(alert.id(), alert);
        changed.add(alert);
    }

    /**
     * Takes the changes since the last call.
     *
     * @return the changed alerts and the notifications to send
     */
    public synchronized Changes drain() {
        Changes changes = new Changes(List.copyOf(changed), List.copyOf(notifications));
        changed.clear();
        notifications.clear();
        return changes;
    }

    /**
     * All alerts kept.
     *
     * @return the alerts, oldest first
     */
    public synchronized List<Alert> alerts() {
        return List.copyOf(alerts.values());
    }

    /**
     * The alerts changed after a change counter.
     *
     * @param after the counter of the last change seen
     * @return the alerts with a higher counter
     */
    public synchronized List<Alert> changedAfter(long after) {
        return alerts.values().stream().filter(alert -> alert.seq() > after).toList();
    }

    /**
     * The change counter.
     *
     * @return the counter of the last change
     */
    public synchronized long seq() {
        return seq;
    }

    /**
     * An alert by id.
     *
     * @param id the id
     * @return the alert, {@code null} if unknown
     */
    public synchronized Alert alert(String id) {
        return alerts.get(id);
    }

    private String text(String fromRule, String fallback) {
        return cut(fromRule != null ? fromRule : fallback);
    }

    private String line(Map<String, Object> event) {
        Object message = event.get("message");
        if (message == null) {
            return "";
        }
        String text = message.toString();
        int newline = text.indexOf('\n');
        return cut(newline < 0 ? text : text.substring(0, newline));
    }

    private String cut(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxText ? text : text.substring(0, maxText) + " …";
    }

    /**
     * Applies a function to every kept alert, e.g. to count them.
     *
     * @param <T> type of the result
     * @param function the function
     * @return its result
     */
    public synchronized <T> T read(Function<Collection<Alert>, T> function) {
        return function.apply(alerts.values());
    }
}
