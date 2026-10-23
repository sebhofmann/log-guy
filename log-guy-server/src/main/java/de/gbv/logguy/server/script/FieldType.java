package de.gbv.logguy.server.script;

import java.util.Locale;

/**
 * Type of a field declared by a parser script, with the suffix of the dynamic Solr field.
 */
public enum FieldType {

    /** Single string, {@code *_s}. */
    STRING("_s"),

    /** Several strings, {@code *_ss}. */
    STRINGS("_ss"),

    /** 32 bit integer, {@code *_i}. */
    INT("_i"),

    /** 64 bit integer, {@code *_l}. */
    LONG("_l"),

    /** Floating point number, {@code *_d}. */
    DOUBLE("_d"),

    /** Point in time, {@code *_dt}. */
    DATE("_dt"),

    /** Boolean, {@code *_b}. */
    BOOL("_b"),

    /** Full text, {@code *_t}. */
    TEXT("_t");

    private final String suffix;

    FieldType(String suffix) {
        this.suffix = suffix;
    }

    /**
     * Suffix of the dynamic Solr field.
     *
     * @return e.g. {@code _s}
     */
    public String suffix() {
        return suffix;
    }

    /**
     * Name of the Solr field for a declared field.
     *
     * @param field the declared name, e.g. {@code level}
     * @return the Solr field, e.g. {@code level_s}
     */
    public String solrField(String field) {
        return field + suffix;
    }

    /**
     * Parses the type name used in scripts.
     *
     * @param name e.g. {@code string}
     * @return the type
     * @throws IllegalArgumentException for unknown names
     */
    public static FieldType of(String name) {
        return valueOf(name.toUpperCase(Locale.ROOT));
    }
}
