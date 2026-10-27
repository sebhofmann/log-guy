package de.gbv.logguy.server.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Appends to the growing files of {@code hot/} and {@code archive/} ({@code .log.zst}, {@code .idx},
 * {@code .tail}) so that a failed write leaves nothing behind.
 * <p>
 * The caller knows where the valid data of a file end. The new bytes are written at exactly that position, not with
 * {@link StandardOpenOption#APPEND}, and synced. If writing or syncing fails, e.g. on a full disk, the file is cut
 * back to the old end. If even that fails, a {@link DamagedFileException} tells the caller that the file now has
 * bytes behind its end. Before the next write those bytes are cut off; if that fails again, the write is refused, so
 * nothing ever lands behind a broken rest.
 */
public final class Appends {

    private static final Logger LOGGER = LogManager.getLogger();

    private Appends() {
    }

    /**
     * Opens a file, creating it if needed, and writes bytes at the end of its valid data.
     *
     * @param opener opens the channel
     * @param path the file
     * @param end where the valid data end, the new bytes start here
     * @param data the bytes
     * @throws DamagedFileException if the file is shorter than {@code end}, or a failed write cannot be undone
     * @throws IOException if writing fails, the file is cut back to {@code end}
     */
    public static void writeAt(FileChannelOpener opener, Path path, long end, byte[] data) throws IOException {
        try (FileChannel channel = opener.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            writeAt(channel, path, end, ByteBuffer.wrap(data));
        }
    }

    /**
     * Writes bytes at the end of the valid data of an open file.
     *
     * @param channel a channel opened for writing, not in append mode
     * @param path the file, for messages
     * @param end where the valid data end, the new bytes start here
     * @param data the bytes
     * @throws DamagedFileException if the file is shorter than {@code end}, or a failed write cannot be undone
     * @throws IOException if writing fails, the file is cut back to {@code end}
     */
    public static void writeAt(FileChannel channel, Path path, long end, ByteBuffer data) throws IOException {
        cutBack(channel, path, end);
        long position = end;
        try {
            while (data.hasRemaining()) {
                position += channel.write(data, position);
            }
            channel.force(false);
        } catch (IOException e) {
            try {
                channel.truncate(end);
                channel.force(false);
            } catch (IOException | RuntimeException rollback) {
                e.addSuppressed(rollback);
                throw new DamagedFileException(path, "write failed after " + (position - end)
                    + " bytes and the file cannot be cut back to " + end + " bytes", e);
            }
            LOGGER.warn("{}: write failed, cut back to {} bytes", path, end);
            throw e;
        }
    }

    /**
     * Cuts a file back to a size if it is longer, e.g. the data of a frame whose index line could not be written.
     * Errors are only logged: the next {@link #writeAt} or the repair on load cuts the bytes off as well.
     *
     * @param opener opens the channel
     * @param path the file
     * @param size where the valid data end
     */
    public static void cutBackQuietly(FileChannelOpener opener, Path path, long size) {
        try (FileChannel channel = opener.open(path, StandardOpenOption.WRITE)) {
            if (channel.size() > size) {
                channel.truncate(size);
                channel.force(false);
            }
        } catch (IOException e) {
            LOGGER.debug("{}: cannot cut back to {} bytes", path, size, e);
        }
    }

    /**
     * Cuts off bytes behind {@code end}, left by a failed write whose rollback failed as well.
     */
    private static void cutBack(FileChannel channel, Path path, long end) throws IOException {
        long size = channel.size();
        if (size < end) {
            throw new DamagedFileException(path, "has " + size + " bytes, expected " + end, null);
        }
        if (size > end) {
            LOGGER.warn("{}: cutting {} bytes behind the end at {}, left by a failed write", path, size - end, end);
            try {
                channel.truncate(end);
                channel.force(false);
            } catch (IOException e) {
                throw new DamagedFileException(path, "cannot cut " + (size - end) + " bytes behind " + end, e);
            }
        }
    }
}
