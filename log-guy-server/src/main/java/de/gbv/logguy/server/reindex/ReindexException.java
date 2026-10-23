package de.gbv.logguy.server.reindex;

import java.io.Serial;

/**
 * A reindex request that cannot be done, with a message for the user.
 */
public class ReindexException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the message for the user
     */
    public ReindexException(String message) {
        super(message);
    }

    /**
     * Creates the exception with its cause.
     *
     * @param message the message for the user
     * @param cause the cause
     */
    public ReindexException(String message, Throwable cause) {
        super(message, cause);
    }
}
