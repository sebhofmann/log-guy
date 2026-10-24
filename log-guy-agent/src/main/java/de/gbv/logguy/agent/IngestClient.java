package de.gbv.logguy.agent;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import de.gbv.logguy.common.IngestProtocol;
import de.gbv.logguy.common.StreamStatus;
import de.gbv.logguy.common.TokenInfo;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client of the stream API.
 */
public class IngestClient {

    private final AgentConfig config;

    private final HttpClient http;

    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Creates the client.
     *
     * @param config the agent configuration
     */
    public IngestClient(AgentConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(config.connectTimeout())
            .build();
    }

    /**
     * Asks what the token allows.
     *
     * @return name, host and applications of the token
     * @throws RejectedException if the server rejects the token
     * @throws IOException on network errors and server errors
     * @throws InterruptedException if interrupted
     */
    public TokenInfo token() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.server() + IngestProtocol.STREAMS_PATH))
            .timeout(config.requestTimeout())
            .header("Authorization", "Bearer " + config.token())
            .GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 200) {
            return json.readValue(response.body(), TokenInfo.class);
        }
        String body = new String(response.body(), StandardCharsets.UTF_8);
        if (response.statusCode() >= 500) {
            throw new IOException("server answered " + response.statusCode() + ": " + body);
        }
        throw new RejectedException("server answered " + response.statusCode() + ": " + body);
    }

    /**
     * Asks how far the server has a file.
     *
     * @param fingerprint the fingerprint
     * @return the status, empty if the server does not know the file
     * @throws IOException on network errors and server errors, worth a retry
     * @throws InterruptedException if interrupted
     */
    public Optional<StreamStatus> status(String fingerprint) throws IOException, InterruptedException {
        HttpRequest request = request(fingerprint, "").GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        return Optional.of(handle(response));
    }

    /**
     * Sends a chunk.
     *
     * @param fingerprint the fingerprint
     * @param offset offset of the first byte
     * @param data the bytes
     * @param length number of valid bytes
     * @param app the application
     * @param path path of the file on this host
     * @return the new status
     * @throws ConflictException if the server expects another offset
     * @throws IOException on network errors and server errors, worth a retry
     * @throws InterruptedException if interrupted
     */
    public StreamStatus send(String fingerprint, long offset, byte[] data, int length, String app, Path path)
        throws IOException, InterruptedException {
        HttpRequest request = request(fingerprint, "/chunks?" + IngestProtocol.PARAM_OFFSET + "=" + offset)
            .header(IngestProtocol.HEADER_APP, app)
            .header(IngestProtocol.HEADER_FILE, encode(path))
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(data, 0, length))
            .build();
        return handle(http.send(request, HttpResponse.BodyHandlers.ofByteArray()));
    }

    /**
     * Marks a file as complete.
     *
     * @param fingerprint the fingerprint
     * @param length the final length
     * @param app the application
     * @param path path of the file on this host
     * @return the new status
     * @throws ConflictException if the server has another length
     * @throws IOException on network errors and server errors, worth a retry
     * @throws InterruptedException if interrupted
     */
    public StreamStatus close(String fingerprint, long length, String app, Path path)
        throws IOException, InterruptedException {
        HttpRequest request = request(fingerprint, "/close?" + IngestProtocol.PARAM_LENGTH + "=" + length)
            .header(IngestProtocol.HEADER_APP, app)
            .header(IngestProtocol.HEADER_FILE, encode(path))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        return handle(http.send(request, HttpResponse.BodyHandlers.ofByteArray()));
    }

    private HttpRequest.Builder request(String fingerprint, String suffix) {
        return HttpRequest
            .newBuilder(URI.create(config.server() + IngestProtocol.STREAMS_PATH + "/" + fingerprint + suffix))
            .timeout(config.requestTimeout())
            .header("Authorization", "Bearer " + config.token())
            .header(IngestProtocol.HEADER_HOST, config.host());
    }

    private StreamStatus handle(HttpResponse<byte[]> response) throws IOException {
        int code = response.statusCode();
        if (code == 200) {
            return json.readValue(response.body(), StreamStatus.class);
        }
        if (code == 409) {
            throw new ConflictException(json.readValue(response.body(), StreamStatus.class));
        }
        String body = new String(response.body(), StandardCharsets.UTF_8);
        if (code == 429 || code >= 500) {
            throw new IOException("server answered " + code + ": " + body);
        }
        throw new RejectedException("server answered " + code + ": " + body);
    }

    private static String encode(Path path) {
        return URLEncoder.encode(path.toString(), StandardCharsets.UTF_8);
    }

    /**
     * The server expects another offset, continue at {@link #getStatus()}.
     */
    public static class ConflictException extends IOException {

        private static final long serialVersionUID = 1L;

        private final transient StreamStatus status;

        ConflictException(StreamStatus status) {
            super("server has " + status.length() + " bytes" + (status.closed() ? ", closed" : ""));
            this.status = status;
        }

        /**
         * What the server has.
         *
         * @return the status of the stream on the server
         */
        public StreamStatus getStatus() {
            return status;
        }
    }

    /**
     * The server refused the request for good, e.g. the token may not write the application. A retry does not help.
     */
    public static class RejectedException extends IOException {

        private static final long serialVersionUID = 1L;

        RejectedException(String message) {
            super(message);
        }
    }
}
