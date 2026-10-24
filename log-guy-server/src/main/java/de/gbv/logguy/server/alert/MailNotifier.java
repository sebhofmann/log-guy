package de.gbv.logguy.server.alert;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;

import de.gbv.logguy.server.config.LogGuyProperties.MailTarget;

/**
 * Sends notifications to the targets {@code mail:<name>} of a rule, as plain text mail.
 */
public class MailNotifier implements Notifier {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final String PREFIX = "mail:";

    private final MailSender sender;

    private final String from;

    private final Map<String, MailTarget> targets;

    private final NotificationTexts texts;

    private final Deliveries deliveries;

    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /**
     * Creates the notifier.
     *
     * @param sender the SMTP connection, {@code null} if {@code spring.mail.host} is not set
     * @param from sender address
     * @param targets mail targets by name
     * @param texts subject and body
     * @param deliveries the queue that sends and retries
     */
    public MailNotifier(MailSender sender, String from, Map<String, MailTarget> targets, NotificationTexts texts,
        Deliveries deliveries) {
        this.sender = sender;
        this.from = from;
        this.targets = targets;
        this.texts = texts;
        this.deliveries = deliveries;
        if (sender == null && !targets.isEmpty()) {
            LOGGER.error("mail targets {} are configured, but spring.mail.host is not set", targets.keySet());
        }
    }

    @Override
    public void send(AlertEngine.Notification notification) {
        for (String target : notification.targets()) {
            if (!target.startsWith(PREFIX)) {
                continue;
            }
            String name = target.substring(PREFIX.length());
            MailTarget mail = targets.get(name);
            if (mail == null || mail.to().isEmpty() || sender == null) {
                if (warned.add(name)) {
                    LOGGER.warn("rule {} names {}, which is not configured under log-guy.notifications.mail or has no "
                        + "SMTP server", notification.alert().rule(), target);
                }
                continue;
            }
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(mail.to().toArray(String[]::new));
            message.setSubject(texts.subject(notification));
            message.setText(texts.body(notification, mail.withText()));
            deliveries.submit(notification.kind() + " of alert " + notification.alert().id() + " to " + target,
                () -> sender.send(message));
        }
    }
}
