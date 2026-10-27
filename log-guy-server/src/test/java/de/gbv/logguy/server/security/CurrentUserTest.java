package de.gbv.logguy.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.gbv.logguy.server.config.AppGroups;
import de.gbv.logguy.server.config.LogGuyProperties.GrantProperties;
import de.gbv.logguy.server.config.LogGuyProperties.Right;
import de.gbv.logguy.server.config.LogGuyProperties.TokenKind;
import de.gbv.logguy.server.config.LogGuyProperties.TokenProperties;
import de.gbv.logguy.server.config.LogGuyProperties.UserProperties;

class CurrentUserTest {

    private static final List<String> APPS = List.of("digibib", "rolf");

    private final UserRegistry users = new UserRegistry(List.of(anna(Right.READ)), () -> AppGroups.NONE);

    private final TokenRegistry tokens = new TokenRegistry(List.of(token("anna-api", TokenKind.API, "anna"),
        token("anna-mcp", TokenKind.MCP, "anna")));

    private final CurrentUser currentUser = new CurrentUser(users, tokens);

    private static UserProperties anna(Right right) {
        return new UserProperties("anna", null, List.of(new GrantProperties(List.of("digibib"), Set.of(right))));
    }

    private static TokenProperties token(String name, TokenKind kind, String user) {
        return new TokenProperties(name, kind, null, List.of(), user, TokenRegistry.hash(name), false);
    }

    @Test
    void sessionGetsTheRightsOfTheReload() {
        LogGuyUser before = users.find("anna", null).orElseThrow();
        assertEquals(Optional.of(before), currentUser.again(before));
        users.update(List.of(anna(Right.READ_PSEUDONYMIZED)));
        LogGuyUser after = currentUser.again(before).orElseThrow();
        assertEquals(Set.of(), after.readableApps(APPS));
        assertEquals(Set.of("digibib"), after.asPseudonymized().readableApps(APPS));
        users.update(List.of());
        assertTrue(currentUser.again(before).isEmpty());
    }

    @Test
    void tokenMustStillBeConfiguredForTheSameUser() {
        LogGuyUser api = users.find("anna", new CurrentUser.ApiTokenDetails("anna-api", List.of(), false, false))
            .orElseThrow();
        LogGuyUser mcp = users.find("anna", new CurrentUser.ApiTokenDetails("anna-mcp", List.of(), true, false))
            .orElseThrow();
        assertEquals(Optional.of(api), currentUser.again(api));
        assertEquals(Optional.of(mcp), currentUser.again(mcp));
        tokens.update(List.of(token("anna-api", TokenKind.API, "bert"), token("anna-mcp", TokenKind.API, "anna")));
        assertTrue(currentUser.again(api).isEmpty());
        assertTrue(currentUser.again(mcp).isEmpty());
        tokens.update(List.of(token("other", TokenKind.API, "anna")));
        assertTrue(currentUser.again(api).isEmpty());
    }
}
