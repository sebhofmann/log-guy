package de.gbv.logguy.server.script;

/**
 * A script cannot be loaded, e.g. because of a syntax error or missing exports.
 */
public class ScriptException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong
     * @param cause the cause, may be null
     */
    public ScriptException(String message, Throwable cause) {
        super(message, cause);
    }
}
