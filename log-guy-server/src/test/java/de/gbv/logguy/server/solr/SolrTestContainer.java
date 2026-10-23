package de.gbv.logguy.server.solr;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import de.gbv.logguy.server.config.LogGuyProperties.SolrProperties;

/**
 * SolrCloud with embedded ZooKeeper for integration tests. The container uses the host network, so the node
 * registers itself as {@code localhost:<port>} and the cloud client can reach it.
 */
public final class SolrTestContainer implements AutoCloseable {

    /** The image, as the production cluster is expected to run. */
    public static final String IMAGE = "solr:9.10.1";

    private final GenericContainer<?> container;

    private final int port;

    /**
     * Starts Solr and waits until it answers.
     *
     * @throws Exception if Solr does not come up
     */
    @SuppressWarnings("resource")
    public SolrTestContainer() throws Exception {
        port = freePort();
        container = new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withNetworkMode("host")
            .withEnv("SOLR_PORT", Integer.toString(port))
            .withEnv("SOLR_HOST", "localhost")
            .withEnv("SOLR_HEAP", "1g")
            .withCommand("solr-foreground", "-c");
        container.start();
        waitUntilReady();
    }

    /**
     * Connection settings for this Solr.
     *
     * @return settings with replication factor 1
     */
    public SolrProperties properties() {
        return new SolrProperties(List.of(baseUrl()), "", null, "log-guy-v1", "logs_v1", "logs", 1,
            Duration.ofSeconds(60));
    }

    /**
     * Base URL of the node.
     *
     * @return e.g. {@code http://localhost:32123/solr}
     */
    public String baseUrl() {
        return "http://localhost:" + port + "/solr";
    }

    private void waitUntilReady() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/admin/collections?action=LIST"))
            .build();
        Instant deadline = Instant.now().plusSeconds(120);
        while (Instant.now().isBefore(deadline)) {
            try {
                if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                // not up yet
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Solr did not start: " + container.getLogs());
    }

    private static int freePort() throws IOException {
        for (int i = 0; i < 20; i++) {
            try (ServerSocket socket = new ServerSocket(0)) {
                int candidate = socket.getLocalPort();
                // the embedded ZooKeeper listens on port + 1000
                if (candidate + 1000 < 65536) {
                    try (ServerSocket zk = new ServerSocket(candidate + 1000)) {
                        return candidate;
                    } catch (IOException e) {
                        // taken, try another
                    }
                }
            }
        }
        throw new IOException("no free port pair found");
    }

    @Override
    public void close() {
        container.stop();
    }
}
