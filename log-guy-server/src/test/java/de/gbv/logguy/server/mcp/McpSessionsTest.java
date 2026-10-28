package de.gbv.logguy.server.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import de.gbv.logguy.server.pseudonym.Pseudonyms;
import de.gbv.logguy.server.security.CurrentUser.ApiTokenDetails;
import de.gbv.logguy.server.security.LogGuyUser;
import io.modelcontextprotocol.spec.HttpHeaders;
import jakarta.servlet.http.HttpServletResponse;

class McpSessionsTest {

    private static final Instant T0 = Instant.parse("2026-10-28T10:00:00Z");

    private static final Duration IDLE = Duration.ofHours(2);

    private static LogGuyUser user(String name, String token) {
        return new LogGuyUser(name, List.of(), token, List.of(), true);
    }

    @Test
    void limitsTheSessionsOfOneTokenWithoutBlockingOthers() {
        McpSessions sessions = new McpSessions(IDLE, 100, 2, Clock.fixed(T0, ZoneOffset.UTC));
        sessions.open(sessions.reserve("anna", "anna-mcp"), "s1");
        sessions.open(sessions.reserve("anna", "anna-mcp"), "s2");

        AccessDeniedException e = assertThrows(AccessDeniedException.class,
            () -> sessions.reserve("anna", "anna-mcp"));
        assertEquals("too many MCP sessions for token anna-mcp", e.getMessage());
        assertThrows(AccessDeniedException.class, () -> sessions.get("s3", user("anna", "anna-mcp")));

        sessions.open(sessions.reserve("root", "root-mcp"), "r1");
        assertEquals(3, sessions.size());
    }

    @Test
    void limitsAllSessions() {
        McpSessions sessions = new McpSessions(IDLE, 2, 2, Clock.fixed(T0, ZoneOffset.UTC));
        sessions.open(sessions.reserve("anna", "anna-mcp"), "s1");
        sessions.open(sessions.reserve("root", "root-mcp"), "r1");

        AccessDeniedException e = assertThrows(AccessDeniedException.class,
            () -> sessions.reserve("sam", "sam-mcp"));
        assertEquals("too many MCP sessions", e.getMessage());
    }

    @Test
    void freesTheSlotsOfExpiredAndCancelledSessions() {
        MovingClock clock = new MovingClock(T0);
        McpSessions sessions = new McpSessions(IDLE, 100, 1, clock);
        sessions.open(sessions.reserve("anna", "anna-mcp"), "s1");
        assertThrows(AccessDeniedException.class, () -> sessions.reserve("anna", "anna-mcp"));

        clock.now = T0.plus(IDLE).plusSeconds(1);
        String reservation = sessions.reserve("anna", "anna-mcp");
        sessions.cancel(reservation);
        sessions.open(sessions.reserve("anna", "anna-mcp"), "s2");
        assertEquals(1, sessions.size());
    }

    /**
     * A session opened just now is not dropped by an expiry that runs at the same moment, its pseudonyms stay.
     */
    @Test
    void keepsANewSessionThroughAnExpiryAtTheSameTime() {
        McpSessions sessions = new McpSessions(IDLE, 100, 10, Clock.fixed(T0, ZoneOffset.UTC));
        LogGuyUser anna = user("anna", "anna-mcp");
        sessions.open(sessions.reserve("anna", "anna-mcp"), "s1");
        sessions.expire(T0);
        Pseudonyms first = sessions.get("s1", anna);
        sessions.expire(T0);

        assertSame(first, sessions.get("s1", anna));
        assertNotSame(first, sessions.get("s2", anna));
    }

    @Test
    void sessionBelongsToItsToken() {
        McpSessions sessions = new McpSessions(IDLE, 100, 10, Clock.fixed(T0, ZoneOffset.UTC));
        sessions.open(sessions.reserve("anna", "anna-mcp"), "s1");

        assertThrows(AccessDeniedException.class, () -> sessions.get("s1", user("anna", "anna-mcp2")));
    }

    @Test
    void filterRefusesTheSessionOfATokenAtItsLimit() throws Exception {
        McpSessions sessions = new McpSessions(IDLE, 100, 1, Clock.fixed(T0, ZoneOffset.UTC));
        McpSessionFilter filter = new McpSessionFilter(sessions);
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated("anna",
            null, List.of());
        authentication.setDetails(new ApiTokenDetails("anna-mcp", List.of(), true, false));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            MockHttpServletResponse opened = initialize(filter, "s1");
            assertEquals(HttpServletResponse.SC_OK, opened.getStatus());
            assertSame(sessions.get("s1", user("anna", "anna-mcp")), sessions.get("s1", user("anna", "anna-mcp")));

            MockHttpServletResponse refused = initialize(filter, "s2");
            assertEquals(429, refused.getStatus());
            assertEquals("too many MCP sessions for token anna-mcp", refused.getContentAsString());
            assertEquals(1, sessions.size());

            MockHttpServletRequest call = new MockHttpServletRequest("POST", "/mcp");
            call.addHeader(HttpHeaders.MCP_SESSION_ID, "s1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(call, response, new MockFilterChain());
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void filterDropsTheReservationIfTheTransportOpensNoSession() throws Exception {
        McpSessions sessions = new McpSessions(IDLE, 100, 1, Clock.fixed(T0, ZoneOffset.UTC));
        McpSessionFilter filter = new McpSessionFilter(sessions);
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated("anna",
            null, List.of());
        authentication.setDetails(new ApiTokenDetails("anna-mcp", List.of(), true, false));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            initialize(filter, null);
            assertEquals(0, sessions.size());
            assertEquals(HttpServletResponse.SC_OK, initialize(filter, "s1").getStatus());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static MockHttpServletResponse initialize(McpSessionFilter filter, String id) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/mcp"), response, (req, res) -> {
            if (id != null) {
                ((HttpServletResponse) res).setHeader(HttpHeaders.MCP_SESSION_ID, id);
            }
        });
        return response;
    }

    private static final class MovingClock extends Clock {

        private Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
