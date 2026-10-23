package de.gbv.logguy.server.script;

import java.util.Map;

/**
 * A script request that cannot be carried out. The message is German, it is shown to the user.
 */
public class ScriptRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Why the request failed.
     */
    public enum Reason {
        /** The request or the script is invalid. */
        INVALID,
        /** The script or its draft does not exist. */
        NOT_FOUND,
        /** The file changed since the draft was started. */
        CONFLICT,
        /** The last test had too many errors, activating needs a confirmation. */
        CONFIRM
    }

    private final Reason reason;

    private final transient Map<String, Object> details;

    /**
     * Creates the exception.
     *
     * @param reason why the request failed
     * @param message German message for the user
     * @param details further values for the client, may be empty
     */
    public ScriptRequestException(Reason reason, String message, Map<String, Object> details) {
        super(message);
        this.reason = reason;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    /**
     * Creates the exception without details.
     *
     * @param reason why the request failed
     * @param message German message for the user
     */
    public ScriptRequestException(Reason reason, String message) {
        this(reason, message, Map.of());
    }

    /**
     * Why the request failed.
     *
     * @return the reason
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Further values for the client.
     *
     * @return the details, may be empty
     */
    public Map<String, Object> details() {
        return details;
    }
}
