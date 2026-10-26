package de.gbv.logguy.server.index;

import java.time.Instant;
import java.util.Date;
import java.util.Map;

import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrInputDocument;

import de.gbv.logguy.server.script.FieldDefinition;
import de.gbv.logguy.server.script.ParsedEvent;
import de.gbv.logguy.server.script.ParserScript;

/**
 * Builds the Solr documents of events, the same for the indexer and for events loaded from the archive.
 */
public final class EventDocuments {

    /** Marks the offset of an event id that points into the filtered file in {@code archive/}. */
    public static final String ARCHIVE_MARK = "a";

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
     * @param ts time of the event
     * @param parsed the parsed event
     * @param script the parser script
     * @return the document
     */
    public static SolrInputDocument create(String id, String app, String host, String file, long offset,
        Instant ts, ParsedEvent parsed, ParserScript script) {
        SolrInputDocument doc = new SolrInputDocument();
        doc.setField("id", id);
        doc.setField("app", app);
        doc.setField("host", host);
        doc.setField("file", file);
        doc.setField("offset", offset);
        doc.setField("ts", Date.from(ts));
        doc.setField("message", parsed.message());
        doc.setField("parser_version", script.version());
        if (parsed.error() != null) {
            doc.setField("parse_error", parsed.error());
        }
        for (Map.Entry<String, Object> field : parsed.fields().entrySet()) {
            FieldDefinition definition = script.fields().get(field.getKey());
            Object value = field.getValue();
            if (value instanceof Instant instant) {
                value = Date.from(instant);
            }
            doc.setField(definition.solrField(), value);
        }
        return doc;
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
