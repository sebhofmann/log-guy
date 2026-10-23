package de.gbv.logguy.server.ingest;

import java.io.IOException;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.common.StreamStatus;
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

    /**
     * Creates the service.
     *
     * @param store the hot storage
     */
    public IngestService(HotStore store) {
        this.store = store;
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
        checkFingerprint(fingerprint);
        HotFile file = store.find(fingerprint)
            .orElseThrow(() -> new IngestException(HttpStatus.NOT_FOUND, "unknown stream", null));
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
        checkFingerprint(fingerprint);
        checkAccess(principal, host, app);
        if (offset < 0) {
            throw new IngestException(HttpStatus.BAD_REQUEST, "negative offset", null);
        }
        HotFile file = store.find(fingerprint).orElse(null);
        if (file == null) {
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
        checkFingerprint(fingerprint);
        checkAccess(principal, host, app);
        HotFile file = store.find(fingerprint)
            .orElseThrow(() -> new IngestException(HttpStatus.NOT_FOUND, "unknown stream", null));
        checkOwner(file, host, app);
        try {
            return file.close(length, path, store.now());
        } catch (GapException e) {
            throw new IngestException(HttpStatus.CONFLICT, e.getMessage(), e.getStatus());
        } catch (IOException e) {
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
        if (!meta.host().equals(host) || !meta.app().equals(app)) {
            throw new IngestException(HttpStatus.FORBIDDEN,
                "stream belongs to " + meta.host() + "/" + meta.app(), null);
        }
    }
}
