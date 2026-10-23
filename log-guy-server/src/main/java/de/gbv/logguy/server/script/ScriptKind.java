package de.gbv.logguy.server.script;

import java.util.Locale;

/**
 * The kinds of scripts, each in its own directory below the script directory.
 */
public enum ScriptKind {

    /** Cuts the lines of an application into events with fields. */
    PARSER,

    /** Decides whether an event triggers an alert. */
    RULE,

    /** Changes lines on their way into the archive. */
    LIFECYCLE,

    /** Replaces personal values before they leave the MCP endpoint. */
    PSEUDONYM;

    /**
     * The name of the directory and the path segment in the API.
     *
     * @return e.g. {@code parser}
     */
    public String dir() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a script of this kind can be removed. Every application needs a parser.
     *
     * @return false for parsers
     */
    public boolean deletable() {
        return this != PARSER;
    }

    /**
     * Looks up a kind by its directory name.
     *
     * @param dir e.g. {@code parser}
     * @return the kind
     * @throws IllegalArgumentException for an unknown name
     */
    public static ScriptKind of(String dir) {
        for (ScriptKind kind : values()) {
            if (kind.dir().equals(dir)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown script kind " + dir);
    }
}
