package de.gbv.logguy.server.security;

import java.io.IOException;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates {@code log-guy reload} by the control token.
 */
public class ControlTokenFilter extends OncePerRequestFilter {

    /** Authority of the control token. */
    public static final String CONTROL_AUTHORITY = "ROLE_CONTROL";

    /** Name of the principal and of the token in the audit log. */
    public static final String PRINCIPAL = "control";

    private static final Logger LOGGER = LogManager.getLogger();

    private static final String BEARER = "Bearer ";

    private final ControlToken token;

    /**
     * Creates the filter.
     *
     * @param token the control token
     */
    public ControlTokenFilter(ControlToken token) {
        this.token = token;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER) && token.matches(header.substring(BEARER.length()))) {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken
                .authenticated(PRINCIPAL, null, List.of(new SimpleGrantedAuthority(CONTROL_AUTHORITY))));
        } else {
            LOGGER.warn("wrong control token from {}", request.getRemoteAddr());
        }
        chain.doFilter(request, response);
    }
}
