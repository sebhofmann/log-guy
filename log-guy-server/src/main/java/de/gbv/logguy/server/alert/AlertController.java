package de.gbv.logguy.server.alert;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import de.gbv.logguy.server.alert.Alert.State;
import de.gbv.logguy.server.audit.Actor;
import de.gbv.logguy.server.audit.Actor.Via;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.AlertProperties;
import de.gbv.logguy.server.pseudonym.Pseudonyms;
import de.gbv.logguy.server.search.SearchException;
import de.gbv.logguy.server.security.CurrentUser;
import de.gbv.logguy.server.security.LogGuyUser;
import de.gbv.logguy.server.web.FlushingBody;
import tools.jackson.databind.json.JsonMapper;

/**
 * The alert API: list, follow as server-sent events, acknowledge, and the state of the rules. Alerts are visible
 * to everybody who may read their application.
 */
@RestController
@RequestMapping("/api/v1/alerts")
@Profile("!cli")
public class AlertController {

    private static final String SESSION_PSEUDONYMS = Pseudonyms.class.getName();

    private final AlertService alerts;

    private final AlertTexts texts;

    private final AlertProperties settings;

    private final LogGuyProperties properties;

    private final CurrentUser currentUser;

    private final JsonMapper json;

    private final HttpServletRequest request;

    /**
     * Creates the controller.
     *
     * @param alerts the alerts
     * @param texts pseudonymizes texts for pseudonymized readers
     * @param properties configuration
     * @param currentUser resolves the user
     * @param json serializes the stream
     * @param request the current request, for the pseudonyms of the session
     */
    public AlertController(AlertService alerts, AlertTexts texts, LogGuyProperties properties,
        CurrentUser currentUser, JsonMapper json, HttpServletRequest request) {
        this.alerts = alerts;
        this.texts = texts;
        this.settings = properties.alerts();
        this.properties = properties;
        this.currentUser = currentUser;
        this.json = json;
        this.request = request;
    }

    /**
     * The alerts of the readable applications.
     *
     * @param state {@code open}, {@code acked}, {@code resolved} or {@code all}
     * @return the alerts, newest first
     */
    @GetMapping
    public List<Alert> list(@RequestParam(defaultValue = "open") String state) {
        LogGuyUser user = currentUser.get();
        Pseudonyms session = session(user);
        return alerts.list(user, state(state)).stream().map(alert -> texts.forUser(user, session, alert)).toList();
    }

    /**
     * Follows the alerts. The first event {@code state} carries the open alerts, every later one the changed
     * alerts, each with the number of open alerts. Comments keep the connection open, it ends after
     * {@code stream-duration} and the client connects again.
     *
     * @param response the servlet response, flushed after each event
     * @return the stream
     */
    @GetMapping("/stream")
    public ResponseEntity<StreamingResponseBody> stream(HttpServletResponse response) {
        LogGuyUser user = currentUser.get();
        Pseudonyms session = session(user);
        StreamingResponseBody body = FlushingBody.of(response, out -> stream(user, session, out));
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).header("Cache-Control", "no-store")
            .header("X-Accel-Buffering", "no").body(body);
    }

    private void stream(LogGuyUser user, Pseudonyms session, OutputStream out) throws IOException {
        long end = System.nanoTime() + settings.streamDuration().toNanos();
        long seq = alerts.seq();
        write(out, user, session, seq, alerts.list(user, State.OPEN));
        try {
            while (true) {
                long left = end - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                Duration wait = settings.streamPing().compareTo(Duration.ofNanos(left)) < 0 ? settings.streamPing()
                    : Duration.ofNanos(left);
                List<Alert> changed = alerts.awaitChanges(seq, wait);
                if (changed.isEmpty()) {
                    out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    continue;
                }
                seq = changed.stream().mapToLong(Alert::seq).max().orElse(seq);
                List<Alert> readable = changed.stream().filter(alert -> user.mayRead(alert.app())).toList();
                if (!readable.isEmpty()) {
                    write(out, user, session, seq, readable);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void write(OutputStream out, LogGuyUser user, Pseudonyms session, long seq, List<Alert> list)
        throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seq", seq);
        data.put("open", alerts.openCount(user));
        data.put("alerts", list.stream().map(alert -> texts.forUser(user, session, alert)).toList());
        out.write(("event: state\ndata: " + json.writeValueAsString(data) + "\n\n")
            .getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Acknowledges an alert.
     *
     * @param id the alert
     * @return the alert
     */
    @PostMapping("/{id}/ack")
    public Alert ack(@PathVariable String id) {
        LogGuyUser user = currentUser.get();
        Actor actor = new Actor(user.name(), user.token(), user.token() == null ? Via.GUI : Via.API);
        Alert alert = alerts.ack(user, actor, id);
        if (alert == null) {
            throw new NoSuchElementException("Meldung " + id + " gibt es nicht.");
        }
        return texts.forUser(user, session(user), alert);
    }

    /**
     * The rules for the readable applications and the errors of rules that cannot be loaded.
     *
     * @return {@code rules} and {@code errors}
     */
    @GetMapping("/rules")
    public AlertService.RuleStatus rules() {
        LogGuyUser user = currentUser.get();
        Set<String> readable = user.readableApps(properties.apps().keySet());
        AlertService.RuleStatus status = alerts.ruleStatus();
        List<AlertService.RuleInfo> visible = status.rules().stream()
            .filter(rule -> user.isAdmin() || rule.apps().stream()
                .anyMatch(pattern -> readable.stream().anyMatch(app -> LogGuyUser.matches(pattern, app))))
            .toList();
        return new AlertService.RuleStatus(visible, user.isAdmin() ? status.errors() : List.of());
    }

    private static State state(String state) {
        if ("all".equalsIgnoreCase(state)) {
            return null;
        }
        try {
            return State.from(state);
        } catch (IllegalArgumentException e) {
            throw new SearchException("Unbekannter Zustand " + state + ", erlaubt sind open, acked, resolved, all.");
        }
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
