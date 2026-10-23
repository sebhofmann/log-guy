package de.gbv.logguy.server.ingest;

import org.springframework.http.HttpStatus;

import de.gbv.logguy.common.StreamStatus;

/**
 * A request to the stream API that cannot be fulfilled.
 */
public class IngestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;

    private final transient StreamStatus streamStatus;

    /**
     * Creates the exception.
     *
     * @param status HTTP status of the answer
     * @param message reason, sent to the agent
     * @param streamStatus status of the stream sent as body, or null for an error message
     */
    public IngestException(HttpStatus status, String message, StreamStatus streamStatus) {
        super(message);
        this.status = status;
        this.streamStatus = streamStatus;
    }

    /**
     * HTTP status of the answer.
     *
     * @return the status
     */
    public HttpStatus getStatus() {
        return status;
    }

    /**
     * Status of the stream sent as body.
     *
     * @return the stream status or null
     */
    public StreamStatus getStreamStatus() {
        return streamStatus;
    }
}
