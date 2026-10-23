package de.gbv.logguy.server.web;

import org.springframework.security.web.csrf.CsrfToken;

import de.gbv.logguy.server.security.LogGuyUser;

/**
 * What every page needs: the user for the navigation, the CSRF token for forms and {@code fetch}, the active menu.
 *
 * @param user the logged in user, {@code null} on the login page
 * @param csrf the CSRF token
 * @param active name of the active menu entry
 */
public record PageContext(LogGuyUser user, CsrfToken csrf, String active) {
}
