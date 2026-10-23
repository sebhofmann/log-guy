package de.gbv.logguy.server.hot;

import de.gbv.logguy.common.StreamStatus;

/**
 * A chunk does not fit to what is stored: it starts behind the end, or the file is already closed.
 */
public class GapException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient StreamStatus status;

    /**
     * Creates the exception.
     *
     * @param message what went wrong
     * @param status what is stored, the agent continues there
     */
    public GapException(String message, StreamStatus status) {
        super(message);
        this.status = status;
    }

    /**
     * What is stored.
     *
     * @return the current status of the stream
     */
    public StreamStatus getStatus() {
        return status;
    }
}
