package de.gbv.logguy.server.web;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import de.gbv.logguy.server.archive.ArchiveWriter;
import de.gbv.logguy.server.archive.ArchiveWriter.PseudonymizeReport;
import de.gbv.logguy.server.archive.ArchiveWriter.RefilterReport;
import de.gbv.logguy.server.audit.Actor;
import de.gbv.logguy.server.audit.Actor.Via;
import de.gbv.logguy.server.audit.AuditLog;
import de.gbv.logguy.server.config.ConfigReloader;
import de.gbv.logguy.server.config.ConfigReloader.Report;
import de.gbv.logguy.server.hot.FileMeta;
import de.gbv.logguy.server.hot.GapException;
import de.gbv.logguy.server.hot.HotFile;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.reindex.ReindexException;
import de.gbv.logguy.server.reindex.ReindexJob;
import de.gbv.logguy.server.reindex.ReindexJob.Plan;
import de.gbv.logguy.server.reindex.ReindexJob.State;
import de.gbv.logguy.server.reindex.ReindexService;
import de.gbv.logguy.server.reindex.ReindexService.Source;
import de.gbv.logguy.server.script.ScriptStore.ScriptId;
import de.gbv.logguy.server.security.ControlTokenFilter;
import de.gbv.logguy.server.security.CurrentUser;
import de.gbv.logguy.server.security.SecurityConfig;
import de.gbv.logguy.server.serverlog.LogEntry;
import de.gbv.logguy.server.serverlog.ServerLog;
import de.gbv.logguy.server.status.StatusService;
import de.gbv.logguy.server.status.StatusService.StatusView;

/**
 * Pages for administrators: status and the own log. The security configuration allows {@code /admin/**} only for
 * administrators.
 */
@Controller
@Profile("!cli")
public class AdminController {

    private static final int MAX_LOG_ENTRIES = 2000;

    private static final long REINDEX_POLL_MILLIS = 500;

    private final CurrentUser currentUser;

    private final StatusService status;

    private final ServerLog serverLog;

    private final ConfigReloader reloader;

    private final ObjectProvider<ArchiveWriter> archiveWriter;

    private final AuditLog audit;

    private final ReindexService reindex;

    private final HotStore hot;

    /**
     * Creates the controller.
     *
     * @param currentUser resolves the user
     * @param status collects the status
     * @param serverLog the own log
     * @param reloader reads configuration and scripts again
     * @param archiveWriter the archive writer, missing if the archive is disabled
     * @param audit the audit log
     * @param reindex the reindex jobs
     * @param hot the originals
     */
    public AdminController(CurrentUser currentUser, StatusService status, ServerLog serverLog,
        ConfigReloader reloader, ObjectProvider<ArchiveWriter> archiveWriter, AuditLog audit,
        ReindexService reindex, HotStore hot) {
        this.currentUser = currentUser;
        this.status = status;
        this.serverLog = serverLog;
        this.reloader = reloader;
        this.archiveWriter = archiveWriter;
        this.audit = audit;
        this.reindex = reindex;
        this.hot = hot;
    }

    private Actor actor(Authentication authentication) {
        boolean control = authentication.getAuthorities().stream()
            .anyMatch(a -> ControlTokenFilter.CONTROL_AUTHORITY.equals(a.getAuthority()));
        return control ? new Actor(null, ControlTokenFilter.PRINCIPAL, Via.CLI)
            : new Actor(currentUser.get().name(), null, Via.GUI);
    }

    /**
     * Reads configuration and scripts again, for administrators and for {@code log-guy reload} with the control token.
     *
     * @param script only this script, e.g. {@code parser/digibib}
     * @param authentication the caller
     * @return what changed, 200 if it was taken over, otherwise 422 with the errors
     * @throws IOException if the script directory cannot be read
     */
    @PostMapping(SecurityConfig.RELOAD_PATH)
    @ResponseBody
    public ResponseEntity<Report> reload(@RequestParam(required = false) String script,
        Authentication authentication) throws IOException {
        Actor actor = actor(authentication);
        ScriptId only;
        try {
            only = script == null || script.isBlank() ? null : ScriptId.parse(script);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(Report.failed(List.of(), List.of("Unbekanntes Skript: " + script)));
        }
        Report report = reloader.reload(actor, only);
        return ResponseEntity.status(report.ok() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_CONTENT).body(report);
    }

