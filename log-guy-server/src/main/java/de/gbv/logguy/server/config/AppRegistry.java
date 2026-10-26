package de.gbv.logguy.server.config;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;

/**
 * The configured applications as they are now. A reload replaces them, so everything that needs the applications at
 * run time asks here and not {@link LogGuyProperties#apps()}, which keeps the state of the start.
 */
public class AppRegistry {

    private final AtomicReference<Map<String, AppProperties>> apps = new AtomicReference<>(Map.of());

    /**
     * Creates the registry with the applications of the start.
     *
     * @param properties configuration
     */
    public AppRegistry(LogGuyProperties properties) {
        update(properties.apps());
    }

    /**
     * Replaces all applications, e.g. after a reload.
     *
     * @param configured the configured applications
     */
    public final void update(Map<String, AppProperties> configured) {
        apps.set(Collections.unmodifiableSortedMap(new TreeMap<>(configured)));
    }

    /**
     * The applications with their settings.
     *
     * @return name to settings, sorted by name
     */
    public Map<String, AppProperties> apps() {
        return apps.get();
    }

    /**
     * The names of the applications.
     *
     * @return the names, sorted
     */
    public Set<String> names() {
        return apps.get().keySet();
    }

    /**
     * Whether an application is configured.
     *
     * @param app the application
     * @return true if it is configured
     */
    public boolean contains(String app) {
        return apps.get().containsKey(app);
    }

    /**
     * The settings of an application.
     *
     * @param app the application
     * @return the settings, {@code null} if the application is not configured
     */
    public AppProperties get(String app) {
        return apps.get().get(app);
    }
}
