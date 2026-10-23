package de.gbv.logguy.server.security;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import de.gbv.logguy.server.config.LogGuyProperties.GrantProperties;
import de.gbv.logguy.server.config.LogGuyProperties.Right;

/**
 * A user with the rights per application, as seen by a request.
 *
 * @param name login name
 * @param grants rights per application pattern
 * @param token name of the token used for the request, {@code null} for a GUI session
 * @param tokenApps application patterns the token is limited to, empty for no limit
 */
public record LogGuyUser(String name, List<GrantProperties> grants, String token, List<String> tokenApps) {

    /**
     * Normalizes a missing token limit.
     */
    public LogGuyUser {
        tokenApps = tokenApps == null ? List.of() : List.copyOf(tokenApps);
    }

    /**
     * A user of a GUI session, without token.
     *
     * @param name login name
     * @param grants rights per application pattern
     * @return the user
     */
    public static LogGuyUser of(String name, List<GrantProperties> grants) {
        return new LogGuyUser(name, grants, null, List.of());
    }

    /**
     * Whether the user has a right for an application. {@link Right#ADMIN} implies all rights. A token limited to
     * some applications has no rights for others.
     *
     * @param right the right
     * @param app the application
     * @return true if one grant matches
     */
    public boolean may(Right right, String app) {
        if (!tokenApps.isEmpty() && tokenApps.stream().noneMatch(pattern -> matches(pattern, app))) {
            return false;
        }
        for (GrantProperties grant : grants) {
            if ((grant.rights().contains(right) || grant.rights().contains(Right.ADMIN))
                && grant.apps().stream().anyMatch(pattern -> matches(pattern, app))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The applications for which the user has a right.
     *
     * @param right the right
     * @param known all configured applications
     * @return the allowed applications, sorted
     */
    public Set<String> apps(Right right, Collection<String> known) {
        Set<String> allowed = new TreeSet<>();
        for (String app : known) {
            if (may(right, app)) {
                allowed.add(app);
            }
        }
        return allowed;
    }

    /**
     * Whether the user is administrator of log-guy itself, i.e. has {@link Right#ADMIN} for all applications.
     *
     * @return true for {@code apps: ["*"]} with {@code admin}, unless a token limits the applications
     */
    public boolean isAdmin() {
        return (tokenApps.isEmpty() || tokenApps.contains("*"))
            && grants.stream().anyMatch(g -> g.rights().contains(Right.ADMIN) && g.apps().contains("*"));
    }

    /**
     * Matches an application name against a pattern where {@code *} stands for any characters.
     *
     * @param pattern the pattern, e.g. {@code digibib*}
     * @param app the application name
     * @return true if it matches
     */
    public static boolean matches(String pattern, String app) {
        if (!pattern.contains("*")) {
            return pattern.equals(app);
        }
        StringBuilder regex = new StringBuilder();
        for (String part : pattern.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return app.matches(regex.toString());
    }
}
