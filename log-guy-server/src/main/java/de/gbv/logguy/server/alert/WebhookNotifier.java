package de.gbv.logguy.server.alert;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.gbv.logguy.server.config.LogGuyProperties.WebhookTarget;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends notifications to the targets {@code webhook:<name>} of a rule as JSON per {@code POST}.
 */
public class WebhookNotifier implements Notifier, AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final String PREFIX = "webhook:";

    private final Map<String, WebhookTarget> targets;

    private final NotificationTexts texts;

    private final Deliveries deliveries;

    private final JsonMapper json;

    private final Duration timeout;

    private final HttpClient http;

    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /**
     * Creates the notifier.
     *
     * @param targets webhook targets by name
     * @param texts text and JSON fields
     * @param deliveries the queue that sends and retries
     * @param json mapper for the body
     * @param timeout connect and request timeout
     */
    public WebhookNotifier(Map<String, WebhookTarget> targets, NotificationTexts texts, Deliveries deliveries,
        JsonMapper json, Duration timeout) {
        this.targets = targets;
        this.texts = texts;
        this.deliveries = deliveries;
        this.json = json;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public void send(AlertEngine.Notification notification) {
        for (String target : notification.targets()) {
            if (!target.startsWith(PREFIX)) {
                continue;
            }
            String name = target.substring(PREFIX.length());
            WebhookTarget webhook = targets.get(name);
            if (webhook == null) {
                if (warned.add(name)) {
                    LOGGER.warn("rule {} names {}, which is not configured under log-guy.notifications.webhook",
                        notification.alert().rule(), target);
                }
                continue;
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(webhook.url())).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                    texts.json(notification, webhook.withText()))));
            webhook.headers().forEach(request::header);
            HttpRequest built = request.build();
            deliveries.submit(notification.kind() + " of alert " + notification.alert().id() + " to " + target,
                () -> post(built));
        }
    }

    private void post(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            String body = response.body();
            throw new IOException("HTTP " + response.statusCode() + ": "
                + (body.length() > 200 ? body.substring(0, 200) : body));
        }
    }

    @Override
    public void close() {
        http.shutdownNow();
    }
}
