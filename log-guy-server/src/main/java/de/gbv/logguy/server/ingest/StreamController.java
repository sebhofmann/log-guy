package de.gbv.logguy.server.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.gbv.logguy.common.IngestProtocol;
import de.gbv.logguy.common.StreamStatus;
import de.gbv.logguy.common.TokenInfo;
import de.gbv.logguy.server.LogGuyApplication;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.security.IngestPrincipal;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The stream API the agents send their log files to, see {@link IngestProtocol}.
 */
@RestController
@Profile("!" + LogGuyApplication.CLI_PROFILE)
@RequestMapping(IngestProtocol.STREAMS_PATH)
public class StreamController {

    private final IngestService service;

    private final Semaphore permits;

    private final int maxChunkSize;

    private final String retryAfter;

    /**
     * Creates the controller.
     *
     * @param service the ingest logic
     * @param properties configuration
     */
    public StreamController(IngestService service, LogGuyProperties properties) {
        this.service = service;
        this.permits = new Semaphore(properties.ingest().maxConcurrent());
        this.maxChunkSize = (int) properties.ingest().maxChunkSize().toBytes();
        this.retryAfter = Long.toString(Math.max(1, properties.ingest().retryAfter().toSeconds()));
    }

    /**
     * What the token of the agent allows, for {@code log-guy-agent check}.
     *
     * @param principal the agent
     * @return name, host and applications of the token
     */
    @GetMapping
    public TokenInfo token(@AuthenticationPrincipal IngestPrincipal principal) {
        return new TokenInfo(principal.name(), principal.host(), principal.apps().stream().sorted().toList());
    }

    /**
     * How far the server has a file.
     *
     * @param principal the agent
     * @param fingerprint the fingerprint
     * @return the status
     */
    @GetMapping("/{fingerprint}")
    public StreamStatus status(@AuthenticationPrincipal IngestPrincipal principal, @PathVariable String fingerprint) {
        return service.status(principal, fingerprint);
    }

    /**
     * Appends a chunk.
     *
     * @param principal the agent
     * @param fingerprint the fingerprint
     * @param offset offset of the first byte
     * @param host host the file comes from
     * @param app application the file belongs to
     * @param file path of the file on the host
     * @param request the request with the raw bytes as body
     * @return the new status
     * @throws IOException if the body cannot be read
     */
    @PostMapping(path = "/{fingerprint}/chunks", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<?> chunk(@AuthenticationPrincipal IngestPrincipal principal,
        @PathVariable String fingerprint, @RequestParam(IngestProtocol.PARAM_OFFSET) long offset,
        @RequestHeader(IngestProtocol.HEADER_HOST) String host, @RequestHeader(IngestProtocol.HEADER_APP) String app,
        @RequestHeader(name = IngestProtocol.HEADER_FILE, required = false) String file, HttpServletRequest request)
        throws IOException {
        if (request.getContentLengthLong() > maxChunkSize) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(Map.of("error", "chunk larger than " + maxChunkSize + " bytes"));
        }
        if (!permits.tryAcquire()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, retryAfter)
                .body(Map.of("error", "too many concurrent chunks"));
        }
        try {
            byte[] data;
            try (InputStream in = request.getInputStream()) {
                data = in.readNBytes(maxChunkSize + 1);
            }
            if (data.length > maxChunkSize) {
                return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                    .body(Map.of("error", "chunk larger than " + maxChunkSize + " bytes"));
            }
            return ResponseEntity
                .ok(service.append(principal, fingerprint, offset, host, app, decode(file), data, data.length));
        } finally {
            permits.release();
        }
    }

    /**
     * Marks a file as rotated and completely transferred.
     *
     * @param principal the agent
     * @param fingerprint the fingerprint
     * @param length the final length
     * @param host host the file comes from
     * @param app application the file belongs to
     * @param file path of the file on the host
     * @return the new status
     */
    @PostMapping("/{fingerprint}/close")
    public StreamStatus close(@AuthenticationPrincipal IngestPrincipal principal, @PathVariable String fingerprint,
        @RequestParam(IngestProtocol.PARAM_LENGTH) long length, @RequestHeader(IngestProtocol.HEADER_HOST) String host,
        @RequestHeader(IngestProtocol.HEADER_APP) String app,
        @RequestHeader(name = IngestProtocol.HEADER_FILE, required = false) String file) {
        return service.close(principal, fingerprint, length, host, app, decode(file));
    }

    private static String decode(String file) {
        return file == null ? null : URLDecoder.decode(file, StandardCharsets.UTF_8);
    }

    /**
     * Turns an {@link IngestException} into its answer: the stream status for 409, an error message otherwise.
     *
     * @param e the exception
     * @return the answer
     */
    @ExceptionHandler(IngestException.class)
    public ResponseEntity<?> handle(IngestException e) {
        if (e.getStreamStatus() != null) {
            return ResponseEntity.status(e.getStatus()).body(e.getStreamStatus());
        }
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
    }
}
