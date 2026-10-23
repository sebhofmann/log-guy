package de.gbv.logguy.server.alert;

/**
 * Sends notifications of alerts to their targets.
 */
@FunctionalInterface
public interface Notifier {

    /**
     * Sends a notification. Called outside of any lock, may take a while.
     *
     * @param notification what to send
     */
    void send(AlertEngine.Notification notification);
}
