package de.gbv.logguy.server.search;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import de.gbv.logguy.server.script.ScriptRequestException;

/**
 * Turns errors of the API into JSON {@code {"error": "..."}} with a German message for the user.
 */
@RestControllerAdvice(basePackages = "de.gbv.logguy.server")
public class ApiExceptionHandler {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * A request the user has to correct.
     *
     * @param e the exception
     * @return 400
     */
    @ExceptionHandler(SearchException.class)
    public ResponseEntity<Map<String, String>> invalid(SearchException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * Missing rights.
     *
     * @param e the exception
     * @return 403
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> denied(AccessDeniedException e) {
        return error(HttpStatus.FORBIDDEN, "Dafür fehlt die Berechtigung.");
    }

    /**
     * Something that does not exist (any more).
     *
     * @param e the exception
     * @return 404
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> missing(NoSuchElementException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * Solr rejected the request, usually a syntax error in the query.
     *
     * @param e the exception
     * @return 400 for errors of the request, otherwise 502
     */
    @ExceptionHandler(SolrException.class)
    public ResponseEntity<Map<String, String>> solr(SolrException e) {
        if (e.code() == HttpStatus.BAD_REQUEST.value()) {
            return error(HttpStatus.BAD_REQUEST, "Solr lehnt die Suche ab: " + e.getMessage());
        }
        LOGGER.warn("Solr error", e);
        return error(HttpStatus.BAD_GATEWAY, "Solr meldet einen Fehler: " + e.getMessage());
    }

    /**
     * Solr is not reachable.
     *
     * @param e the exception
     * @return 502
     */
    @ExceptionHandler({ SolrServerException.class, IOException.class })
    public ResponseEntity<Map<String, String>> unreachable(Exception e) {
        LOGGER.warn("Solr or storage not reachable", e);
        return error(HttpStatus.BAD_GATEWAY, "Solr oder der Speicher ist gerade nicht erreichbar.");
    }

    /**
     * A file of log-guy itself cannot be read or written, e.g. a read-only scripts directory. Named, because only
     * the operator can fix it.
     *
     * @param e the exception
     * @return 500
     */
    @ExceptionHandler(FileSystemException.class)
    public ResponseEntity<Map<String, String>> fileSystem(FileSystemException e) {
        LOGGER.error("File system error", e);
        String reason = e.getReason() == null ? e.getClass().getSimpleName() : e.getReason();
        return error(HttpStatus.INTERNAL_SERVER_ERROR,
            "log-guy kann die Datei " + e.getFile() + " nicht schreiben oder lesen: " + reason);
    }

    /**
     * A script request that failed: invalid input, unknown script, conflict or missing confirmation. The details
     * are added to the body, e.g. the error rate of the last test.
     *
     * @param e the exception
     * @return 400, 404 or 409
     */
    @ExceptionHandler(ScriptRequestException.class)
    public ResponseEntity<Map<String, Object>> script(ScriptRequestException e) {
        HttpStatus status = switch (e.reason()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT, CONFIRM -> HttpStatus.CONFLICT;
        };
        Map<String, Object> body = new LinkedHashMap<>(e.details());
        body.put("error", e.getMessage());
        body.put("reason", e.reason().name().toLowerCase(Locale.ROOT));
        return ResponseEntity.status(status).body(body);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? status.getReasonPhrase()
            : message));
    }
}
