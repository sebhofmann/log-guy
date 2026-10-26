package de.gbv.logguy.server.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.security.LogGuyUser;

/**
 * The application groups of one state of the configuration, e.g. {@code mir} for all MIR applications. Scripts and
 * grants name a group as {@code @mir} where they otherwise name applications. A reload creates a new instance, so
 * a cache that depends on the groups is valid as long as the instance stays the same.
 *
 * @param byApp the groups of every application that has some
 */
public record AppGroups(Map<String, Set<String>> byApp) {

    /** Prefix of a group in a list of application patterns. */
    public static final String PREFIX = "@";

    /** No groups at all. */
    public static final AppGroups NONE = new AppGroups(Map.of());

    /**
     * Copies the map.
     */
    public AppGroups {
        byApp = Map.copyOf(byApp);
    }

    /**
     * The groups of the configured applications.
     *
     * @param apps the applications with their settings
     * @return the groups
     */
    public static AppGroups of(Map<String, AppProperties> apps) {
        Map<String, Set<String>> byApp = new TreeMap<>();
        apps.forEach((app, settings) -> {
            if (settings != null && !settings.groups().isEmpty()) {
                byApp.put(app, Set.copyOf(settings.groups()));
            }
        });
        return new AppGroups(byApp);
    }

    /**
     * Whether a pattern names a group.
     *
     * @param pattern the pattern
     * @return true for {@code @name}
     */
    public static boolean isGroup(String pattern) {
        return pattern.startsWith(PREFIX);
    }

    /**
     * Matches an application against a name, a pattern with {@code *} or a group like {@code @mir}.
     *
     * @param pattern the pattern
     * @param app the application
     * @return true if it matches
     */
    public boolean matches(String pattern, String app) {
        if (isGroup(pattern)) {
            return byApp.getOrDefault(app, Set.of()).contains(pattern.substring(PREFIX.length()));
        }
        return LogGuyUser.matches(pattern, app);
    }

    /**
     * Whether one of the patterns matches an application.
     *
     * @param patterns the patterns
     * @param app the application
     * @return true if one matches
     */
    public boolean matchesAny(Collection<String> patterns, String app) {
        return patterns.stream().anyMatch(pattern -> matches(pattern, app));
    }

    /**
     * The applications of a group.
     *
     * @param group the group without prefix
     * @return the applications, sorted
     */
    public Set<String> members(String group) {
        Set<String> members = new TreeSet<>();
        byApp.forEach((app, groups) -> {
            if (groups.contains(group)) {
                members.add(app);
            }
        });
        return members;
    }

    /**
     * All groups.
     *
     * @return the group names without prefix, sorted
     */
    public Set<String> names() {
        Set<String> names = new TreeSet<>();
        byApp.values().forEach(names::addAll);
        return names;
    }

    /**
     * Adds the applications of the groups in a list of patterns, so the result can be matched without knowing the
     * groups. The group itself stays in the list: it matches no application name, but a list of only empty groups
     * does not become empty, which would mean no limit for a token.
     *
     * @param patterns the patterns, may be {@code null}
     * @return the patterns with the members added for the groups
     */
    public List<String> expand(List<String> patterns) {
        if (patterns == null || patterns.stream().noneMatch(AppGroups::isGroup)) {
            return patterns;
        }
        Set<String> expanded = new TreeSet<>();
        for (String pattern : patterns) {
            if (isGroup(pattern)) {
                expanded.addAll(members(pattern.substring(PREFIX.length())));
            }
            expanded.add(pattern);
        }
        return List.copyOf(expanded);
    }
}
