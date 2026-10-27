package de.gbv.logguy.server.hot;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Content of the {@code .meta.json} next to every log file.
 *
 * @param fingerprint fingerprint of the original file
 * @param host host the file comes from
 * @param app application the file belongs to
 * @param created day the first byte arrived, part of the file name
 * @param paths every path the file had on the host, e.g. the live file and later the rotated {@code .gz}
 * @param firstReceived when the first byte arrived
 * @param lastReceived when the last byte arrived
 * @param length bytes stored when the meta file was written
 * @param closed true if the file is rotated and completely transferred
 * @param key secret of the file for the tokens of the pseudonymization in the archive, Base64, deleted with the file
 */
public record FileMeta(String fingerprint, String host, String app, LocalDate created, List<String> paths,
    Instant firstReceived, Instant lastReceived, long length, boolean closed, String key) {

    /**
     * Creates meta data without a key, e.g. for tests or files from before keys existed.
     *
     * @param fingerprint fingerprint of the original file
     * @param host host the file comes from
     * @param app application the file belongs to
     * @param created day the first byte arrived
     * @param paths every path the file had on the host
     * @param firstReceived when the first byte arrived
     * @param lastReceived when the last byte arrived
     * @param length bytes stored
     * @param closed true if the file is rotated and completely transferred
     */
    public FileMeta(String fingerprint, String host, String app, LocalDate created, List<String> paths,
        Instant firstReceived, Instant lastReceived, long length, boolean closed) {
        this(fingerprint, host, app, created, paths, firstReceived, lastReceived, length, closed, null);
    }

    /**
     * Creates a copy with another path added, unless it is already known.
     *
     * @param path the path on the host
     * @return the new meta data
     */
    public FileMeta withPath(String path) {
        if (path == null || path.isBlank() || paths.contains(path)) {
            return this;
        }
        List<String> newPaths = new ArrayList<>(paths);
        newPaths.add(path);
        return new FileMeta(fingerprint, host, app, created, List.copyOf(newPaths), firstReceived, lastReceived,
            length, closed, key);
    }

    /**
     * Creates a copy with new length and receive time.
     *
     * @param newLength bytes stored
     * @param received time of the last byte
     * @return the new meta data
     */
    public FileMeta withLength(long newLength, Instant received) {
        return new FileMeta(fingerprint, host, app, created, paths, firstReceived, received, newLength, closed, key);
    }

    /**
     * Creates a closed copy.
     *
     * @return the new meta data
     */
    public FileMeta asClosed() {
        return new FileMeta(fingerprint, host, app, created, paths, firstReceived, lastReceived, length, true, key);
    }

    /**
     * Creates a copy with a key.
     *
     * @param newKey the key, Base64
     * @return the new meta data
     */
    public FileMeta withKey(String newKey) {
        return new FileMeta(fingerprint, host, app, created, paths, firstReceived, lastReceived, length, closed,
            newKey);
    }

    /**
     * Leaves the key out, so it never ends up in a log.
     *
     * @return the meta data without the key
     */
    @Override
    public String toString() {
        return "FileMeta[fingerprint=" + fingerprint + ", host=" + host + ", app=" + app + ", created=" + created
            + ", paths=" + paths + ", firstReceived=" + firstReceived + ", lastReceived=" + lastReceived
            + ", length=" + length + ", closed=" + closed + "]";
    }
}
