package de.gbv.logguy.server.search;

import java.io.IOException;
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

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? status.getReasonPhrase()
            : message));
    }
}
