package de.gbv.logguy.agent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.common.StreamStatus;

/**
 * Follows the live file of a source like {@code tail -F}.
 * <p>
 * The open file handle keeps pointing to the same inode. When the path points to another inode, the file was rotated
 * and the new file is opened. The old file stays open: a process may still write to it until it reopens its log, e.g.
 * Apache httpd until the reload after {@code logrotate}. Its stream is closed once it has not grown for
 * {@code rotation-grace}. A file that became shorter was truncated in place ({@code copytruncate}), a new stream starts
 * at offset 0. The copy belongs to the old stream and comes with the rotated files.
 */
public class LiveTailer implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger();

    private final IngestClient client;

    private final String app;

    private final Path path;

    private final int chunkSize;

    private final Duration rotationGrace;

    private final Clock clock;

    private final ByteBuffer buffer;

    private Followed current;

    private final List<Followed> rotated = new ArrayList<>();

    /**
     * Creates the tailer.
     *
     * @param client the client of the stream API
     * @param app the application
     * @param path the live file
     * @param chunkSize largest chunk
     * @param rotationGrace how long a rotated file must stop growing before its stream is closed
     */
    public LiveTailer(IngestClient client, String app, Path path, int chunkSize, Duration rotationGrace) {
        this(client, app, path, chunkSize, rotationGrace, Clock.systemUTC());
    }

    LiveTailer(IngestClient client, String app, Path path, int chunkSize, Duration rotationGrace, Clock clock) {
        this.client = client;
        this.app = app;
        this.path = path;
        this.chunkSize = chunkSize;
        this.rotationGrace = rotationGrace;
        this.clock = clock;
        this.buffer = ByteBuffer.allocate(chunkSize);
    }

    /**
     * Checks whether enough new bytes for a full chunk are there, in the live file or in a rotated file that is still
     * followed.
     *
     * @return true if a full chunk is waiting
     */
    public boolean hasFullChunk() {
        if (hasFullChunk(current)) {
            return true;
        }
        return rotated.stream().anyMatch(this::hasFullChunk);
    }

    private boolean hasFullChunk(Followed file) {
        try {
            return file != null && file.fingerprint != null && !file.finished
                && file.channel.size() - file.position >= chunkSize;
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
        pollRotated();
        if (current == null && (current = open()) == null) {
            return;
        }
        Object inode = currentInode();
        if (inode == null || !inode.equals(current.inode)) {
            LOGGER.info("{} rotated, following the old file until it has not grown for {}", path, rotationGrace);
            current.lastSize = current.channel.size();
            current.lastGrowth = clock.instant();
            rotated.add(current);
            current = null;
            pollRotated();
            if ((current = open()) == null) {
                return;
            }
        }
        long size = current.channel.size();
        if (size < current.position) {
            LOGGER.info("{} was truncated from {} to {} bytes, starting a new stream", path, current.position, size);
            current.fingerprint = null;
            current.position = 0;
            current.finished = false;
        }
        if (current.fingerprint == null && !startStream(current, size, false)) {
            return;
        }
        sendUntil(current, size);
    }

    /**
     * Sends what came to the rotated files and closes their streams once they have not grown for the grace period.
     */
    private void pollRotated() throws IOException, InterruptedException {
        Instant now = clock.instant();
        for (Iterator<Followed> it = rotated.iterator(); it.hasNext();) {
            Followed file = it.next();
            long size = file.channel.size();
            if (size != file.lastSize) {
                file.lastSize = size;
                file.lastGrowth = now;
            }
            boolean quiet = !file.lastGrowth.plus(rotationGrace).isAfter(now);
            if (file.fingerprint == null && !startStream(file, size, quiet)) {
                if (quiet) {
                    file.channel.close();
                    it.remove();
                }
                continue;
            }
            sendUntil(file, size);
            if (!quiet && !file.finished) {
                continue;
            }
            if (file.channel.size() != size) {
                // written while sending, wait for the next grace period
                file.lastSize = file.channel.size();
                file.lastGrowth = now;
                continue;
            }
            if (!file.finished) {
                try {
                    client.close(file.fingerprint, file.position, app, path);
                    LOGGER.info("{} rotated, stream {} closed at {} bytes", path, file.fingerprint, file.position);
                } catch (IngestClient.ConflictException e) {
                    LOGGER.info("{}: close refused, {}; the rotated file delivers the rest", path, e.getMessage());
                }
            }
            file.channel.close();
            it.remove();
        }
    }

    private Followed open() throws IOException {
        FileChannel channel;
        try {
            channel = FileChannel.open(path, StandardOpenOption.READ);
        } catch (NoSuchFileException e) {
            return null;
        }
        try {
            return new Followed(channel, Files.readAttributes(path, "unix:ino").get("ino"));
        } catch (IOException e) {
            channel.close();
            throw e;
        }
    }

    private Object currentInode() throws IOException {
        try {
            return Files.readAttributes(path, "unix:ino").get("ino");
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    /**
     * Starts the stream of a file once its fingerprint can be computed.
     *
     * @param complete true if the file will not grow anymore, a file below the fingerprint prefix is then sent anyway
     */
    private boolean startStream(Followed file, long size, boolean complete) throws IOException,
        InterruptedException {
        if (size == 0 || size < Fingerprint.PREFIX_BYTES && !complete) {
            return false;
        }
        ByteBuffer prefix = ByteBuffer.allocate((int) Math.min(size, Fingerprint.PREFIX_BYTES));
        while (prefix.hasRemaining() && file.channel.read(prefix, prefix.position()) > 0) {
            // read until the prefix is complete
        }
        file.fingerprint = Fingerprint.of(prefix.array(), prefix.position());
        StreamStatus status = client.status(file.fingerprint).orElse(new StreamStatus(file.fingerprint, 0, false));
        file.position = status.length();
        file.finished = status.closed();
        LOGGER.info("following {} as stream {} from offset {}", path, file.fingerprint, file.position);
        return true;
    }

    private void sendUntil(Followed file, long size) throws IOException, InterruptedException {
        while (!file.finished && file.position < size) {
            buffer.clear();
            buffer.limit((int) Math.min(chunkSize, size - file.position));
            int read = file.channel.read(buffer, file.position);
            if (read <= 0) {
                return;
            }
            StreamStatus status;
            try {
                status = client.send(file.fingerprint, file.position, buffer.array(), buffer.position(), app, path);
            } catch (IngestClient.ConflictException e) {
                LOGGER.info("{}: {}, continuing there", path, e.getMessage());
                status = e.getStatus();
            }
            file.position = status.length();
            file.finished = status.closed();
        }
    }

    @Override
    public void close() throws IOException {
        if (current != null) {
            current.channel.close();
            current = null;
        }
        for (Followed file : rotated) {
            file.channel.close();
        }
        rotated.clear();
    }

    /**
     * The live file.
     *
     * @return the path
     */
    public Path path() {
        return path;
    }

    /**
     * An open file and the state of its stream.
     */
    private static final class Followed {

        private final FileChannel channel;

        private final Object inode;

        private String fingerprint;

        private long position;

        private boolean finished;

        private long lastSize;

        private Instant lastGrowth;

        private Followed(FileChannel channel, Object inode) {
            this.channel = channel;
            this.inode = inode;
        }
    }
}
