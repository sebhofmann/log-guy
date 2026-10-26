package de.gbv.logguy.server.alert;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.gbv.logguy.server.alert.Alert.State;
import de.gbv.logguy.server.audit.Actor;
import de.gbv.logguy.server.audit.AuditLog;
import de.gbv.logguy.server.config.AppRegistry;
import de.gbv.logguy.server.config.LogGuyProperties.AlertProperties;
import de.gbv.logguy.server.index.EventChecker;
import de.gbv.logguy.server.script.ParsedEvent;
import de.gbv.logguy.server.security.LogGuyUser;

/**
 * Runs the alert rules on new events, keeps the alerts and sends their notifications. A thread evaluates
 * thresholds, silence and cooldowns every {@code tick-interval}.
 */
public class AlertService implements EventChecker, AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger();

    private final AlertProperties settings;

    private final AlertRules rules;

    private final AlertEngine engine;

    private final AlertStore store;

    private final List<Notifier> notifiers;

    private final AppRegistry apps;

    private final AuditLog audit;

    private final Clock clock;

    private final ThreadLocal<AlertRules.Runner> runners;

    private final Set<AlertRules.Runner> allRunners = ConcurrentHashMap.newKeySet();

    private final Object changes = new Object();

    private final Thread ticker;

    private volatile boolean running = true;

    /**
     * Creates the service, loads the alerts and starts the tick thread.
     *
     * @param settings configuration
     * @param rules the active rules
     * @param store the alert file
     * @param notifiers where notifications go
     * @param apps the configured applications, for silence rules and their groups
     * @param audit the audit log, for acknowledgements
     * @param clock the clock
     * @throws IOException if the alert file cannot be read
     */
    public AlertService(AlertProperties settings, AlertRules rules, AlertStore store, List<Notifier> notifiers,
        AppRegistry apps, AuditLog audit, Clock clock) throws IOException {
        this.settings = settings;
        this.rules = rules;
        this.store = store;
        this.notifiers = List.copyOf(notifiers);
        this.apps = apps;
        this.audit = audit;
        this.clock = clock;
        this.engine = new AlertEngine(settings.defaultCooldown(), settings.keep(), settings.maxText(),
            clock.instant());
        List<Alert> loaded = store.load();
        engine.restore(loaded);
        LOGGER.info("{} alerts loaded, {} active", loaded.size(), loaded.stream().filter(Alert::active).count());
        this.runners = ThreadLocal.withInitial(() -> {
            AlertRules.Runner runner = rules.runner();
            allRunners.add(runner);
            return runner;
        });
        this.ticker = Thread.ofPlatform().name("alerts").daemon().start(this::run);
    }

    @Override
    public Set<String> check(String app, String host, String id, Instant ts, ParsedEvent event, Set<String> skip) {
        Instant now = clock.instant();
        if (ts == null || ts.isBefore(now.minus(settings.maxAge()))) {
            return Set.of();
        }
        engine.seen(app, ts);
        Map<String, Object> map = eventMap(app, host, id, ts, event);
        AlertRules.Runner runner = runners.get();
        Set<String> matched = new HashSet<>();
        for (RuleDefinition rule : runner.check(app, map)) {
            matched.add(rule.name());
            if (!skip.contains(rule.name())) {
                engine.match(rule, app, id, ts, map, now, runner::message);
            }
        }
        return matched;
    }

    /**
     * The event the way {@code check(event)} sees it: the fields plus {@code app}, {@code host}, {@code id},
     * {@code ts} and {@code message}.
     *
     * @param app the application
     * @param host the host
     * @param id id of the event
     * @param ts time of the event
     * @param event the parsed event
     * @return the event
     */
    public static Map<String, Object> eventMap(String app, String host, String id, Instant ts, ParsedEvent event) {
        Map<String, Object> map = new LinkedHashMap<>(event.fields());
        map.put("app", app);
        map.put("host", host);
        map.put("id", id);
        map.put("ts", ts == null ? null : ts.toString());
        map.put("message", event.message());
        return map;
    }

    private void run() {
        AlertRules.Runner runner = rules.runner();
        try {
            while (running) {
                try {
                    Thread.sleep(settings.tickInterval());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                try {
                    tick(runner);
                } catch (RuntimeException e) {
                    LOGGER.error("alert tick failed", e);
                }
            }
            tick(runner);
        } finally {
            runner.close();
        }
    }

    private void tick(AlertRules.Runner runner) {
        engine.tick(clock.instant(), rules.rules(), apps.names(), apps.groups(), runner::message);
        flush();
    }

    private void flush() {
        AlertEngine.Changes drained = engine.drain();
        if (!drained.alerts().isEmpty()) {
            try {
                store.append(drained.alerts());
            } catch (IOException e) {
                LOGGER.error("cannot write {} alert changes", drained.alerts().size(), e);
            }
            synchronized (changes) {
                changes.notifyAll();
            }
        }
        for (AlertEngine.Notification notification : drained.notifications()) {
            for (Notifier notifier : notifiers) {
                try {
                    notifier.send(notification);
                } catch (RuntimeException e) {
                    LOGGER.error("notification of alert {} failed", notification.alert().id(), e);
                }
            }
        }
    }

    /**
     * The alerts a user may see: those of the applications the user may read, in plain text or pseudonymized.
     * The texts are pseudonymized by {@link AlertTexts}.
     *
     * @param user the user
     * @param state only alerts in this state, {@code null} for all
     * @return the alerts, newest first
     */
    public List<Alert> list(LogGuyUser user, State state) {
        return engine.read(all -> all.stream().filter(alert -> maySee(user, alert))
            .filter(alert -> state == null || alert.state() == state)
            .sorted(Comparator.comparing(Alert::opened).reversed()).toList());
    }

    /**
     * Whether a user may see an alert: if the user may read its application, in plain text or pseudonymized.
     *
     * @param user the user
     * @param alert the alert
     * @return true if the alert is visible
     */
    public static boolean maySee(LogGuyUser user, Alert alert) {
        return user.asPseudonymized().mayRead(alert.app());
    }

    /**
     * Number of open alerts a user may see.
     *
     * @param user the user
     * @return the count
     */
    public long openCount(LogGuyUser user) {
        return engine.read(all -> all.stream().filter(alert -> alert.state() == State.OPEN)
            .filter(alert -> maySee(user, alert)).count());
    }

    /**
     * Acknowledges an alert. Everybody who may see an alert may acknowledge its alerts.
     *
     * @param user the user
     * @param actor who acknowledges, for the audit log
     * @param id the alert
     * @return the alert, {@code null} if there is none or the user may not see it
     */
    public Alert ack(LogGuyUser user, Actor actor, String id) {
        Alert alert = engine.alert(id);
        if (alert == null || !maySee(user, alert)) {
            return null;
        }
        if (alert.state() != State.OPEN) {
            return alert;
        }
        Alert acked = engine.ack(id, user.name(), clock.instant());
        audit.log(actor, "alert.ack", alert.app(), Map.of("alert", id, "rule", alert.rule()));
        flush();
        return acked;
    }

    /**
     * The change counter of the alerts.
     *
     * @return the counter of the last change
     */
    public long seq() {
        return engine.seq();
    }

    /**
     * Waits until alerts change.
     *
     * @param after the counter of the last change seen
     * @param timeout how long to wait at most
     * @return the alerts changed after {@code after}, empty after the timeout
     * @throws InterruptedException if the thread is interrupted
     */
    public List<Alert> awaitChanges(long after, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (changes) {
            while (engine.seq() <= after) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return List.of();
                }
                Duration wait = Duration.ofNanos(left);
                changes.wait(Math.max(1, wait.toMillis()));
            }
        }
        return engine.changedAfter(after);
    }

    /**
     * The rules with their state.
     *
     * @return the loaded rules, the errors of rules that cannot be loaded and the errors of calls
     */
    public RuleStatus ruleStatus() {
        List<RuleInfo> infos = new ArrayList<>();
        Map<String, Long> errors = rules.checkErrors();
        Map<String, String> last = rules.lastErrors();
        for (RuleDefinition rule : rules.rules()) {
            infos.add(RuleInfo.of(rule, errors.getOrDefault(rule.name(), 0L), last.get(rule.name())));
        }
        return new RuleStatus(infos, rules.errors());
    }

    /**
     * A loaded rule.
     *
     * @param name name of the script
     * @param version short hash of the code
     * @param type kind of the rule
     * @param apps application patterns
     * @param cooldown cooldown, e.g. {@code 15m}
     * @param targets targets
     * @param count events of a threshold
     * @param within time window of a threshold
     * @param per field a threshold is counted by besides the application
     * @param absentFor silence after which the rule fires
     * @param errors failed calls since the start
     * @param lastError the last error
     */
    public record RuleInfo(String name, String version, RuleDefinition.Type type, List<String> apps,
        String cooldown, List<String> targets, Integer count, String within, String per, String absentFor,
        long errors, String lastError) {

        static RuleInfo of(RuleDefinition rule, long errors, String lastError) {
            return new RuleInfo(rule.name(), rule.version(), rule.type(), rule.apps(),
                rule.cooldown() == null ? null : RuleDefinition.format(rule.cooldown()), rule.targets(),
                rule.type() == RuleDefinition.Type.THRESHOLD ? rule.count() : null,
                rule.within() == null ? null : RuleDefinition.format(rule.within()), rule.per(),
                rule.absentFor() == null ? null : RuleDefinition.format(rule.absentFor()), errors, lastError);
        }
    }

    /**
     * The state of the rules.
     *
     * @param rules the loaded rules
     * @param errors errors of rules that cannot be loaded
     */
    public record RuleStatus(List<RuleInfo> rules, List<String> errors) {
    }

    @Override
    public void close() throws IOException, InterruptedException {
        running = false;
        ticker.interrupt();
        ticker.join();
        allRunners.forEach(AlertRules.Runner::close);
        store.close();
    }
}
