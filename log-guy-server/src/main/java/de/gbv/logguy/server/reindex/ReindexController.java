package de.gbv.logguy.server.reindex;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.gbv.logguy.server.audit.Actor;
import de.gbv.logguy.server.audit.Actor.Via;
import de.gbv.logguy.server.audit.AuditLog;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.Right;
import de.gbv.logguy.server.reindex.ReindexJob.Plan;
import de.gbv.logguy.server.reindex.ReindexJob.Status;
import de.gbv.logguy.server.reindex.ReindexService.Source;
import de.gbv.logguy.server.retention.RetentionService;
import de.gbv.logguy.server.security.CurrentUser;
import de.gbv.logguy.server.security.LogGuyUser;

/**
 * The reindex API. Indexing files of {@code hot/} again needs the right {@code scripts}, loading older days from the
 * archive the right {@code archive} for the application.
 */
@RestController
@RequestMapping("/api/v1/reindex")
@Profile("!cli")
public class ReindexController {

    private final ReindexService reindex;

    private final RetentionService retention;

    private final LogGuyProperties properties;

    private final CurrentUser currentUser;

    private final AuditLog audit;

    /**
     * Creates the controller.
     *
     * @param reindex the reindex jobs
     * @param retention knows the index window of each application
     * @param properties configuration, for the known applications
     * @param currentUser resolves the user
     * @param audit the audit log
     */
    public ReindexController(ReindexService reindex, RetentionService retention, LogGuyProperties properties,
        CurrentUser currentUser, AuditLog audit) {
        this.reindex = reindex;
        this.retention = retention;
        this.properties = properties;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    /**
     * The applications the user may index again or load, with the start of their index window, and the recent jobs.
     *
     * @return {@code apps}, {@code expiresAfterHours} and {@code jobs}
     */
    @GetMapping
    public Map<String, Object> overview() {
        LogGuyUser user = currentUser.get();
        Set<String> known = new TreeSet<>(properties.apps().keySet());
        List<Map<String, Object>> apps = new ArrayList<>();
        for (String app : known) {
            boolean hot = user.may(Right.SCRIPTS, app);
            boolean archive = user.may(Right.ARCHIVE, app);
            if (hot || archive) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("app", app);
                entry.put("windowStart", retention.windowStart(app));
                entry.put("hot", hot);
                entry.put("archive", archive);
                apps.add(entry);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("apps", apps);
        result.put("expiresAfterHours", properties.reindex().expiresAfter().toHours());
        result.put("jobs", reindex.jobs(user::mayRead).stream().map(ReindexJob::status).toList());
        return result;
    }

    /**
     * Starts a job.
     *
     * @param body what to index again
     * @return the status of the new job
     * @throws ReindexException if there is nothing to do or the request is invalid
     */
    @PostMapping
    public ResponseEntity<Status> start(@RequestBody StartBody body) throws ReindexException {
        LogGuyUser user = currentUser.get();
        if (body.app() == null || !user.mayRead(body.app()) && !user.may(Right.ARCHIVE, body.app())
            && !user.may(Right.SCRIPTS, body.app())) {
            throw new AccessDeniedException("app " + body.app());
        }
        Plan plan = reindex.plan(body.app(), body.from(), body.to(), body.source() == null ? Source.ALL
            : body.source());
        if (plan.indexesHot() && !user.may(Right.SCRIPTS, plan.app())) {
            throw new AccessDeniedException("scripts " + plan.app());
        }
        if (plan.loadsArchive() && !user.may(Right.ARCHIVE, plan.app())) {
            throw new AccessDeniedException("archive " + plan.app());
        }
        ReindexJob job = reindex.start(user.name(), plan);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", String.valueOf(plan.from()));
        details.put("to", String.valueOf(plan.to()));
        details.put("hotFiles", plan.hotFiles().size());
        details.put("archiveFiles", plan.archiveFiles().size());
        audit.log(new Actor(user.name(), user.token(), user.token() == null ? Via.GUI : Via.API), "reindex.start",
            plan.app(), details);
        return ResponseEntity.status(HttpStatus.CREATED).body(job.status());
    }

    /**
     * Progress of a job.
     *
     * @param id the job
     * @return the status
     */
    @GetMapping("/{id}")
    public Status status(@PathVariable String id) {
        return reindex.job(id, currentUser.get()::mayRead).status();
    }

    /**
     * Cancels the archive part of a job. Files handed to the indexer are indexed anyway.
     *
     * @param id the job
     * @return the status
     */
    @DeleteMapping("/{id}")
    public Status cancel(@PathVariable String id) {
        LogGuyUser user = currentUser.get();
        ReindexJob job = reindex.job(id, user::mayRead);
        if (!user.may(Right.ARCHIVE, job.plan().app())) {
            throw new AccessDeniedException("archive " + job.plan().app());
        }
        job.cancel();
        return job.status();
    }

    /**
     * The JSON body of a start.
     *
     * @param app the application
     * @param from start of the time range
     * @param to end of the time range, {@code null} for now
     * @param source which storage to read, {@code null} for both
     */
    public record StartBody(String app, Instant from, Instant to, Source source) {
    }
}
