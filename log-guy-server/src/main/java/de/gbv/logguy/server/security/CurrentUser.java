package de.gbv.logguy.server.security;

import java.util.List;
import java.util.Optional;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import de.gbv.logguy.server.config.LogGuyProperties.TokenKind;
import de.gbv.logguy.server.config.LogGuyProperties.TokenProperties;

/**
 * Resolves the user of the current request with the rights from the current configuration.
 */
public class CurrentUser {

    private final UserRegistry users;

    private final TokenRegistry tokens;

    /**
     * Creates the resolver.
     *
     * @param users the configured users
     * @param tokens the configured tokens, to check them again for long running requests and jobs
     */
    public CurrentUser(UserRegistry users, TokenRegistry tokens) {
        this.users = users;
        this.tokens = tokens;
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
     * Resolves a user again with the rights of the current configuration, for requests and jobs that run longer
     * than a reload may take: a stream of alerts or an archive search. A user with a token keeps the token, which
     * must still be configured for the same user and of the same kind.
     *
     * @param user the user as resolved earlier
     * @return the user with the current rights, empty if the user or the token was removed
     */
    public Optional<LogGuyUser> again(LogGuyUser user) {
        if (user.token() == null) {
            return users.find(user.name(), null);
        }
        TokenProperties token = tokens.findByName(user.token()).orElse(null);
        if (token == null || !user.name().equals(token.user())
            || token.kind() != (user.pseudonymized() ? TokenKind.MCP : TokenKind.API)) {
            return Optional.empty();
        }
        return users.find(user.name(),
            new ApiTokenDetails(token.name(), token.apps(), token.kind() == TokenKind.MCP, token.activate()));
    }

    /**
     * Details of an authentication by API token.
     *
     * @param token the name of the token
     * @param apps application patterns the token is limited to, empty for no limit
     * @param mcp an MCP token: everything pseudonymized
     * @param activate the MCP token may activate parsers and rules
     */
    public record ApiTokenDetails(String token, List<String> apps, boolean mcp, boolean activate) {
    }
}
