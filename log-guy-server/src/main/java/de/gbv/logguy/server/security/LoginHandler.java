package de.gbv.logguy.server.security;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import de.gbv.logguy.server.audit.Actor;
import de.gbv.logguy.server.audit.Actor.Via;
import de.gbv.logguy.server.audit.AuditLog;

/**
 * Handles the result of the login form: counts failures per client address in the {@link LoginThrottle}, writes
 * {@code login.success}, {@code login.failure} and {@code login.blocked} to the audit log and redirects like the
 * default handlers of Spring Security.
 */
public class LoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    /** Name of the form field with the user name. */
    public static final String USERNAME_PARAMETER = "username";

    private final LoginThrottle throttle;

    private final AuditLog audit;

    private final int maxNameLength;

    private final AuthenticationSuccessHandler success;

    private final AuthenticationFailureHandler failure = new SimpleUrlAuthenticationFailureHandler("/login?error");

    /**
     * Creates the handler.
     *
     * @param throttle counts failed logins
     * @param audit the audit log
     * @param settings the limits of the login form
     */
    public LoginHandler(LoginThrottle throttle, AuditLog audit, LoginProperties settings) {
        this.throttle = throttle;
        this.audit = audit;
        this.maxNameLength = settings.maxNameLength();
        SavedRequestAwareAuthenticationSuccessHandler saved = new SavedRequestAwareAuthenticationSuccessHandler();
        saved.setDefaultTargetUrl("/");
        this.success = saved;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
        Authentication authentication) throws IOException, ServletException {
        String address = request.getRemoteAddr();
        throttle.success(address);
        audit.log(new Actor(authentication.getName(), null, Via.GUI), "login.success", address, Map.of());
        success.onAuthenticationSuccess(request, response, authentication);
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
        AuthenticationException exception) throws IOException, ServletException {
        String address = request.getRemoteAddr();
        Actor actor = new Actor(name(request), null, Via.GUI);
        audit.log(actor, "login.failure", address, Map.of());
        Optional<Duration> wait = throttle.failure(address);
        if (wait.isPresent()) {
            audit.log(actor, "login.blocked", address, Map.of("wait", wait.get().toString()));
        }
        failure.onAuthenticationFailure(request, response, exception);
    }

    // the name is whatever the client sent, cut so a long name cannot blow up the audit log
    private String name(HttpServletRequest request) {
        String name = request.getParameter(USERNAME_PARAMETER);
        if (name == null || name.isEmpty()) {
            return null;
        }
        return name.length() > maxNameLength ? name.substring(0, maxNameLength) : name;
    }
}
