package de.gbv.logguy.server.archive;

import java.time.Instant;

/**
 * One independent zstd frame of an archive file, a line in its {@code .idx}.
 *
 * @param zst byte offset of the frame in the {@code .zst} file
 * @param zlen compressed length
 * @param raw byte offset of the frame content in the filtered file
 * @param rlen uncompressed length
 * @param src byte offset of the first original byte in the file in {@code hot/}
 * @param srcEnd end of the original bytes the frame was made of
 * @param lines number of lines in the frame
 * @param errors lines replaced by the placeholder because a filter failed
 * @param from time of the first event, the receive time if no line has a time
 * @param to time of the last event, the receive time if no line has a time
 * @param received when the last original byte arrived
 */
public record ArchiveFrame(long zst, int zlen, long raw, int rlen, long src, long srcEnd, int lines, int errors,
    Instant from, Instant to, Instant received) {

    /**
     * End of the frame content in the filtered file.
     *
     * @return {@code raw + rlen}
     */
    public long rawEnd() {
        return raw + rlen;
    }

    /**
     * End of the frame in the {@code .zst} file.
     *
     * @return {@code zst + zlen}
     */
    public long zstEnd() {
        return zst + zlen;
    }
}
