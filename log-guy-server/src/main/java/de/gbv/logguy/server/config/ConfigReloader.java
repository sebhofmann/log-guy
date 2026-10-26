package de.gbv.logguy.server.config;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import de.gbv.logguy.server.audit.Actor;
import de.gbv.logguy.server.audit.AuditLog;
import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.script.ScriptService;
import de.gbv.logguy.server.script.ScriptService.ReloadResult;
import de.gbv.logguy.server.script.ScriptStore.ScriptId;
import de.gbv.logguy.server.security.TokenRegistry;
import de.gbv.logguy.server.security.UserRegistry;

/**
 * Reads {@code application.yml} and the scripts again while the server runs, like {@code nginx -s reload}. Everything
 * is checked first, nothing changes if there is an error. Applications, users and tokens take effect at once, all
 * other settings only after a restart.
 */
public class ConfigReloader {

    private static final Logger LOGGER = LogManager.getLogger();

    /** Settings that a reload takes over. */
    private static final Set<String> LIVE = Set.of("apps", "users", "tokens");

    private static final String CONFIG_SOURCE_PREFIX = "Config resource";

    private static final String ATTACHED_SOURCE = "configurationProperties";

    private final ConfigurableEnvironment environment;

    private final LogGuyProperties started;

    private final AppRegistry apps;

    private final TokenRegistry tokens;

    private final UserRegistry users;

    private final ScriptService scripts;

    private final AuditLog audit;

    /**
     * Creates the reloader.
     *
     * @param environment the environment of the running server, for command line and default properties
     * @param started the configuration the server started with
     * @param apps the applications to update
     * @param tokens the tokens to update
     * @param users the users to update
     * @param scripts the scripts to reload
     * @param audit the audit log
     */
    public ConfigReloader(ConfigurableEnvironment environment, LogGuyProperties started, AppRegistry apps,
        TokenRegistry tokens,
        UserRegistry users, ScriptService scripts, AuditLog audit) {
        this.environment = environment;
        this.started = started;
        this.apps = apps;
        this.tokens = tokens;
        this.users = users;
        this.scripts = scripts;
        this.audit = audit;
    }

    /**
     * Reads configuration and scripts again.
     *
     * @param actor who triggered the reload
     * @param only only this script and no configuration, {@code null} for everything
     * @return what changed and the errors
     * @throws IOException if the script directory cannot be read
     */
    public synchronized Report reload(Actor actor, ScriptId only) throws IOException {
        if (only != null) {
            ReloadResult result = scripts.reload(only);
            Report report = new Report(result.errors().isEmpty(), result.changed(), List.of(), List.of(),
                result.errors());
            log(actor, only.toString(), report);
            return report;
        }
        LogGuyProperties properties;
        try {
            properties = read();
        } catch (BindException | IllegalStateException e) {
            Report report = Report.failed(List.of(), List.of("Konfiguration: " + message(e)));
            log(actor, "*", report);
            return report;
        }
        List<String> errors = ConfigChecker.check(properties);
        if (!errors.isEmpty()) {
            Report report = Report.failed(List.of(), errors);
            log(actor, "*", report);
            return report;
        }
        List<String> changedApps = changedApps(apps.apps(), properties.apps());
        ReloadResult result = scripts.reload(null, properties.apps());
        if (!result.errors().isEmpty()) {
            Report report = Report.failed(result.changed(), result.errors());
            log(actor, "*", report);
            return report;
        }
        tokens.update(properties.tokens());
        users.update(properties.users());
        Report report = new Report(true, result.changed(), changedApps, restartNeeded(started, properties),
            List.of());
        log(actor, "*", report);
        return report;
    }

    /**
     * The applications that a reload adds, removes or changes.
     *
     * @param before the applications now
     * @param after the applications of the new configuration
     * @return e.g. {@code +rolf}, {@code -old}, {@code ~digibib}, sorted by name
     */
    static List<String> changedApps(Map<String, AppProperties> before, Map<String, AppProperties> after) {
        Set<String> names = new TreeSet<>(before.keySet());
        names.addAll(after.keySet());
        List<String> changed = new ArrayList<>();
        for (String name : names) {
            if (!before.containsKey(name)) {
                changed.add("+" + name);
            } else if (!after.containsKey(name)) {
                changed.add("-" + name);
            } else if (!Objects.equals(before.get(name), after.get(name))) {
                changed.add("~" + name);
            }
        }
        return changed;
    }

    /**
     * Reads and binds the configuration as at the start of the server.
     *
     * @return the configuration
     */
    LogGuyProperties read() {
        StandardEnvironment fresh = new StandardEnvironment();
        fresh.getPropertySources().forEach(source -> fresh.getPropertySources().remove(source.getName()));
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!source.getName().startsWith(CONFIG_SOURCE_PREFIX) && !ATTACHED_SOURCE.equals(source.getName())) {
                fresh.getPropertySources().addLast(source);
            }
        }
        fresh.setActiveProfiles(environment.getActiveProfiles());
        ConfigDataEnvironmentPostProcessor.applyTo(fresh);
        return Binder.get(fresh).bind("log-guy", LogGuyProperties.class)
            .orElseThrow(() -> new IllegalStateException("log-guy is not configured"));
    }

    /**
     * The settings that differ but only take effect after a restart.
     *
     * @param before the configuration the server runs with
     * @param after the new configuration
     * @return the names of the settings, e.g. {@code log-guy.apps}
     */
    static List<String> restartNeeded(LogGuyProperties before, LogGuyProperties after) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : LogGuyProperties.class.getRecordComponents()) {
            if (LIVE.contains(component.getName())) {
                continue;
            }
            try {
                Object a = component.getAccessor().invoke(before);
                Object b = component.getAccessor().invoke(after);
                if (!Objects.equals(a, b)) {
                    names.add("log-guy." + kebab(component.getName()));
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return names;
    }

    private void log(Actor actor, String target, Report report) {
        if (report.ok()) {
            LOGGER.info("reload by {}: scripts {}, applications {}, restart needed for {}", actor.label(),
                report.scripts(), report.apps(), report.restartNeeded());
        } else {
            LOGGER.warn("reload by {} failed: {}", actor.label(), report.errors());
        }
        audit.log(actor, "reload", target, Map.of("ok", report.ok(), "scripts", report.scripts(), "apps",
            report.apps(), "restartNeeded", report.restartNeeded(), "errors", report.errors()));
    }

    private static String message(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause == e ? e.getMessage() : e.getMessage() + ": " + cause.getMessage();
    }

    private static String kebab(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    /**
     * Result of a reload.
     *
     * @param ok whether the new state was taken over
     * @param scripts the scripts that changed on disk
     * @param apps the applications added ({@code +}), removed ({@code -}) or changed ({@code ~})
     * @param restartNeeded settings that changed but need a restart
     * @param errors the errors, nothing was taken over if not empty
     */
    public record Report(boolean ok, List<String> scripts, List<String> apps, List<String> restartNeeded,
        List<String> errors) {

        /**
         * A reload that took over nothing.
         *
         * @param scripts the scripts that changed on disk
         * @param errors the errors
         * @return the report
         */
        public static Report failed(List<String> scripts, List<String> errors) {
            return new Report(false, scripts, List.of(), List.of(), errors);
        }
    }
}
