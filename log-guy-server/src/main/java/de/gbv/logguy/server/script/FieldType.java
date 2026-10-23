package de.gbv.logguy.server.script;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
     * Converts a value as JSON returns it, e.g. from {@code fields(event)} of a lifecycle filter.
     *
     * @param value a string, number, boolean, list or {@link Instant}
     * @return the value as the indexer stores it, {@code null} for {@code null} or an empty list
     * @throws IllegalArgumentException if the value does not fit the type
     * @throws java.time.DateTimeException if a date cannot be parsed
     */
    public Object convert(Object value) {
        if (value == null) {
            return null;
        }
        return switch (this) {
            case STRING, TEXT -> value.toString();
            case STRINGS -> {
                if (!(value instanceof List<?> list)) {
                    yield List.of(value.toString());
                }
                List<String> strings = list.stream().filter(Objects::nonNull).map(Object::toString).toList();
                yield strings.isEmpty() ? null : strings;
            }
            case INT -> value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
            case LONG -> value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
            case DOUBLE -> value instanceof Number number ? number.doubleValue()
                : Double.parseDouble(value.toString());
            case BOOL -> value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
            case DATE -> switch (value) {
                case Instant instant -> instant;
                case Number number -> Instant.ofEpochMilli(number.longValue());
                default -> Instant.parse(value.toString());
            };
        };
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
