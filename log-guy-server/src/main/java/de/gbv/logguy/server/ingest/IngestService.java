package de.gbv.logguy.server.ingest;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.common.StreamStatus;
import de.gbv.logguy.server.archive.ArchiveFile;
import de.gbv.logguy.server.archive.ArchiveMeta;
import de.gbv.logguy.server.archive.ArchiveStore;
import de.gbv.logguy.server.files.DeletedFiles;
import de.gbv.logguy.server.hot.FileMeta;
import de.gbv.logguy.server.hot.GapException;
import de.gbv.logguy.server.hot.HotFile;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.security.IngestPrincipal;

/**
 * Logic of the stream API: authorization per host and application, fingerprint check, duplicate protection.
 */
public class IngestService {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private final HotStore store;

    private final ArchiveStore archive;

    private final DeletedFiles deleted;

    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();

    /**
     * Creates the service.
     *
     * @param store the hot storage
     * @param archive the archive, it knows the files already deleted from {@code hot/}
     * @param deleted files deleted by a user and blocked, they are answered as closed
     */
    public IngestService(HotStore store, ArchiveStore archive, DeletedFiles deleted) {
        this.store = store;
        this.archive = archive;
        this.deleted = deleted;
    }

    /**
     * When each ingest token made its last request since the start.
     *
     * @return token name to time
     */
    public Map<String, Instant> lastSeen() {
        return Map.copyOf(lastSeen);
    }

    private void seen(IngestPrincipal principal) {
        lastSeen.put(principal.name(), store.now());
    }

    /**
     * How far the server has a file.
     *
     * @param principal the agent
     * @param fingerprint the fingerprint
     * @return the status
     * @throws IngestException 404 if the file is unknown or belongs to an application of another token
     */
    public StreamStatus status(IngestPrincipal principal, String fingerprint) {
        seen(principal);
        checkFingerprint(fingerprint);
        Optional<DeletedFiles.Entry> blocked = deleted.blocked(fingerprint);
        if (blocked.isPresent()) {
            if (!principal.mayWrite(blocked.get().host(), blocked.get().app())) {
                throw new IngestException(HttpStatus.NOT_FOUND, "unknown stream", null);
            }
            return status(blocked.get(), 0);
        }
        HotFile file = store.find(fingerprint).orElse(null);
        if (file == null) {
            ArchiveMeta archived = archivedOnly(fingerprint)
                .orElseThrow(() -> new IngestException(HttpStatus.NOT_FOUND, "unknown stream", null));
            if (!principal.mayWrite(archived.host(), archived.app())) {
                throw new IngestException(HttpStatus.NOT_FOUND, "unknown stream", null);
            }
            return status(archived);
        }
        FileMeta meta = file.meta();
        if (!principal.mayWrite(meta.host(), meta.app())) {
            throw new IngestException(HttpStatus.NOT_FOUND, "unknown stream", null);
        }
        return file.status();
    }

    /**
     * Appends a chunk.
     *
     * @param principal the agent
     * @param fingerprint the fingerprint
     * @param offset offset of the first byte
     * @param host host from the request
     * @param app application from the request
     * @param path path of the file on the host
     * @param data the bytes
     * @param length number of valid bytes
     * @return the new status, after the bytes are synced to disk
     * @throws IngestException 403, 409 or 400, see {@link de.gbv.logguy.common.IngestProtocol}
     */
    public StreamStatus append(IngestPrincipal principal, String fingerprint, long offset, String host, String app,
        String path, byte[] data, int length) {
        seen(principal);
        checkFingerprint(fingerprint);
        checkAccess(principal, host, app);
        if (offset < 0) {
            throw new IngestException(HttpStatus.BAD_REQUEST, "negative offset", null);
        }
        Optional<StreamStatus> blocked = blocked(fingerprint, host, app, offset + length);
        if (blocked.isPresent()) {
            return blocked.get();
        }
        HotFile file = store.find(fingerprint).orElse(null);
        if (file == null) {
            Optional<ArchiveMeta> archived = archivedOnly(fingerprint);
            if (archived.isPresent()) {
                return appendArchived(archived.get(), offset, length, host, app);
            }
            if (offset != 0) {
                throw new IngestException(HttpStatus.CONFLICT, "unknown stream, start at 0",
                    new StreamStatus(fingerprint, 0, false));
            }
            String actual = Fingerprint.of(data, length);
            if (!actual.equals(fingerprint)) {
                throw new IngestException(HttpStatus.BAD_REQUEST,
                    "fingerprint of the first " + Math.min(length, Fingerprint.PREFIX_BYTES) + " bytes is " + actual,
                    null);
            }
            file = store.getOrCreate(fingerprint, host, app);
        }
        checkOwner(file, host, app);
        try {
            return file.append(offset, data, length, path, store.now());
        } catch (GapException e) {
            throw new IngestException(HttpStatus.CONFLICT, e.getMessage(), e.getStatus());
        } catch (IOException e) {
            blocked = blocked(fingerprint, host, app, offset + length);
            if (blocked.isPresent()) {
                // deleted while the chunk was written
                return blocked.get();
            }
            LOGGER.error("cannot store chunk of {}", file.relativePath(), e);
            throw new IngestException(HttpStatus.SERVICE_UNAVAILABLE, "cannot store chunk", null);
        }
    }