    /**
     * Writes archive files again from {@code hot/} with the current lifecycle filters, for administrators and for
     * {@code log-guy archive refilter} with the control token. Runs until all files are written.
     *
     * @param app only files of this application
     * @param all all files with an original in {@code hot/}, not only those with placeholders
     * @param authentication the caller
     * @return the files written again, 200 if all could be written, otherwise 422
     * @throws InterruptedException if waiting is interrupted
     */
    @PostMapping(SecurityConfig.REFILTER_PATH)
    @ResponseBody
    public ResponseEntity<RefilterReport> refilter(@RequestParam(required = false) String app,
        @RequestParam(defaultValue = "false") boolean all, Authentication authentication)
        throws InterruptedException {
        ArchiveWriter writer = archiveWriter.getIfAvailable();
        if (writer == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new RefilterReport(List.of(), List.of(),
                List.of(), 0, List.of("Das Archiv ist abgeschaltet (log-guy.archive.enabled)")));
        }
        RefilterReport report = writer.refilter(app == null || app.isBlank() ? null : app, all);
        audit.log(actor(authentication), "archive.refilter", app == null ? "*" : app,
            Map.of("all", all, "refiltered", report.refiltered().size(), "missing", report.missing().size(),
                "failed", report.failed().size(), "placeholders", report.placeholders()));
        boolean ok = report.failed().isEmpty() && report.pausedBy().isEmpty();
        return ResponseEntity.status(ok ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_CONTENT).body(report);
    }

    /**
     * Pseudonymizes archive files again with the active pseudonymization scripts, for administrators and for
     * {@code log-guy archive pseudonymize} with the control token. Runs until all files are written.
     *
     * @param app only files of this application
     * @param all also files already pseudonymized with the current scripts
     * @param authentication the caller
     * @return the files written again, 200 if all could be written, otherwise 422
     * @throws InterruptedException if waiting is interrupted
     */
    @PostMapping(SecurityConfig.PSEUDONYMIZE_PATH)
    @ResponseBody
    public ResponseEntity<PseudonymizeReport> pseudonymize(@RequestParam(required = false) String app,
        @RequestParam(defaultValue = "false") boolean all, Authentication authentication)
        throws InterruptedException {
        ArchiveWriter writer = archiveWriter.getIfAvailable();
        if (writer == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new PseudonymizeReport(List.of(), 0, 0,
                List.of(), 0, List.of("Das Archiv ist abgeschaltet (log-guy.archive.enabled)")));
        }
        PseudonymizeReport report = writer.pseudonymize(app == null || app.isBlank() ? null : app, all);
        audit.log(actor(authentication), "archive.pseudonymize", app == null ? "*" : app,
            Map.of("all", all, "pseudonymized", report.pseudonymized().size(), "newKeys", report.newKeys(),
                "unchanged", report.unchanged(), "failed", report.failed().size(), "placeholders",
                report.placeholders()));
        boolean ok = report.failed().isEmpty() && report.pausedBy().isEmpty();
        return ResponseEntity.status(ok ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_CONTENT).body(report);
    }

    /**
     * Indexes a time range of an application again, for administrators and for {@code log-guy reindex} with the
     * control token. Files of {@code hot/} go to the indexer, the part before the index is loaded from the archive.
     * Waits until the archive part is loaded.
     *
     * @param app the application
     * @param from start of the time range
     * @param to end of the time range, now if left out
     * @param source which storage to read
     * @param authentication the caller
     * @return the status of the job, 200 if it is done, otherwise 422
     * @throws ReindexException if there is nothing to do or the request is invalid
     * @throws InterruptedException if waiting is interrupted
     */
    @PostMapping(SecurityConfig.REINDEX_PATH)
    @ResponseBody
    public ResponseEntity<ReindexJob.Status> reindex(@RequestParam String app, @RequestParam Instant from,
        @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "ALL") Source source,
        Authentication authentication) throws ReindexException, InterruptedException {
        Actor actor = actor(authentication);
        Plan plan = reindex.plan(app, from, to, source);
        ReindexJob job = reindex.start(actor.user() != null ? actor.user() : actor.token(), plan);
        audit.log(actor, "reindex.start", app, Map.of("from", String.valueOf(plan.from()), "to",
            String.valueOf(plan.to()), "hotFiles", plan.hotFiles().size(), "archiveFiles",
            plan.archiveFiles().size()));
        while (job.running()) {
            Thread.sleep(REINDEX_POLL_MILLIS);
        }
        ReindexJob.Status result = job.status();
        return ResponseEntity.status(result.state() == State.DONE && result.failed().isEmpty() ? HttpStatus.OK
            : HttpStatus.UNPROCESSABLE_CONTENT).body(result);
    }

    /**
     * Closes an open file in {@code hot/} at the length received so far, for administrators on the status page and
     * for {@code log-guy hot close} with the control token. Meant for abandoned files that no agent will close
     * anymore. Archive writer and indexer finish the file, then it leaves {@code hot/} with its key like a rotated
     * file. If the agent sends more later, it gets the answer for a closed file and stops.
     *
     * @param fingerprint the fingerprint of the file
     * @param force also a file that received data within {@code hot-days}
     * @param authentication the caller
     * @return the closed file, 404 if it is unknown, 409 if it is closed already or not abandoned
     * @throws IOException if writing the file fails
     */
    @PostMapping(SecurityConfig.HOT_CLOSE_PATH)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> closeHotFile(@RequestParam String fingerprint,
        @RequestParam(defaultValue = "false") boolean force, Authentication authentication) throws IOException {
        HotFile file = hot.find(fingerprint).orElse(null);
        if (file == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Keine Datei mit diesem Fingerabdruck in hot/: " + fingerprint));
        }
        FileMeta meta = file.meta();
        if (meta.closed()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Die Datei ist schon abgeschlossen: " + file.relativePath()));
        }
        Instant now = hot.now();
        boolean abandoned = status.abandoned(now).stream().anyMatch(a -> a.fingerprint().equals(fingerprint));
        if (!abandoned && !force) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Die Datei hat innerhalb von "
                + "hot-days noch Daten bekommen, zuletzt " + meta.lastReceived() + ". Abschließen nur mit force."));
        }
        long length = file.length();
        try {
            file.close(length, null, now);
        } catch (GapException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Während des Abschließens kamen Daten an, bitte erneut versuchen."));
        }
        audit.log(actor(authentication), "hot.close", fingerprint, Map.of("host", meta.host(), "app", meta.app(),
            "file", file.relativePath(), "length", length, "lastReceived", meta.lastReceived().toString(),
            "force", force));
        return ResponseEntity.ok(Map.of("fingerprint", fingerprint, "file", file.relativePath(), "length", length));
    }

    /**
     * The status page.
     *
     * @param csrf the CSRF token
     * @return the page
     */
    @GetMapping("/admin/status")
    public ModelAndView statusPage(CsrfToken csrf) {
        return new ModelAndView("status", Map.of("page", new PageContext(currentUser.get(), csrf, "status")));
    }

    /**
     * The server log page.
     *
     * @param csrf the CSRF token
     * @return the page
     */
    @GetMapping("/admin/log")
    public ModelAndView logPage(CsrfToken csrf) {
        return new ModelAndView("serverlog", Map.of("page", new PageContext(currentUser.get(), csrf, "log")));
    }

    /**
     * The current status as JSON.
     *
     * @return the status
     */
    @GetMapping("/admin/api/status")
    @ResponseBody
    public StatusView status() {
        return status.status();
    }

    /**
     * Entries of the own log as JSON, oldest first.
     *
     * @param after only entries with a higher sequence number
     * @param level the least level, e.g. {@code WARN}
     * @param logger part of the logger name
     * @param limit the most entries, the newest are returned
     * @return the entries and the sequence number to continue with
     */
    @GetMapping("/admin/api/log")
    @ResponseBody
    public ResponseEntity<?> log(@RequestParam(defaultValue = "-1") long after,
        @RequestParam(required = false) String level, @RequestParam(required = false) String logger,
        @RequestParam(defaultValue = "500") int limit) {
        Level min = level == null || level.isBlank() ? Level.ALL : Level.getLevel(level.toUpperCase());
        if (min == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unbekannte Stufe: " + level));
        }
        long next = serverLog.nextSeq();
        List<LogEntry> entries = serverLog.entries(after, min, logger, Math.clamp(limit, 1, MAX_LOG_ENTRIES));
        return ResponseEntity.ok(new LogPage(entries, next));
    }

    /**
     * A page of the own log.
     *
     * @param entries the entries, oldest first
     * @param next the next sequence number when the request started, continue with the larger of {@code next - 1}
     *     and the sequence number of the last entry as {@code after}
     */
    public record LogPage(List<LogEntry> entries, long next) {
    }
}
