package de.gbv.logguy.server.index;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrInputDocument;

import de.gbv.logguy.server.script.FieldDefinition;
import de.gbv.logguy.server.script.FieldType;
import de.gbv.logguy.server.script.ParsedEvent;
import de.gbv.logguy.server.script.ParserScript;

/**
 * Builds the Solr documents of events, the same for the indexer and for events loaded from the archive.
 * <p>
 * Every document is made acceptable for Solr: values of string fields are cut to {@link #MAX_TERM_BYTES}, dates
 * outside of {@link #MIN_TIME} and {@link #MAX_TIME} are left out. What was changed is noted in {@code parse_error}.
 */
public final class EventDocuments {

    /** Marks the offset of an event id that points into the filtered file in {@code archive/}. */
    public static final String ARCHIVE_MARK = "a";

    /**
     * The longest term Lucene accepts in bytes of UTF-8, {@code IndexWriter.MAX_TERM_LENGTH}. A value of a string
     * field is one term, a longer one makes Solr reject the whole request.
     */
    public static final int MAX_TERM_BYTES = 32766;

    /** The earliest plausible time of an event, the first instant with a four digit year in ISO 8601. */
    public static final Instant MIN_TIME = Instant.parse("0000-01-01T00:00:00Z");

    /** The latest plausible time of an event, the last instant with a four digit year in ISO 8601. */
    public static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999Z");

    /** Fixed fields of type string besides {@code id} and {@code parse_error}. */
    private static final List<String> FIXED_STRING_FIELDS = List.of("app", "host", "file", "parser_version");

    /** Fields every document needs, see the schema. */
    public static final Set<String> REQUIRED_FIELDS = Set.of("id", "app", "host", "file", "offset", "ts", "message");

    private EventDocuments() {
    }

    /**
     * Builds the document of an event.
     *
     * @param id the event id
     * @param app the application
     * @param host the host
     * @param file the relative path of the file the event was read from
     * @param offset byte offset of the event in that file
     * @param ts time of the event, must be {@link #plausible(Instant)}
     * @param parsed the parsed event
     * @param script the parser script
     * @return the document
     * @throws IllegalArgumentException if {@code ts} is not plausible
     */
    public static SolrInputDocument create(String id, String app, String host, String file, long offset,
        Instant ts, ParsedEvent parsed, ParserScript script) {
        if (!plausible(ts)) {
            throw new IllegalArgumentException("implausible time of event " + id + ": " + ts);
        }
        List<String> cut = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        SolrInputDocument doc = new SolrInputDocument();
        doc.setField("id", id);
        doc.setField("offset", offset);
        doc.setField("ts", Date.from(ts));
        doc.setField("message", parsed.message());
        List<Object> fixed = List.of(app, host, file, script.version());
        for (int i = 0; i < FIXED_STRING_FIELDS.size(); i++) {
            doc.setField(FIXED_STRING_FIELDS.get(i), string(FIXED_STRING_FIELDS.get(i), fixed.get(i), cut));
        }
        for (Map.Entry<String, Object> field : parsed.fields().entrySet()) {
            FieldDefinition definition = script.fields().get(field.getKey());
            String name = definition.solrField();
            Object value = field.getValue();
            if (value instanceof Instant instant) {
                if (!plausible(instant)) {
                    dropped.add(name);
                    continue;
                }
                value = Date.from(instant);
            } else if (definition.type() == FieldType.STRING || definition.type() == FieldType.STRINGS) {
                value = string(name, value, cut);
            }
            doc.setField(name, value);
        }
        String error = parsed.error();
        cut.sort(null);
        dropped.sort(null);
        if (!cut.isEmpty()) {
            error = appendError(error, "cut to " + MAX_TERM_BYTES + " bytes: " + String.join(", ", cut));
        }
        if (!dropped.isEmpty()) {
            error = appendError(error, "implausible date left out: " + String.join(", ", dropped));
        }
        if (error != null) {
            doc.setField("parse_error", truncate(error, MAX_TERM_BYTES));
        }
        return doc;
    }

