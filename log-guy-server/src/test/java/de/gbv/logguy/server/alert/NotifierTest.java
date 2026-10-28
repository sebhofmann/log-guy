package de.gbv.logguy.server.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.sun.net.httpserver.HttpServer;

import de.gbv.logguy.server.config.LogGuyProperties.MailTarget;
import de.gbv.logguy.server.config.LogGuyProperties.WebhookTarget;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class NotifierTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Instant T0 = Instant.parse("2026-10-24T02:24:49.861Z");

    private static final NotificationTexts TEXTS =
        new NotificationTexts("https://log-guy.example.org", ZoneId.of("Europe/Berlin"));

    private static AlertEngine.Notification notification(List<String> targets) {
        Alert alert = new Alert("a1", "pool", RuleDefinition.Type.HIT, "digibib", null, Alert.State.OPEN, T0, T0,
            T0, T0, 1, "Connection Pool voll (1 Treffer)", "f00:42", "2026-10-24T04:24:49,861 WARN  mueller "
                + "HikariPool: Connection is not available",
            null, null, null, null, 1);
        return new AlertEngine.Notification(alert, AlertEngine.Notification.Kind.OPENED, 1, alert.message(),
            targets);
    }

    private static void awaitSettled(Deliveries deliveries) throws InterruptedException {
        for (int i = 0; i < 200 && deliveries.stats().pending() > 0; i++) {
            Thread.sleep(25);
        }
    }

    @Test
    void mailWithAndWithoutText() throws Exception {
        List<SimpleMailMessage> sent = new CopyOnWriteArrayList<>();
        MailSender sender = new MailSender() {
            @Override
            public void send(SimpleMailMessage... messages) {
                sent.addAll(List.of(messages));
            }
        };
        try (Deliveries deliveries = new Deliveries(1, Duration.ZERO)) {
            MailNotifier notifier = new MailNotifier(sender, "log-guy@example.org",
                Map.of("it", new MailTarget(List.of("it@example.org"), null),
                    "extern", new MailTarget(List.of("extern@example.org"), false)),
                TEXTS, deliveries);
            notifier.send(notification(List.of("mail:it", "mail:extern", "mail:unbekannt", "webhook:chat")));
            awaitSettled(deliveries);
            assertEquals(new Deliveries.Stats(2, 0, 0), deliveries.stats());
        }
        SimpleMailMessage it = sent.stream().filter(m -> m.getTo()[0].startsWith("it@")).findFirst().orElseThrow();
        assertEquals("[log-guy] Neue Meldung: pool in digibib", it.getSubject());
        assertEquals("log-guy@example.org", it.getFrom());
        assertTrue(it.getText().startsWith("Connection Pool voll (1 Treffer)\n\nRegel: pool (Treffer)\n"),
            it.getText());
        assertTrue(it.getText().contains("Seit: 24.10.2026 04:24:49\n"), it.getText());
        assertTrue(it.getText().contains("Event: 2026-10-24T04:24:49,861 WARN  mueller"), it.getText());
        assertTrue(it.getText().contains("Events ansehen: https://log-guy.example.org/?apps=digibib"
            + "&from=2026-10-24T02%3A19%3A49.861Z&to=2026-10-24T02%3A29%3A49.861Z&at=f00%3A42\n"), it.getText());
        SimpleMailMessage extern =
            sent.stream().filter(m -> m.getTo()[0].startsWith("extern@")).findFirst().orElseThrow();
        assertFalse(extern.getText().contains("mueller"), extern.getText());
        assertFalse(extern.getText().contains("Connection"), extern.getText());
        assertTrue(extern.getText().contains("Events ansehen: https://"), extern.getText());
    }

    @Test
    void retriesUntilTheLastAttempt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MailSender failing = new MailSender() {
            @Override
            public void send(SimpleMailMessage... messages) {
                if (calls.incrementAndGet() < 3) {
                    throw new MailSendException("connection refused");
                }
            }
        };
        try (Deliveries deliveries = new Deliveries(3, Duration.ofMillis(10))) {
            new MailNotifier(failing, "a@b.c", Map.of("it", new MailTarget(List.of("it@example.org"), null)), TEXTS,
                deliveries).send(notification(List.of("mail:it")));
            awaitSettled(deliveries);
            assertEquals(new Deliveries.Stats(1, 0, 0), deliveries.stats());
            assertEquals(3, calls.get());
            calls.set(-10);
            new MailNotifier(failing, "a@b.c", Map.of("it", new MailTarget(List.of("it@example.org"), null)), TEXTS,
                deliveries).send(notification(List.of("mail:it")));
            awaitSettled(deliveries);
            assertEquals(new Deliveries.Stats(1, 1, 0), deliveries.stats());
        }
    }

    @Test
    void webhookPostsJson() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        List<String> tokens = new CopyOnWriteArrayList<>();
        AtomicInteger status = new AtomicInteger(200);
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/hook", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            tokens.add(exchange.getRequestHeaders().getFirst("X-Token"));
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort() + "/hook";
        try (Deliveries deliveries = new Deliveries(2, Duration.ofMillis(10));
            WebhookNotifier notifier = new WebhookNotifier(
                Map.of("chat", new WebhookTarget(url, Map.of("X-Token", "geheim"), null),
                    "kurz", new WebhookTarget(url, Map.of(), false)),
                TEXTS, deliveries, JSON, Duration.ofSeconds(5))) {
            notifier.send(notification(List.of("webhook:chat", "mail:it")));
            awaitSettled(deliveries);
            assertEquals(new Deliveries.Stats(1, 0, 0), deliveries.stats());
            JsonNode body = JSON.readTree(bodies.getFirst());
            assertEquals("[log-guy] Neue Meldung: pool in digibib\nConnection Pool voll (1 Treffer)\n"
                + "https://log-guy.example.org/?apps=digibib&from=2026-10-24T02%3A19%3A49.861Z"
                + "&to=2026-10-24T02%3A29%3A49.861Z&at=f00%3A42", body.get("text").asString());
            assertEquals("OPENED", body.get("kind").asString());
            assertEquals("digibib", body.get("app").asString());
            assertTrue(body.get("eventLine").asString().contains("mueller"));
            assertEquals("geheim", tokens.getFirst());

            bodies.clear();
            notifier.send(notification(List.of("webhook:kurz")));
            awaitSettled(deliveries);
            JsonNode brief = JSON.readTree(bodies.getFirst());
            assertFalse(brief.has("eventLine"));
            assertFalse(brief.get("text").asString().contains("Connection"));

            status.set(500);
            notifier.send(notification(List.of("webhook:chat")));
            awaitSettled(deliveries);
            assertEquals(new Deliveries.Stats(2, 1, 0), deliveries.stats());
        } finally {
            server.stop(0);
        }
    }

    /**
     * A mail server that accepts the connection and never answers runs into the SMTP timeout, and a webhook sent
     * after the mail arrives while the mail still hangs.
     */
    @Test
    void hangingMailServerDoesNotHoldUpWebhooks() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/hook", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort() + "/hook";
        try (ServerSocket smtp = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Deliveries deliveries = new Deliveries(1, Duration.ZERO);
            WebhookNotifier webhook = new WebhookNotifier(Map.of("chat", new WebhookTarget(url, Map.of(), null)),
                TEXTS, deliveries, JSON, Duration.ofSeconds(5))) {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(smtp.getInetAddress().getHostAddress());
            sender.setPort(smtp.getLocalPort());
            MailNotifier.applyTimeouts(sender, Duration.ofSeconds(3));
            new MailNotifier(sender, "a@b.c", Map.of("it", new MailTarget(List.of("it@example.org"), null)), TEXTS,
                deliveries).send(notification(List.of("mail:it")));
            webhook.send(notification(List.of("webhook:chat")));

            for (int i = 0; i < 80 && deliveries.stats().sent() == 0; i++) {
                Thread.sleep(25);
            }
            assertEquals(1, bodies.size());
            assertEquals(new Deliveries.Stats(1, 0, 1), deliveries.stats());
            awaitSettled(deliveries);
            assertEquals(new Deliveries.Stats(1, 1, 0), deliveries.stats());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void noLinksWithoutBaseUrl() {
        NotificationTexts texts = new NotificationTexts(null, ZoneId.of("UTC"));
        String body = texts.body(notification(List.of()), true);
        assertFalse(body.contains("Events ansehen"), body);
        assertEquals(null, texts.json(notification(List.of()), true).get("url"));
    }
}
