package de.gbv.logguy.server.archive.search;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import de.gbv.logguy.server.archive.search.ArchiveSearchJob.Owner;
import de.gbv.logguy.server.archive.search.ArchiveSearchJob.Status;
import de.gbv.logguy.server.audit.Actor;
import de.gbv.logguy.server.audit.Actor.Via;
import de.gbv.logguy.server.audit.AuditLog;
import de.gbv.logguy.server.pseudonym.Pseudonyms;
import de.gbv.logguy.server.security.CurrentUser;
import de.gbv.logguy.server.security.LogGuyUser;
import tools.jackson.databind.json.JsonMapper;

/**
 * The archive search API: start a job, follow its progress, stream its hits and cancel it. A job belongs to the user
 * and token that started it.
 */
@RestController
@RequestMapping("/api/v1/archive-search")
@Profile("!cli")
public class ArchiveSearchController {

    /** Hits as one JSON object per line. */
    public static final String NDJSON = "application/x-ndjson";

    private static final String SESSION_PSEUDONYMS = Pseudonyms.class.getName();

    private static final int CHUNK = 500;

    private static final long WAIT_MILLIS = 1000;

    private final ArchiveSearch search;

    private final CurrentUser currentUser;

    private final AuditLog audit;

    private final JsonMapper json;

    private final HttpServletRequest request;

    /**
     * Creates the controller.
     *
     * @param search the archive search
     * @param currentUser resolves the user
     * @param audit the audit log
     * @param json serializes the hits
     * @param request the current request, for the pseudonyms of the session
     */
    public ArchiveSearchController(ArchiveSearch search, CurrentUser currentUser, AuditLog audit, JsonMapper json,
        HttpServletRequest request) {
        this.search = search;
        this.currentUser = currentUser;
        this.audit = audit;
        this.json = json;
        this.request = request;
    }

    /**
     * The applications and hosts the user may search and their jobs.
     *
     * @return {@code apps}, {@code hosts} and {@code jobs}
     */
    @GetMapping
    public Map<String, Object> overview() {
        LogGuyUser user = currentUser.get();
        Set<String> apps = search.apps(user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("apps", apps);
        result.put("hosts", search.hosts(apps));
        result.put("jobs", search.jobs(owner(user)).stream().map(ArchiveSearchJob::status).toList());
        return result;
    }

    /**
     * Starts a job.
     *
     * @param body what to search
     * @return the status of the new job
     */
    @PostMapping
    public ResponseEntity<Status> start(@RequestBody StartBody body) {
        LogGuyUser user = currentUser.get();
        ArchiveSearchJob job = search.start(user, owner(user), session(user), body.request());
        Status status = job.status();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("query", status.query());
        details.put("regex", status.regex());
        details.put("apps", status.apps());
        details.put("from", String.valueOf(status.from()));
        details.put("to", String.valueOf(status.to()));
        audit.log(new Actor(user.name(), user.token(), user.token() == null ? Via.GUI : Via.API),
            "archive-search.start", job.id(), details);
        return ResponseEntity.status(HttpStatus.CREATED).body(status);
    }

    /**
     * Progress of a job.
     *
     * @param id the job
     * @return the status
     */
    @GetMapping("/{id}")
    public Status status(@PathVariable String id) {
        return search.job(owner(currentUser.get()), id).status();
    }

    /**
     * Cancels a job.
     *
     * @param id the job
     * @return the status
     */
    @DeleteMapping("/{id}")
    public Status cancel(@PathVariable String id) {
        return search.cancel(owner(currentUser.get()), id).status();
    }

    /**
     * Streams the hits of a job, as NDJSON or as server-sent events with {@code Accept: text/event-stream}. With
     * {@code follow} the stream stays open until the job has finished, server-sent events then end with an event
     * {@code end} carrying the status.
     *
     * @param id the job
     * @param offset index of the first hit
     * @param follow whether to wait for further hits
     * @param accept the accepted media types
     * @return the stream
     */
    @GetMapping("/{id}/results")
    public ResponseEntity<StreamingResponseBody> results(@PathVariable String id,
        @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "true") boolean follow,
        @RequestHeader(name = "Accept", required = false) String accept) {
        ArchiveSearchJob job = search.job(owner(currentUser.get()), id);
        boolean sse = accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
        StreamingResponseBody body = out -> stream(job, Math.max(0, offset), follow, sse, out);
        return ResponseEntity.ok()
            .contentType(sse ? MediaType.TEXT_EVENT_STREAM : MediaType.parseMediaType(NDJSON + ";charset=UTF-8"))
            .header("Cache-Control", "no-store").header("X-Accel-Buffering", "no").body(body);
    }

    private void stream(ArchiveSearchJob job, int start, boolean follow, boolean sse, OutputStream out)
        throws IOException {
        int offset = start;
        try {
            while (true) {
                List<ArchiveHit> hits = job.hits(offset, CHUNK);
                for (ArchiveHit hit : hits) {
                    write(out, sse ? "data: " + json.writeValueAsString(hit) + "\n\n"
                        : json.writeValueAsString(hit) + "\n");
                }
                offset += hits.size();
                out.flush();
                if (!hits.isEmpty()) {
                    continue;
                }
                if (!follow || !job.running() && job.hits(offset, 1).isEmpty()) {
                    break;
                }
                job.await(offset, WAIT_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (sse) {
            write(out, "event: end\ndata: " + json.writeValueAsString(job.status()) + "\n\n");
            out.flush();
        }
    }

    /**
     * The JSON body of a start, {@code regex} and {@code ignoreCase} may be left out.
     *
     * @param apps applications, empty for all the user may search
     * @param hosts hosts, empty for all
     * @param from start of the time range
     * @param to end of the time range
     * @param query the words or the regular expression
     * @param regex whether {@code query} is a regular expression, default false
     * @param ignoreCase whether case is ignored, default false
     * @param context lines before and after each hit
     */
    public record StartBody(List<String> apps, List<String> hosts, Instant from, Instant to, String query,
        Boolean regex, Boolean ignoreCase, Integer context) {

        ArchiveSearchRequest request() {
            return new ArchiveSearchRequest(apps, hosts, from, to, query, Boolean.TRUE.equals(regex),
                Boolean.TRUE.equals(ignoreCase), context);
        }
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Owner owner(LogGuyUser user) {
        return new Owner(user.name(), user.token(), "web");
    }

    // a GUI session keeps its pseudonyms, a request with an API token gets new ones
    private Pseudonyms session(LogGuyUser user) {
        if (user.token() != null) {
            return new Pseudonyms();
        }
        HttpSession session = request.getSession(true);
        synchronized (session) {
            if (!(session.getAttribute(SESSION_PSEUDONYMS) instanceof Pseudonyms pseudonyms)) {
                Pseudonyms created = new Pseudonyms();
                session.setAttribute(SESSION_PSEUDONYMS, created);
                return created;
            }
            return pseudonyms;
        }
    }
}
