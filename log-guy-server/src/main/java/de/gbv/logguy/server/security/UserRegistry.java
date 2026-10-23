package de.gbv.logguy.server.security;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import de.gbv.logguy.server.config.LogGuyProperties.UserProperties;

/**
 * The configured users. It is the interface for the login, so LDAP can be added later as a second implementation.
 */
public class UserRegistry implements UserDetailsService {

    /** Authority of every logged in user and API token. */
    public static final String USER_AUTHORITY = "ROLE_USER";

    private final AtomicReference<Map<String, UserProperties>> byName = new AtomicReference<>(Map.of());

    /**
     * Creates the registry.
     *
     * @param users the configured users
     */
    public UserRegistry(List<UserProperties> users) {
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
     * Looks up a user with the current rights.
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
        return Optional.of(token == null ? LogGuyUser.of(user.name(), user.grants())
            : new LogGuyUser(user.name(), user.grants(), token.token(), token.apps(), token.mcp()));
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
