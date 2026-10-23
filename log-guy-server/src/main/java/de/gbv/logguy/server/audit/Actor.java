package de.gbv.logguy.server.audit;

/**
 * Who did something and on which way, for the audit log and the script history.
 *
 * @param user the user, {@code null} for the file system
 * @param token name of the token, {@code null} for a GUI session or the file system
 * @param via the way
 */
public record Actor(String user, String token, Via via) {

    /** Changes made directly in the script directory and taken over by a reload. */
    public static final Actor FILESYSTEM = new Actor(null, null, Via.FILESYSTEM);

    /**
     * The way a change or a request came in.
     */
    public enum Via {
        /** Files changed on disk, taken over by a reload. */
        FILESYSTEM,
        /** The GUI with a session. */
        GUI,
        /** The API with an API token. */
        API,
        /** The MCP endpoint with an MCP token. */
        MCP,
        /** The command line on the server. */
        CLI
    }

    /**
     * A short label for logs and the GUI.
     *
     * @return e.g. {@code anna (GUI)} or {@code Dateisystem}
     */
    public String label() {
        if (via == Via.FILESYSTEM) {
            return "Dateisystem";
        }
        String who = user == null ? "?" : user;
        return token == null ? who + " (" + via + ")" : who + " (" + via + ", " + token + ")";
    }
}
