package de.gbv.logguy.server.security;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import de.gbv.logguy.server.config.LogGuyProperties.TokenKind;
import de.gbv.logguy.server.config.LogGuyProperties.TokenProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates requests with {@code Authorization: Bearer <token>}. Ingest tokens get the authority
 * {@value #INGEST_AUTHORITY} and an {@link IngestPrincipal}.
 */
public class BearerTokenFilter extends OncePerRequestFilter {

    /** Authority of ingest tokens. */
    public static final String INGEST_AUTHORITY = "ROLE_INGEST";

    private static final Logger LOGGER = LogManager.getLogger();

    private static final String BEARER = "Bearer ";

    private final TokenRegistry tokens;

    /**
     * Creates the filter.
     *
     * @param tokens the configured tokens
     */
    public BearerTokenFilter(TokenRegistry tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            TokenProperties token = tokens.find(header.substring(BEARER.length()).trim()).orElse(null);
            if (token == null) {
                LOGGER.warn("unknown token from {}", request.getRemoteAddr());
            } else if (token.kind() == TokenKind.INGEST) {
                IngestPrincipal principal = new IngestPrincipal(token.name(), token.host(), Set.copyOf(token.apps()));
                UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken
                    .authenticated(principal, null, List.of(new SimpleGrantedAuthority(INGEST_AUTHORITY)));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        chain.doFilter(request, response);
    }
}
