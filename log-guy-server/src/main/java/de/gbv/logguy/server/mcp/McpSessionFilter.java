package de.gbv.logguy.server.mcp;

import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import de.gbv.logguy.server.security.CurrentUser.ApiTokenDetails;
import io.modelcontextprotocol.spec.HttpHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Applies the session limits of {@link McpSessions} when a client initializes an MCP session. A POST without
 * {@code Mcp-Session-Id} is the only request that opens a session in the transport, so the filter reserves a session
 * for the token before the transport sees the request and takes over the id the transport hands out. Runs after the
 * security filter chain, which has already checked the MCP token.
 */
public class McpSessionFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LogManager.getLogger();

    private final McpSessions sessions;

    /**
     * Creates the filter.
     *
     * @param sessions the sessions
     */
    public McpSessionFilter(McpSessions sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!HttpMethod.POST.matches(request.getMethod()) || request.getHeader(HttpHeaders.MCP_SESSION_ID) != null
            || authentication == null || !(authentication.getDetails() instanceof ApiTokenDetails details)) {
            chain.doFilter(request, response);
            return;
        }
        String reservation;
        try {
            reservation = sessions.reserve(authentication.getName(), details.token());
        } catch (AccessDeniedException e) {
            LOGGER.warn("MCP session of token {} from {} refused: {}", details.token(), request.getRemoteAddr(),
                e.getMessage());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.TEXT_PLAIN_VALUE);
            response.getWriter().write(e.getMessage());
            return;
        }
        String id = null;
        try {
            chain.doFilter(request, response);
            id = response.getHeader(HttpHeaders.MCP_SESSION_ID);
        } finally {
            if (id == null) {
                sessions.cancel(reservation);
            } else {
                sessions.open(reservation, id);
            }
        }
    }
}
