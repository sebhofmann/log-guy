package de.gbv.logguy.server.security;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import de.gbv.logguy.server.config.AppGroups;
import de.gbv.logguy.server.config.LogGuyProperties.GrantProperties;
import de.gbv.logguy.server.config.LogGuyProperties.UserProperties;

/**
 * The configured users. It is the interface for the login, so LDAP can be added later as a second implementation.
 */
public class UserRegistry implements UserDetailsService {

    /** Authority of every logged in user and API token. */
    public static final String USER_AUTHORITY = "ROLE_USER";

    private final AtomicReference<Map<String, UserProperties>> byName = new AtomicReference<>(Map.of());

    private final Supplier<AppGroups> groups;

    /**
     * Creates the registry.
     *
     * @param users the configured users
     * @param groups the application groups as they are now, replaced by their members in grants and tokens
     */
    public UserRegistry(List<UserProperties> users, Supplier<AppGroups> groups) {
        this.groups = groups;
        update(users);
    }

    /**
     * Replaces all users, e.g. after a reload.
     *
     * @param users the configured users
     */
    public final void update(List<UserProperties> users) {
        Map<String, UserProperties> map = new HashMap<>();
        for (UserProperties user : users) {
            map.put(user.name(), user);
        }
        byName.set(Map.copyOf(map));
    }

    /**
     * Looks up a user with the current rights. Application groups in grants and tokens are replaced by their
     * members as they are now, so a reload that changes a group changes the rights with the next request.
     *
     * @param name the login name
     * @param token the token used, {@code null} for a GUI session
     * @return the user, empty if not configured (any more)
     */
    public Optional<LogGuyUser> find(String name, CurrentUser.ApiTokenDetails token) {
        UserProperties user = byName.get().get(name);
        if (user == null) {
            return Optional.empty();
        }
        AppGroups now = groups.get();
        List<GrantProperties> grants = user.grants().stream()
            .map(grant -> new GrantProperties(now.expand(grant.apps()), grant.rights())).toList();
        return Optional.of(token == null ? LogGuyUser.of(user.name(), grants)
            : new LogGuyUser(user.name(), grants, token.token(), now.expand(token.apps()), token.mcp()));
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        UserProperties user = byName.get().get(username);
        if (user == null || user.passwordHash() == null) {
            throw new UsernameNotFoundException("unknown user");
        }
        return User.withUsername(user.name()).password(user.passwordHash()).authorities(USER_AUTHORITY).build();
    }
}
