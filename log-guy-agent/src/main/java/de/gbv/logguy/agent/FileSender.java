package de.gbv.logguy.agent;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.common.StreamStatus;

/**
 * Sends a file, plain or gzip compressed, and closes its stream if the file is complete. Only what the server does not
 * have yet is sent.
 */
public class FileSender {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final int BUFFER_SIZE = 64 * 1024;

    private final IngestClient client;

    private final int chunkSize;

    /**
     * Creates the sender.
     *
     * @param client the client of the stream API
     * @param chunkSize largest chunk
     */
    public FileSender(IngestClient client, int chunkSize) {
        this.client = client;
        this.chunkSize = chunkSize;
    }

    /**
     * Reads the fingerprint of a file.
     *
     * @param path the file, decompressed if it ends with {@code .gz}
     * @return the fingerprint, empty for an empty file
     * @throws IOException if the file cannot be read
     */
    public static Optional<String> fingerprint(Path path) throws IOException {
        try (InputStream in = open(path)) {
            byte[] prefix = in.readNBytes(Fingerprint.PREFIX_BYTES);
            return prefix.length == 0 ? Optional.empty() : Optional.of(Fingerprint.of(prefix));
        }
    }

    /**
     * Sends the rest of a file and closes its stream.
     *
     * @param path the file
     * @param app the application
     * @return the final status, empty for an empty file
     * @throws IOException on errors, worth a retry unless it is a {@link IngestClient.RejectedException}
     * @throws InterruptedException if interrupted
     */
    public Optional<StreamStatus> send(Path path, String app) throws IOException, InterruptedException {
        return send(path, app, true);
    }

    /**
     * Sends the rest of a file up to its current end.
     *
     * @param path the file
     * @param app the application
     * @param close whether the file is complete and its stream is closed at the end. A file that a process may still
     *     write to stays open.
     * @return the final status, empty for an empty file
     * @throws IOException if the file cannot be read
     * @throws IngestClient.ServerException if the server cannot be reached or fails, worth a retry
     * @throws IngestClient.RejectedException if the server refuses the file, worth a retry once the token or the
     *     server configuration has changed
     * @throws InterruptedException if interrupted
     */
    public Optional<StreamStatus> send(Path path, String app, boolean close) throws IOException,
        InterruptedException {
        Optional<String> fingerprint = fingerprint(path);
        if (fingerprint.isEmpty()) {
            return Optional.empty();
        }
        String fp = fingerprint.get();
        StreamStatus status = client.status(fp).orElse(new StreamStatus(fp, 0, false));
        if (status.closed()) {
            return Optional.of(status);
        }
        long position = status.length();
        byte[] buffer = new byte[chunkSize];
        long sent = 0;
        while (true) {
            try (InputStream in = open(path)) {
                skip(in, position, path);
                while (true) {
                    int length = in.readNBytes(buffer, 0, chunkSize);
                    if (length == 0) {
                        if (close) {
                            status = client.close(fp, position, app, path);
                        }
                        if (sent > 0) {
                            LOGGER.info("sent {} bytes of {}, {} bytes in total", sent, path, position);
                        }
                        return Optional.of(status);
                    }
                    status = client.send(fp, position, buffer, length, app, path);
                    if (status.closed()) {
                        return Optional.of(status);
                    }
                    long expected = position + length;
                    if (status.length() < expected) {
                        throw new IngestClient.ServerException(
                            "server stored " + status.length() + " bytes, expected " + expected);
                    }
                    // another sender may have delivered more of the same file in the meantime
                    skip(in, status.length() - expected, path);
                    sent += length;
                    position = status.length();
                }
            } catch (IngestClient.ConflictException e) {
                LOGGER.info("{}: {}, continuing there", path, e.getMessage());
                if (e.getStatus().closed()) {
                    return Optional.of(e.getStatus());
                }
                position = e.getStatus().length();
            }
        }
    }

    /**
     * Skips bytes the server already has. A file that ends before is not the file the server knows, a broken
     * {@code .gz} reports its own error.
     */
    private static void skip(InputStream in, long count, Path path) throws IOException {
        try {
            in.skipNBytes(count);
        } catch (EOFException e) {
            if (e.getMessage() != null) {
                throw e;
            }
            throw new IOException(path + " is shorter than the bytes the server has", e);
        }
    }

    /**
     * Opens a file for reading, decompressing {@code .gz}.
     *
     * @param path the file
     * @return the uncompressed content
     * @throws IOException if the file cannot be opened
     */
    static InputStream open(Path path) throws IOException {
        InputStream in = new BufferedInputStream(Files.newInputStream(path), BUFFER_SIZE);
        if (path.getFileName().toString().endsWith(".gz")) {
            return new GZIPInputStream(in, BUFFER_SIZE);
        }
        return in;
    }
}
