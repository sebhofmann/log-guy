package de.gbv.logguy.server.search;

/**
 * A search request the user has to correct. The message is shown to the user and therefore German.
 */
public class SearchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message message for the user
     */
    public SearchException(String message) {
        super(message);
    }
}
