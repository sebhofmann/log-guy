package de.gbv.logguy.server.search;

/**
 * The context cannot be shown right now, e.g. because a lifecycle filter of the application cannot be loaded and
 * the lines would otherwise leave unfiltered. The message is shown to the user and therefore German.
 */
public class ContextUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message message for the user
     */
    public ContextUnavailableException(String message) {
        super(message);
    }
}
