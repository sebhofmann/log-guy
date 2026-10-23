package de.gbv.logguy.server.security;

import java.util.List;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Resolves the user of the current request with the rights from the current configuration.
 */
public class CurrentUser {

    private final UserRegistry users;

    /**
     * Creates the resolver.
     *
     * @param users the configured users
     */
    public CurrentUser(UserRegistry users) {
        this.users = users;
    }

    /**
     * The user of the current request.
     *
     * @return the user
     * @throws AccessDeniedException if nobody is logged in or the user was removed from the configuration
     */
    public LogGuyUser get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("not logged in");
        }
        ApiTokenDetails token = authentication.getDetails() instanceof ApiTokenDetails details ? details : null;
        return users.find(authentication.getName(), token)
            .orElseThrow(() -> new AccessDeniedException("unknown user " + authentication.getName()));
    }

    /**
     * Details of an authentication by API token.
     *
     * @param token the name of the token
     * @param apps application patterns the token is limited to, empty for no limit
     */
    public record ApiTokenDetails(String token, List<String> apps) {
    }
}
