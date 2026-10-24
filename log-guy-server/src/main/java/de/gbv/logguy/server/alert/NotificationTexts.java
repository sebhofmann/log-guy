package de.gbv.logguy.server.alert;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Subject, body and JSON of a notification for mails and webhooks, in German like the GUI.
 */
public class NotificationTexts {

    /** Time shown before and after an alert in the linked search, as on the alert page. */
    private static final Duration MARGIN = Duration.ofMinutes(5);

    private final String baseUrl;

    private final DateTimeFormatter time;

    /**
     * Creates the texts.
     *
     * @param baseUrl address of the GUI without trailing slash, {@code null} for no links
     * @param zone time zone of the times in the texts
     */
    public NotificationTexts(String baseUrl, ZoneId zone) {
        this.baseUrl = baseUrl;
        this.time = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(zone);
    }

    /**
     * The subject of a mail and the first line of a webhook.
     *
     * @param notification the notification
     * @return e.g. {@code [log-guy] Neue Meldung: pool in digibib}
     */
    public String subject(AlertEngine.Notification notification) {
        Alert alert = notification.alert();
        String where = alert.group() == null ? alert.app() : alert.app() + " (" + alert.group() + ")";
        return "[log-guy] " + kind(notification.kind()) + ": " + alert.rule() + " in " + where;
    }

    /**
     * The body of a mail.
     *
     * @param notification the notification
     * @param withText whether text and event line are included
     * @return the plain text body
     */
    public String body(AlertEngine.Notification notification, boolean withText) {
        Alert alert = notification.alert();
        StringBuilder body = new StringBuilder();
        if (withText) {
            body.append(notification.text()).append("\n\n");
        }
        body.append("Regel: ").append(alert.rule()).append(" (").append(type(alert.type())).append(")\n");
        body.append("Anwendung: ").append(alert.app()).append('\n');
        if (alert.group() != null) {
            body.append("Gruppe: ").append(alert.group()).append('\n');
        }
        body.append("Anzahl: ").append(notification.count()).append('\n');
        body.append("Seit: ").append(time.format(alert.opened())).append('\n');
        if (withText && alert.eventLine() != null) {
            body.append("Event: ").append(alert.eventLine()).append('\n');
        }
        String events = eventsUrl(alert);
        if (events != null) {
            body.append("\nEvents ansehen: ").append(events).append('\n');
            body.append("Alle Meldungen: ").append(baseUrl).append("/alerts\n");
        }
        return body.toString();
    }

    /**
     * The JSON body of a webhook. {@code text} suits Mattermost, Slack and Matrix hookshot, the other fields are for
     * receivers that build their own message.
     *
     * @param notification the notification
     * @param withText whether text and event line are included
     * @return the fields of the JSON object
     */
    public Map<String, Object> json(AlertEngine.Notification notification, boolean withText) {
        Alert alert = notification.alert();
        String events = eventsUrl(alert);
        StringBuilder text = new StringBuilder(subject(notification));
        if (withText) {
            text.append('\n').append(notification.text());
        }
        if (events != null) {
            text.append('\n').append(events);
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("text", text.toString());
        json.put("kind", notification.kind().name());
        json.put("id", alert.id());
        json.put("rule", alert.rule());
        json.put("type", alert.type().name());
        json.put("app", alert.app());
        json.put("group", alert.group());
        json.put("state", alert.state().name());
        json.put("count", notification.count());
        json.put("opened", alert.opened().toString());
        if (withText) {
            json.put("message", notification.text());
            json.put("eventLine", alert.eventLine());
        }
        json.put("url", events);
        return json;
    }

    /**
     * Link to the search around the alert with its first event open, like on the alert page.
     *
     * @param alert the alert
     * @return the link, {@code null} without base URL
     */
    public String eventsUrl(Alert alert) {
        if (baseUrl == null) {
            return null;
        }
        Instant from = (alert.firstTs() == null ? alert.opened() : alert.firstTs()).minus(MARGIN);
        Instant to = (alert.lastTs() == null ? alert.updated() : alert.lastTs()).plus(MARGIN);
        StringBuilder url = new StringBuilder(baseUrl).append("/?apps=").append(encode(alert.app()))
            .append("&from=").append(encode(from.toString())).append("&to=").append(encode(to.toString()));
        if (alert.eventId() != null) {
            url.append("&at=").append(encode(alert.eventId()));
        }
        return url.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String kind(AlertEngine.Notification.Kind kind) {
        return switch (kind) {
            case OPENED -> "Neue Meldung";
            case SUMMARY -> "Weitere Treffer";
            case RESOLVED -> "Erledigt";
        };
    }

    private static String type(RuleDefinition.Type type) {
        return switch (type) {
            case HIT -> "Treffer";
            case THRESHOLD -> "Schwelle";
            case ABSENCE -> "Stille";
        };
    }
}
