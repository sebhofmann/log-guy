package de.gbv.logguy.server.security;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Answers a login of a waiting client address with a redirect to the login page, before the password is checked.
 * The page tells how long the address still has to wait.
 */
public class LoginThrottleFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LogManager.getLogger();

    private final LoginThrottle throttle;

    /**
     * Creates the filter.
     *
     * @param throttle counts failed logins
     */
    public LoginThrottleFilter(LoginThrottle throttle) {
        this.throttle = throttle;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (HttpMethod.POST.matches(request.getMethod())
            && "/login".equals(request.getRequestURI().substring(request.getContextPath().length()))) {
            Optional<Duration> wait = throttle.blocked(request.getRemoteAddr());
            if (wait.isPresent()) {
                LOGGER.debug("login from {} refused, waits {}", request.getRemoteAddr(), wait.get());
                response.sendRedirect(request.getContextPath() + "/login");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
