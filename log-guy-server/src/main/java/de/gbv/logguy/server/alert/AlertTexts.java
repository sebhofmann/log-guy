package de.gbv.logguy.server.alert;

import de.gbv.logguy.server.pseudonym.PseudonymService;
import de.gbv.logguy.server.pseudonym.Pseudonyms;
import de.gbv.logguy.server.search.PseudonymizedSearch;
import de.gbv.logguy.server.security.LogGuyUser;

/**
 * Pseudonymizes the texts of alerts for users who may read an application only pseudonymized.
 */
public class AlertTexts {

    private final PseudonymService pseudonyms;

    /**
     * Creates the helper.
     *
     * @param pseudonyms the pseudonymization scripts
     */
    public AlertTexts(PseudonymService pseudonyms) {
        this.pseudonyms = pseudonyms;
    }

    /**
     * The alert as a user may see it: unchanged if the user reads the application in plain text, otherwise with
     * text, first line and group pseudonymized.
     *
     * @param user the user
     * @param session the pseudonyms of the session
     * @param alert the alert
     * @return the alert for the user
     */
    public Alert forUser(LogGuyUser user, Pseudonyms session, Alert alert) {
        if (user.readsPlain(alert.app())) {
            return alert;
        }
        return new Alert(alert.id(), alert.rule(), alert.type(), alert.app(), text(session, alert, alert.group()),
            alert.state(), alert.opened(), alert.updated(), alert.firstTs(), alert.lastTs(), alert.count(),
            text(session, alert, alert.message()), alert.eventId(), text(session, alert, alert.eventLine()),
            alert.notified(), alert.ackedBy(), alert.acked(), alert.resolved(), alert.seq());
    }

    private String text(Pseudonyms session, Alert alert, String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return pseudonyms.text(session, alert.app(), text).orElse(PseudonymizedSearch.HELD_BACK);
    }
}
