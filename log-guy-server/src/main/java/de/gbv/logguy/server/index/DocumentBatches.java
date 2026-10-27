package de.gbv.logguy.server.index;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.solr.common.SolrInputDocument;

/**
 * Sends batches of documents so that a single invalid document cannot stop the indexing of a file.
 * <p>
 * If the sink rejects a batch with {@link DocumentsRejectedException}, the batch is split in halves and each half is
 * sent again, until the rejected document is alone. That one is sent once more without the field Solr names in its
 * error, then without all fields of the parser script, and at last as a minimal document with only the required
 * fields. Each attempt notes in {@code parse_error} what was left out. Other errors, e.g. of the network, are thrown
 * on, so the caller tries again later.
 */
public final class DocumentBatches {

    private static final Logger LOGGER = LogManager.getLogger();

    // Solr names the field in errors like: immense term in field="path_s"
    private static final Pattern FIELD = Pattern.compile("field=\"([^\"]+)\"");

    private DocumentBatches() {
    }

    /**
     * Adds documents to the sink.
     *
     * @param sink where the documents go
     * @param documents the documents, not changed
     * @return the number of documents stored, those stored with fields left out included
     * @throws Exception any error of the sink other than {@link DocumentsRejectedException}
     */
    public static int add(Indexer.DocumentSink sink, List<SolrInputDocument> documents) throws Exception {
        if (documents.isEmpty()) {
            return 0;
        }
        try {
            sink.add(documents);
            return documents.size();
        } catch (DocumentsRejectedException e) {
            if (documents.size() == 1) {
                return repair(sink, documents.getFirst(), e) ? 1 : 0;
            }
            int half = documents.size() / 2;
            return add(sink, new ArrayList<>(documents.subList(0, half)))
                + add(sink, new ArrayList<>(documents.subList(half, documents.size())));
        }
    }

    private static boolean repair(Indexer.DocumentSink sink, SolrInputDocument original,
        DocumentsRejectedException rejected) throws Exception {
        Object id = original.getFieldValue("id");
        Object error = original.getFieldValue("parse_error");
        SolrInputDocument doc = original.deepCopy();
        List<String> removed = new ArrayList<>();
        boolean stripped = false;
        boolean minimal = false;
        String reason = rejected.getMessage();
        while (true) {
            String blamed = blamedField(reason);
            if (!stripped && blamed != null && doc.containsKey(blamed)
                && !EventDocuments.REQUIRED_FIELDS.contains(blamed) && !removed.contains(blamed)) {
                doc.removeField(blamed);
                removed.add(blamed);
            } else if (!stripped) {
                stripped = true;
                for (String name : new ArrayList<>(doc.getFieldNames())) {
                    if (!EventDocuments.REQUIRED_FIELDS.contains(name) && !"expires".equals(name)) {
                        doc.removeField(name);
                        if (!"parse_error".equals(name) && !"parser_version".equals(name)) {
                            removed.add(name);
                        }
                    }
                }
            } else if (!minimal) {
                minimal = true;
                doc = minimal(original);
            } else {
                LOGGER.error("Solr rejects event {} even as minimal document, it is not indexed: {}", id, reason);
                return false;
            }
            String note = minimal ? "rejected by Solr, indexed as minimal document"
                : "rejected by Solr, left out: " + String.join(", ", removed);
            String kept = minimal || stripped || removed.contains("parse_error") || error == null ? null
                : error.toString();
            doc.setField("parse_error",
                EventDocuments.truncate(EventDocuments.appendError(kept, note), EventDocuments.MAX_TERM_BYTES));
            try {
                sink.add(List.of(doc));
                LOGGER.warn("Solr rejected event {}, indexed it with {}: {}", id, note, reason);
                return true;
            } catch (DocumentsRejectedException again) {
                reason = again.getMessage();
            }
        }
    }

    private static SolrInputDocument minimal(SolrInputDocument original) {
        SolrInputDocument doc = new SolrInputDocument();
        for (String name : EventDocuments.REQUIRED_FIELDS) {
            Object value = original.getFieldValue(name);
            if (value instanceof String text) {
                value = EventDocuments.truncate(text, EventDocuments.MAX_TERM_BYTES);
            }
            doc.setField(name, value);
        }
        Object expires = original.getFieldValue("expires");
        if (expires != null) {
            doc.setField("expires", expires);
        }
        return doc;
    }

    private static String blamedField(String reason) {
        if (reason == null) {
            return null;
        }
        Matcher matcher = FIELD.matcher(reason);
        return matcher.find() ? matcher.group(1) : null;
    }
}
