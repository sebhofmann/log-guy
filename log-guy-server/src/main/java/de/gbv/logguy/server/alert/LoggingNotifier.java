package de.gbv.logguy.server.alert;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Writes notifications to the server log, until mail and webhooks exist.
 */
public class LoggingNotifier implements Notifier {

    private static final Logger LOGGER = LogManager.getLogger();

    @Override
    public void send(AlertEngine.Notification notification) {
        Alert alert = notification.alert();
        LOGGER.info("alert {} {} rule={} app={} count={} targets={}: {}", notification.kind(), alert.id(),
            alert.rule(), alert.app(), notification.count(), notification.targets(), notification.text());
    }
}
