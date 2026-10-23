package de.gbv.logguy.agent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.common.StreamStatus;

/**
 * Follows the live file of a source like {@code tail -F}.
 * <p>
 * The open file handle keeps pointing to the same inode. When the path points to another inode, the file was rotated:
 * the old one is read to its end, its stream is closed, and the new file is opened. A file that became shorter was
 * truncated in place ({@code copytruncate}), a new stream starts at offset 0. The copy belongs to the old stream and
 * comes with the rotated files.
 */
public class LiveTailer implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger();

    private final IngestClient client;

    private final String app;

    private final Path path;

    private final int chunkSize;

    private final ByteBuffer buffer;

    private FileChannel channel;

    private Object inode;

    private String fingerprint;

    private long position;

    private boolean finished;

    /**
     * Creates the tailer.
     *
     * @param client the client of the stream API
     * @param app the application
     * @param path the live file
     * @param chunkSize largest chunk
     */
    public LiveTailer(IngestClient client, String app, Path path, int chunkSize) {
        this.client = client;
        this.app = app;
        this.path = path;
        this.chunkSize = chunkSize;
        this.buffer = ByteBuffer.allocate(chunkSize);
    }

    /**
     * Checks whether enough new bytes for a full chunk are there.
     *
     * @return true if a full chunk is waiting
     */
    public boolean hasFullChunk() {
        try {
            return channel != null && fingerprint != null && channel.size() - position >= chunkSize;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Sends everything new, handles rotation and truncation.
     *
     * @throws IOException on errors, the next call continues where the server is
     * @throws InterruptedException if interrupted
     */
    public void poll() throws IOException, InterruptedException {
        if (channel == null && !open()) {
            return;
        }
        Object current = currentInode();
        boolean rotated = current == null || !current.equals(inode);
        long size = channel.size();
        if (!rotated && size < position) {
            LOGGER.info("{} was truncated from {} to {} bytes, starting a new stream", path, position, size);
            fingerprint = null;
            position = 0;
            finished = false;
        }
        if (fingerprint == null && !startStream(size, rotated)) {
            if (rotated) {
                closeChannel();
            }
            return;
        }
        if (!finished) {
            sendUntil(size);
        }
        if (rotated) {
            // the old file is complete once the path points elsewhere, read what came in the meantime
            sendUntil(channel.size());
            if (!finished) {
                try {
                    client.close(fingerprint, position, app, path);
                } catch (IngestClient.ConflictException e) {
                    LOGGER.info("{}: close refused, {}; the rotated file delivers the rest", path, e.getMessage());
                }
                LOGGER.info("{} rotated, stream {} closed at {} bytes", path, fingerprint, position);
            }
            closeChannel();
        }
    }

    private boolean open() throws IOException {
        try {
            channel = FileChannel.open(path, StandardOpenOption.READ);
        } catch (NoSuchFileException e) {
            return false;
        }
        inode = Files.readAttributes(path, "unix:ino").get("ino");
        fingerprint = null;
        position = 0;
        finished = false;
        return true;
    }

    private Object currentInode() throws IOException {
        try {
            return Files.readAttributes(path, "unix:ino").get("ino");
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    private boolean startStream(long size, boolean rotated) throws IOException, InterruptedException {
        if (size == 0 || size < Fingerprint.PREFIX_BYTES && !rotated) {
            return false;
        }
        ByteBuffer prefix = ByteBuffer.allocate((int) Math.min(size, Fingerprint.PREFIX_BYTES));
        while (prefix.hasRemaining() && channel.read(prefix, prefix.position()) > 0) {
            // read until the prefix is complete
        }
        fingerprint = Fingerprint.of(prefix.array(), prefix.position());
        StreamStatus status = client.status(fingerprint).orElse(new StreamStatus(fingerprint, 0, false));
        position = status.length();
        finished = status.closed();
        LOGGER.info("following {} as stream {} from offset {}", path, fingerprint, position);
        return true;
    }

    private void sendUntil(long size) throws IOException, InterruptedException {
        while (!finished && position < size) {
            buffer.clear();
            buffer.limit((int) Math.min(chunkSize, size - position));
            int read = channel.read(buffer, position);
            if (read <= 0) {
                return;
            }
            StreamStatus status;
            try {
                status = client.send(fingerprint, position, buffer.array(), buffer.position(), app, path);
            } catch (IngestClient.ConflictException e) {
                LOGGER.info("{}: {}, continuing there", path, e.getMessage());
                status = e.getStatus();
            }
            position = status.length();
            finished = status.closed();
        }
    }

    private void closeChannel() throws IOException {
        if (channel != null) {
            channel.close();
            channel = null;
        }
    }

    @Override
    public void close() throws IOException {
        closeChannel();
    }

    /**
     * The live file.
     *
     * @return the path
     */
    public Path path() {
        return path;
    }
}
