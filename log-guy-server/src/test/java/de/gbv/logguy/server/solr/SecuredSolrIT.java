package de.gbv.logguy.server.solr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import de.gbv.logguy.server.solr.SolrService.ReplicaInfo;

/**
 * log-guy against a Solr with Basic Auth and the rules of {@code solr/security.json}: a user that may only work on
 * {@code logs*} and its configset, next to a collection of another application.
 */
class SecuredSolrIT {

    private static final String ADMIN_PASSWORD = "admin-pw";

    private static final String LOG_GUY_PASSWORD = "log-guy-pw";

    @TempDir
    static Path dir;

    private static SolrTestContainer solr;

    private static SolrService service;

    @BeforeAll
    static void startSolr() throws Exception {
        String security;
        try (InputStream in = SecuredSolrIT.class.getResourceAsStream("/solr/security.json")) {
            security = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                .replace("ADMIN_HASH", hash(ADMIN_PASSWORD)).replace("LOG_GUY_HASH", hash(LOG_GUY_PASSWORD));
        }
        solr = new SolrTestContainer(security);
        assertEquals(200, admin("admin/collections?action=CREATE&name=mir&numShards=1"
            + "&collection.configName=_default"));
        Path passwordFile = Files.writeString(dir.resolve("solr.pass"), LOG_GUY_PASSWORD + "\n");
        service = new SolrService(solr.properties("log-guy", passwordFile));
    }

    @AfterAll
    static void stopSolr() throws Exception {
        if (service != null) {
            service.close();
        }
        if (solr != null) {
            solr.close();
        }
    }

    @Test
    void logGuyWorksOnItsCollectionAndNowhereElse() throws Exception {
        List<String> steps = service.init();
        assertTrue(steps.stream().anyMatch(s -> s.contains("uploaded")), steps.toString());

        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("id", "f00:0");
        doc.addField("app", "digibib");
        doc.addField("host", "test");
        doc.addField("file", "f00");
        doc.addField("offset", 0L);
        doc.addField("ts", Date.from(Instant.parse("2026-10-24T02:00:00Z")));
        doc.addField("message", "Connection is not available");
        service.add(List.of(doc));
        service.client().commit("logs");
        assertEquals(1, service.client().query("logs", new SolrQuery("message:connection")).getResults()
            .getNumFound());
        assertEquals(Instant.parse("2026-10-24T02:00:00Z"), service.oldestEvent("digibib").orElseThrow());

        List<ReplicaInfo> replicas = service.replicas();
        assertEquals(1, replicas.size());
        assertNull(replicas.getFirst().error());
        assertEquals(1, replicas.getFirst().documents());
        assertTrue(replicas.getFirst().sizeBytes() > 0, replicas.toString());

        service.delete("app:digibib");
        service.client().commit("logs");
        assertEquals(0, service.client().query("logs", new SolrQuery("*:*")).getResults().getNumFound());

        assertForbidden(() -> service.client().query("mir", new SolrQuery("*:*")));
        assertForbidden(() -> CollectionAdminRequest.deleteCollection("mir").process(service.client()));
        assertForbidden(() -> CollectionAdminRequest.createAlias("logs", "mir").process(service.client()));
        assertForbidden(() -> CollectionAdminRequest.createAlias("logs_v2", "mir").process(service.client()));
        assertForbidden(() -> CollectionAdminRequest.createCollection("mirlogs", "log-guy-v1", 1, 1)
            .process(service.client()));
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        Path wrong = Files.writeString(dir.resolve("wrong.pass"), "nope");
        try (SolrService other = new SolrService(solr.properties("log-guy", wrong))) {
            // SolrJ reports the 401 as "Failed fetching live_nodes"
            assertThrows(Exception.class, () -> other.client().query("logs_v1", new SolrQuery("*:*")));
        }
    }

    private static void assertForbidden(Executable executable) {
        int code = 403;
        Throwable thrown = assertThrows(Exception.class, executable);
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SolrException e && e.code() == code || String.valueOf(t.getMessage()).contains(
                Integer.toString(code))) {
                return;
            }
        }
        throw new AssertionError("expected HTTP " + code, thrown);
    }

    private static int admin(String path) throws Exception {
        String auth = Base64.getEncoder().encodeToString(("admin:" + ADMIN_PASSWORD).getBytes(
            StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(solr.baseUrl() + "/" + path))
            .header("Authorization", "Basic " + auth).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /**
     * The password hash of Solr's {@code Sha256AuthenticationProvider}: SHA-256 twice over salt and password.
     */
    private static String hash(String password) throws Exception {
        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(salt);
        byte[] once = digest.digest(password.getBytes(StandardCharsets.UTF_8));
        digest.reset();
        byte[] twice = digest.digest(once);
        Base64.Encoder base64 = Base64.getEncoder();
        return base64.encodeToString(twice) + " " + base64.encodeToString(salt);
    }
}
