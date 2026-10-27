package de.gbv.logguy.server.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Test;

class DocumentBatchesTest {

    private final Map<String, SolrInputDocument> stored = new LinkedHashMap<>();

    private int requests;

    /** Stores like Solr: a batch with one bad document fails as a whole, the error names the field. */
    private Indexer.DocumentSink sink(Predicate<SolrInputDocument> bad, String field) {
        return documents -> {
            requests++;
            for (SolrInputDocument doc : documents) {
                if (bad.test(doc)) {
                    throw new DocumentsRejectedException("Exception writing document id " + doc.getFieldValue("id")
                        + " to the index; possible analysis error: Document contains at least one immense term in"
                        + " field=\"" + field + "\"", null);
                }
            }
            documents.forEach(doc -> stored.put(doc.getFieldValue("id").toString(), doc));
        };
    }

    private static List<SolrInputDocument> batch(int size) {
        List<SolrInputDocument> batch = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            SolrInputDocument doc = new SolrInputDocument();
            doc.setField("id", "fp:" + i);
            doc.setField("app", "digibib");
            doc.setField("host", "app1");
            doc.setField("file", "f");
            doc.setField("offset", (long) i);
            doc.setField("ts", new Date());
            doc.setField("message", "line " + i);
            doc.setField("parser_version", "v1");
            doc.setField("level_s", "INFO");
            batch.add(doc);
        }
        return batch;
    }

    @Test
    void validBatchIsOneRequest() throws Exception {
        assertEquals(10, DocumentBatches.add(sink(doc -> false, "x"), batch(10)));
        assertEquals(1, requests);
        assertEquals(10, stored.size());
        assertEquals(0, DocumentBatches.add(sink(doc -> false, "x"), List.of()));
    }

    @Test
    void rejectedDocumentIsStoredWithoutTheBlamedField() throws Exception {
        List<SolrInputDocument> batch = batch(100);
        batch.get(37).setField("path_s", "bad");
        int count = DocumentBatches.add(sink(doc -> doc.containsKey("path_s"), "path_s"), batch);
        assertEquals(100, count);
        assertEquals(100, stored.size());
        SolrInputDocument repaired = stored.get("fp:37");
        assertFalse(repaired.containsKey("path_s"));
        assertEquals("INFO", repaired.getFieldValue("level_s"));
        assertEquals("rejected by Solr, left out: path_s", repaired.getFieldValue("parse_error"));
        // the original document is not changed
        assertEquals("bad", batch.get(37).getFieldValue("path_s"));
        // halving finds one document of 100 in a few requests
        assertTrue(requests < 20, "requests: " + requests);
    }

    @Test
    void keepsErrorOfTheScript() throws Exception {
        List<SolrInputDocument> batch = batch(1);
        batch.getFirst().setField("path_s", "bad");
        batch.getFirst().setField("parse_error", "timeout");
        DocumentBatches.add(sink(doc -> doc.containsKey("path_s"), "path_s"), batch);
        assertEquals("timeout; rejected by Solr, left out: path_s", stored.get("fp:0").getFieldValue("parse_error"));
    }

    @Test
    void withoutBlamedFieldAllScriptFieldsAreLeftOut() throws Exception {
        List<SolrInputDocument> batch = batch(4);
        batch.get(2).setField("bad_s", "x");
        batch.get(2).setField("expires", new Date());
        // the error names a field that is not in the document
        DocumentBatches.add(sink(doc -> doc.containsKey("bad_s"), "unknown"), batch);
        SolrInputDocument repaired = stored.get("fp:2");
        assertFalse(repaired.containsKey("level_s"));
        assertFalse(repaired.containsKey("bad_s"));
        assertTrue(repaired.containsKey("expires"));
        assertEquals("rejected by Solr, left out: level_s, bad_s", repaired.getFieldValue("parse_error"));
    }

    @Test
    void lastResortIsMinimalDocument() throws Exception {
        List<SolrInputDocument> batch = batch(3);
        // only the minimal document cuts the required fields
        batch.get(1).setField("message", "m".repeat(40_000));
        DocumentBatches.add(sink(doc -> doc.getFieldValue("message").toString().length() > 40_000 - 1, "message"),
            batch);
        SolrInputDocument repaired = stored.get("fp:1");
        assertEquals(EventDocuments.MAX_TERM_BYTES, repaired.getFieldValue("message").toString().length());
        assertEquals(EventDocuments.REQUIRED_FIELDS.size() + 1, repaired.getFieldNames().size());
        assertEquals("rejected by Solr, indexed as minimal document", repaired.getFieldValue("parse_error"));
        assertEquals(3, stored.size());
    }

    @Test
    void documentRejectedEvenAsMinimalIsSkipped() throws Exception {
        List<SolrInputDocument> batch = batch(3);
        batch.get(0).setField("message", "poison");
        int count = DocumentBatches.add(sink(doc -> "poison".equals(doc.getFieldValue("message")), "message"),
            batch);
        assertEquals(2, count);
        assertEquals(List.of("fp:1", "fp:2"), List.copyOf(stored.keySet()));
    }

    @Test
    void otherErrorsAreThrown() {
        Indexer.DocumentSink down = documents -> {
            throw new IOException("Solr is gone");
        };
        assertThrows(IOException.class, () -> DocumentBatches.add(down, batch(3)));
    }
}
