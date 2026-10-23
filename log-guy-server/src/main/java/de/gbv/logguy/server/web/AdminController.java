package de.gbv.logguy.server.web;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
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

import de.gbv.logguy.server.audit.Actor;
import de.gbv.logguy.server.audit.Actor.Via;
import de.gbv.logguy.server.config.ConfigReloader;
import de.gbv.logguy.server.config.ConfigReloader.Report;
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

    private final CurrentUser currentUser;

    private final StatusService status;

    private final ServerLog serverLog;

    private final ConfigReloader reloader;

    /**
     * Creates the controller.
     *
     * @param currentUser resolves the user
     * @param status collects the status
     * @param serverLog the own log
     * @param reloader reads configuration and scripts again
     */
    public AdminController(CurrentUser currentUser, StatusService status, ServerLog serverLog,
        ConfigReloader reloader) {
        this.currentUser = currentUser;
        this.status = status;
        this.serverLog = serverLog;
        this.reloader = reloader;
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
        boolean control = authentication.getAuthorities().stream()
            .anyMatch(a -> ControlTokenFilter.CONTROL_AUTHORITY.equals(a.getAuthority()));
        Actor actor = control ? new Actor(null, ControlTokenFilter.PRINCIPAL, Via.CLI)
            : new Actor(currentUser.get().name(), null, Via.GUI);
        ScriptId only;
        try {
            only = script == null || script.isBlank() ? null : ScriptId.parse(script);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(new Report(false, List.of(), List.of(), List.of("Unbekanntes Skript: " + script)));
        }
        Report report = reloader.reload(actor, only);
        return ResponseEntity.status(report.ok() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_CONTENT).body(report);
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
