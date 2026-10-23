package de.gbv.logguy.server.alert;

import java.time.Instant;

/**
 * An alert, one line in {@code state/alerts.ndjson}. The last line of an id is its current state.
 *
 * @param id the id
 * @param rule name of the rule
 * @param type kind of the rule
 * @param app the application
 * @param group value of the field {@code threshold.per}, {@code null} if counted per application only
 * @param state open, acknowledged or resolved
 * @param opened when the alert was opened
 * @param updated last change
 * @param firstTs time of the first event, for silence the time of the last event before it
 * @param lastTs time of the last event counted
 * @param count events counted since the alert was opened
 * @param message text of the alert
 * @param eventId id of the first event in the index, {@code null} for silence
 * @param eventLine first line of the first event
 * @param notified time of the last notification, {@code null} if none was sent yet
 * @param ackedBy who acknowledged the alert
 * @param acked when the alert was acknowledged
 * @param resolved when the alert was resolved
 * @param seq change counter, increases with every change of any alert
 */
public record Alert(String id, String rule, RuleDefinition.Type type, String app, String group, State state,
    Instant opened, Instant updated, Instant firstTs, Instant lastTs, long count, String message, String eventId,
    String eventLine, Instant notified, String ackedBy, Instant acked, Instant resolved, long seq) {

    /**
     * State of an alert.
     */
    public enum State {
        /** Not looked at yet. */
        OPEN,
        /** Somebody has seen it. */
        ACKED,
        /** A threshold or silence rule is below its limit again. */
        RESOLVED;

        /**
         * Parses the name used by the API.
         *
         * @param text {@code open}, {@code acked} or {@code resolved}, any case
         * @return the state
         */
        public static State from(String text) {
            return valueOf(text.trim().toUpperCase(java.util.Locale.ROOT));
        }
    }

    Alert counted(Instant ts, Instant now, long seq) {
        return new Alert(id, rule, type, app, group, state, opened, now, firstTs,
            lastTs == null || ts.isAfter(lastTs) ? ts : lastTs, count + 1, message, eventId, eventLine, notified,
            ackedBy, acked, resolved, seq);
    }

    Alert withNotified(Instant now, long seq) {
        return new Alert(id, rule, type, app, group, state, opened, now, firstTs, lastTs, count, message, eventId,
            eventLine, now, ackedBy, acked, resolved, seq);
    }

    Alert ack(String user, Instant now, long seq) {
        return new Alert(id, rule, type, app, group, State.ACKED, opened, now, firstTs, lastTs, count, message,
            eventId, eventLine, notified, user, now, resolved, seq);
    }

    Alert resolve(Instant now, long seq) {
        return new Alert(id, rule, type, app, group, State.RESOLVED, opened, now, firstTs, lastTs, count, message,
            eventId, eventLine, notified, ackedBy, acked, now, seq);
    }

    /**
     * Whether the alert still waits for its rule: open, or acknowledged but not resolved for thresholds and
     * silence.
     *
     * @return true if new events count for this alert
     */
    public boolean active() {
        return state == State.OPEN || state == State.ACKED && type != RuleDefinition.Type.HIT;
    }
}
