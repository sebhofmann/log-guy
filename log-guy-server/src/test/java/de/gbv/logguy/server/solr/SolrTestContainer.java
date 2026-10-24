package de.gbv.logguy.server.solr;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
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
    public SolrTestContainer() throws Exception {
        this(null);
    }

    /**
     * Starts Solr, waits until it answers and switches on authentication and authorization.
     *
     * @param securityJson content of {@code security.json}, {@code null} for an open Solr
     * @throws Exception if Solr does not come up
     */
    @SuppressWarnings("resource")
    public SolrTestContainer(String securityJson) throws Exception {
        port = freePort();
        container = new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withNetworkMode("host")
            .withEnv("SOLR_PORT", Integer.toString(port))
            .withEnv("SOLR_HOST", "localhost")
            .withEnv("SOLR_HEAP", "1g")
            .withCommand("solr-foreground", "-c");
        container.start();
        waitUntilReady();
        if (securityJson != null) {
            container.copyFileToContainer(Transferable.of(securityJson), "/tmp/security.json");
            ExecResult result = container.execInContainer("solr", "zk", "cp", "file:/tmp/security.json",
                "zk:/security.json", "-z", "localhost:" + (port + 1000));
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("security.json not uploaded: " + result.getStderr());
            }
            waitForStatus(401);
        }
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
     * Connection settings for this Solr with a user.
     *
     * @param user the Solr user
     * @param passwordFile file with its password
     * @return settings with replication factor 1
     */
    public SolrProperties properties(String user, Path passwordFile) {
        return new SolrProperties(List.of(baseUrl()), user, passwordFile, "log-guy-v1", "logs_v1", "logs", 1,
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
        waitForStatus(200);
    }

    private void waitForStatus(int status) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/admin/collections?action=LIST"))
            .build();
        Instant deadline = Instant.now().plusSeconds(120);
        while (Instant.now().isBefore(deadline)) {
            try {
                if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == status) {
                    return;
                }
            } catch (IOException e) {
                // not up yet
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Solr did not answer with " + status + ": " + container.getLogs());
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