    private static Object string(String name, Object value, List<String> cut) {
        return switch (value) {
            case String text -> {
                String shorter = truncate(text, MAX_TERM_BYTES);
                if (shorter.length() != text.length()) {
                    cut.add(name);
                }
                yield shorter;
            }
            case List<?> list -> {
                List<Object> result = new ArrayList<>(list.size());
                boolean changed = false;
                for (Object element : list) {
                    Object shorter = element instanceof String text ? truncate(text, MAX_TERM_BYTES) : element;
                    changed |= shorter != element;
                    result.add(shorter);
                }
                if (changed) {
                    cut.add(name);
                }
                yield result;
            }
            case null, default -> value;
        };
    }

    /**
     * Cuts a string to at most a number of bytes in UTF-8, without splitting a character.
     *
     * @param value the string
     * @param maxBytes the maximum length in bytes of UTF-8
     * @return the string itself if it is short enough, otherwise its longest prefix that fits
     */
    public static String truncate(String value, int maxBytes) {
        // a char takes at most three bytes, a surrogate pair four bytes for two chars
        if ((long) value.length() * 3 <= maxBytes) {
            return value;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        int end = maxBytes;
        // bytes[end] is the first byte left out, step back while it continues a character started before
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    /**
     * Whether a time can be the time of an event: between {@link #MIN_TIME} and {@link #MAX_TIME}. Other times are
     * mostly misread numbers and would not fit into a {@link Date}, Solr or the search.
     *
     * @param time the time, may be {@code null}
     * @return true if it is not {@code null} and within the range
     */
    public static boolean plausible(Instant time) {
        return time != null && !time.isBefore(MIN_TIME) && !time.isAfter(MAX_TIME);
    }

    /**
     * Treats an implausible time of an event like a parse error: the time is removed, so the caller falls back to
     * the time of the event before or the time the file was received, and {@code parse_error} tells why.
     *
     * @param parsed the parsed event
     * @return the event itself if its time is missing or plausible, otherwise a copy without time
     */
    public static ParsedEvent withPlausibleTime(ParsedEvent parsed) {
        if (parsed.ts() == null || plausible(parsed.ts())) {
            return parsed;
        }
        return new ParsedEvent(null, parsed.message(), parsed.fields(),
            appendError(parsed.error(), "implausible ts: " + parsed.ts()));
    }

    /**
     * Adds a note to a parse error.
     *
     * @param error the error so far, may be {@code null}
     * @param note what to add
     * @return both, separated by {@code ; }
     */
    public static String appendError(String error, String note) {
        return error == null || error.isEmpty() ? note : error + "; " + note;
    }

    /**
     * The id of an event in a file in {@code hot/}.
     *
     * @param fingerprint fingerprint of the file
     * @param offset byte offset of the event
     * @return {@code <fingerprint>:<offset>}
     */
    public static String hotId(String fingerprint, long offset) {
        return fingerprint + ":" + offset;
    }

    /**
     * The id of an event loaded from the filtered file in {@code archive/}. It differs from the ids of
     * {@code hot/}, because the offsets of the filtered file are others.
     *
     * @param fingerprint fingerprint of the original file
     * @param offset byte offset of the event in the filtered file
     * @return {@code <fingerprint>:a<offset>}
     */
    public static String archiveId(String fingerprint, long offset) {
        return fingerprint + ":" + ARCHIVE_MARK + offset;
    }

    /**
     * The query for all events of a file, those of {@code hot/} and those loaded from the archive.
     *
     * @param fingerprint fingerprint of the file
     * @return the query
     */
    public static String eventsOf(String fingerprint) {
        return "id:" + ClientUtils.escapeQueryChars(fingerprint + ":") + "*";
    }

    /**
     * The query for the events of a file in {@code hot/}, without those loaded from the archive.
     *
     * @param fingerprint fingerprint of the file
     * @return the query
     */
    public static String hotEventsOf(String fingerprint) {
        return "id:" + ClientUtils.escapeQueryChars(fingerprint + ":") + "* -expires:[* TO *]";
    }
}
