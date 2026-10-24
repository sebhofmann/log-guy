package de.gbv.logguy.common;

/**
 * Constants of the stream API between agent and server.
 * <p>
 * Endpoints:
 * <ul>
 * <li>{@code GET /api/v1/streams}: name, host and applications of the token as {@link TokenInfo}</li>
 * <li>{@code GET /api/v1/streams/{fingerprint}}: how far the server has the file, 404 if unknown</li>
 * <li>{@code POST /api/v1/streams/{fingerprint}/chunks?offset=N}: append raw bytes, host, app and file name in the
 * headers. The chunk at offset 0 has to contain at least {@value Fingerprint#PREFIX_BYTES} bytes or the complete
 * file, so the server can verify the fingerprint. Answers after the bytes are synced to disk.</li>
 * <li>{@code POST /api/v1/streams/{fingerprint}/close?length=N}: the file is rotated and completely transferred</li>
 * </ul>
 * The endpoints of a file answer with a {@link StreamStatus}. {@code 409 Conflict} also carries a {@link StreamStatus}, the
 * agent continues at its {@code length}.
 */
public final class IngestProtocol {

    /** Base path of the stream API. */
    public static final String STREAMS_PATH = "/api/v1/streams";

    /** Header with the host the file comes from. */
    public static final String HEADER_HOST = "X-Log-Guy-Host";

    /** Header with the application the file belongs to. */
    public static final String HEADER_APP = "X-Log-Guy-App";

    /** Header with the path of the file on the host, URL encoded as UTF-8. */
    public static final String HEADER_FILE = "X-Log-Guy-File";

    /** Query parameter with the offset of a chunk. */
    public static final String PARAM_OFFSET = "offset";

    /** Query parameter with the final length of a closed file. */
    public static final String PARAM_LENGTH = "length";

    private IngestProtocol() {
    }
}
