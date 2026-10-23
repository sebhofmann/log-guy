package de.gbv.logguy.server.hot;

import java.time.Instant;

/**
 * One independent zstd frame, a line in the {@code .idx} file.
 *
 * @param zst byte offset of the frame in the {@code .zst} file
 * @param zlen compressed length
 * @param raw byte offset of the frame content in the original file
 * @param rlen uncompressed length
 * @param received when the last byte of the frame arrived
 */
public record Frame(long zst, int zlen, long raw, int rlen, Instant received) {

    /**
     * End of the frame content in the original file.
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