    /**
     * Marks a file as complete.
     *
     * @param principal the agent
     * @param fingerprint the fingerprint
     * @param length the final length
     * @param host host from the request
     * @param app application from the request
     * @param path path of the file on the host
     * @return the new status
     * @throws IngestException 403, 404 or 409
     */
    public StreamStatus close(IngestPrincipal principal, String fingerprint, long length, String host, String app,
        String path) {
        seen(principal);
        checkFingerprint(fingerprint);
        checkAccess(principal, host, app);
        Optional<StreamStatus> blocked = blocked(fingerprint, host, app, length);
        if (blocked.isPresent()) {
            return blocked.get();
        }
        HotFile file = store.find(fingerprint).orElse(null);
        if (file == null) {
            ArchiveMeta archived = archivedOnly(fingerprint)
                .orElseThrow(() -> new IngestException(HttpStatus.NOT_FOUND, "unknown stream", null));
            checkOwner(archived.host(), archived.app(), host, app);
            if (length != archived.sourceLength()) {
                throw new IngestException(HttpStatus.CONFLICT, "stream is closed at " + archived.sourceLength(),
                    status(archived));
            }
            return status(archived);
        }
        checkOwner(file, host, app);
        try {
            return file.close(length, path, store.now());
        } catch (GapException e) {
            throw new IngestException(HttpStatus.CONFLICT, e.getMessage(), e.getStatus());
        } catch (IOException e) {
            blocked = blocked(fingerprint, host, app, length);
            if (blocked.isPresent()) {
                return blocked.get();
            }
            LOGGER.error("cannot close {}", file.relativePath(), e);
            throw new IngestException(HttpStatus.SERVICE_UNAVAILABLE, "cannot close stream", null);
        }
    }

    private static void checkFingerprint(String fingerprint) {
        if (!Fingerprint.isValid(fingerprint)) {
            throw new IngestException(HttpStatus.BAD_REQUEST, "invalid fingerprint", null);
        }
    }

    private static void checkAccess(IngestPrincipal principal, String host, String app) {
        if (host == null || app == null || !NAME.matcher(host).matches() || !NAME.matcher(app).matches()) {
            throw new IngestException(HttpStatus.BAD_REQUEST, "missing or invalid host or application", null);
        }
        if (!principal.mayWrite(host, app)) {
            throw new IngestException(HttpStatus.FORBIDDEN,
                "token " + principal.name() + " may not write " + host + "/" + app, null);
        }
    }

    private static void checkOwner(HotFile file, String host, String app) {
        FileMeta meta = file.meta();
        checkOwner(meta.host(), meta.app(), host, app);
    }

    private static void checkOwner(String ownerHost, String ownerApp, String host, String app) {
        if (!ownerHost.equals(host) || !ownerApp.equals(app)) {
            throw new IngestException(HttpStatus.FORBIDDEN, "stream belongs to " + ownerHost + "/" + ownerApp,
                null);
        }
    }

    /**
     * A file that was archived completely and then deleted from {@code hot/}. It stays a closed stream, so an agent
     * that sends it again, e.g. after losing its state, does not create a duplicate.
     */
    private Optional<ArchiveMeta> archivedOnly(String fingerprint) {
        return archive.find(fingerprint).map(ArchiveFile::meta).filter(ArchiveMeta::closed);
    }

    /**
     * A file deleted by a user is answered as closed with everything the agent sent, so the agent stops sending it.
     */
    private Optional<StreamStatus> blocked(String fingerprint, String host, String app, long sent) {
        return deleted.blocked(fingerprint).map(entry -> {
            checkOwner(entry.host(), entry.app(), host, app);
            return status(entry, sent);
        });
    }

    private static StreamStatus status(DeletedFiles.Entry entry, long sent) {
        return new StreamStatus(entry.fingerprint(), Math.max(entry.length(), sent), true);
    }

    private static StreamStatus status(ArchiveMeta archived) {
        return new StreamStatus(archived.fingerprint(), archived.sourceLength(), true);
    }

    /**
     * Same answers as {@link HotFile#append} gives for a closed file.
     */
    private static StreamStatus appendArchived(ArchiveMeta archived, long offset, int length, String host,
        String app) {
        checkOwner(archived.host(), archived.app(), host, app);
        if (offset > archived.sourceLength()) {
            throw new IngestException(HttpStatus.CONFLICT, "chunk starts at " + offset + " but stream ends at "
                + archived.sourceLength(), status(archived));
        }
        if (offset + length > archived.sourceLength()) {
            throw new IngestException(HttpStatus.CONFLICT, "stream is closed", status(archived));
        }
        return status(archived);
    }
}
