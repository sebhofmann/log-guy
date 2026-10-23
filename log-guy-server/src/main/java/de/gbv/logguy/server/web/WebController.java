package de.gbv.logguy.server.web;

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import de.gbv.logguy.server.security.CurrentUser;

/**
 * Pages of the GUI. The pages only contain the frame, the data comes from the API via {@code fetch}.
 */
@Controller
@Profile("!cli")
public class WebController {

    private final CurrentUser currentUser;

    /**
     * Creates the controller.
     *
     * @param currentUser resolves the user
     */
    public WebController(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    /**
     * The login form.
     *
     * @param csrf the CSRF token
     * @param error set after a failed login
     * @param logout set after a logout
     * @return the page
     */
    @GetMapping("/login")
    public ModelAndView login(CsrfToken csrf, @RequestParam(required = false) String error,
        @RequestParam(required = false) String logout) {
        return new ModelAndView("login",
            Map.of("page", new PageContext(null, csrf, null), "error", error != null, "logout", logout != null));
    }

    /**
     * The search with filter bar, log window and timeline.
     *
     * @param csrf the CSRF token
     * @return the page
     */
    @GetMapping("/")
    public ModelAndView search(CsrfToken csrf) {
        return new ModelAndView("search", Map.of("page", new PageContext(currentUser.get(), csrf, "search")));
    }
}
