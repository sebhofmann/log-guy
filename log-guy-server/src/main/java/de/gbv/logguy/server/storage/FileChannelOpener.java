package de.gbv.logguy.server.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;

/**
 * Opens the channels a stored file writes through. In production this is {@link FileChannel#open(Path,
 * OpenOption...)}, tests use it to make writes fail after some bytes.
 */
@FunctionalInterface
public interface FileChannelOpener {

    /** Opens channels with {@link FileChannel#open(Path, OpenOption...)}. */
    FileChannelOpener DEFAULT = FileChannel::open;

    /**
     * Opens a channel.
     *
     * @param path the file
     * @param options how to open it
     * @return the channel
     * @throws IOException if the file cannot be opened
     */
    FileChannel open(Path path, OpenOption... options) throws IOException;
}
