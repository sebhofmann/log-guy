package de.gbv.logguy.server.index;

/**
 * Thrown by a {@link Indexer.DocumentSink} when the documents themselves are invalid, e.g. Solr answers with status
 * 400 for an immense term. Sending them again would fail again, unlike after a network error or a 5xx status.
 */
public class DocumentsRejectedException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message why the documents were rejected
     * @param cause the error of the sink
     */
    public DocumentsRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
