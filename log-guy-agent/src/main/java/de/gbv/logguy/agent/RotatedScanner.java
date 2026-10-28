package de.gbv.logguy.agent;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.common.StreamStatus;

/**
 * Watches the rotated files of a source and delivers what the server is missing. Files whose stream the server has
 * closed are remembered by path, size and modification time, so they are not read again.
 * <p>
 * A {@code .gz} file is complete, its stream is closed right away. An uncompressed rotated file, e.g.
 * {@code access.log.1}, may still be written by a process that has not reopened its log yet. It is sent up to its
 * current end and only closed once it has not been modified for {@code rotation-grace}. A file below the
 * fingerprint prefix, e.g. a quiet {@code error.log.1}, is only sent then, because its fingerprint covers all of it.
 * <p>
 * A file the agent may not read is tried again with every scan. A file that is broken, e.g. a truncated {@code .gz},
 * is only tried again once it changes. A file the server refuses, e.g. because the token may not write the
 * application, is tried again after {@code rotated-scan-interval}, doubled with every refusal up to
 * {@code rotated-retry-max}. A file that changes is tried again right away. None of them stops the scan, only an
 * unreachable or failing server does.
 */
public class RotatedScanner {

    private static final Logger LOGGER = LogManager.getLogger();

    private final FileSender sender;

    private final String app;

    private final GlobFiles glob;

    private final Duration rotationGrace;

    private final Duration retryDelay;

    private final Duration retryMax;

    private final Clock clock;

    private final Set<Path> warned = new HashSet<>();

    private final Map<Path, String> complete = new HashMap<>();

    private final Map<Path, Failure> failed = new HashMap<>();

    /**
     * Creates the scanner.
     *
     * @param sender sends a file
     * @param app the application
     * @param glob glob pattern of the rotated files, e.g. {@code /logs/digibib/*}{@code /mycore-app-*.log.gz}
     * @param rotationGrace how long an uncompressed file must stay unmodified before its stream is closed
     * @param retryDelay first wait before a refused file is tried again
     * @param retryMax longest wait before a refused file is tried again
     */
    public RotatedScanner(FileSender sender, String app, String glob, Duration rotationGrace, Duration retryDelay,
        Duration retryMax) {
        this(sender, app, glob, rotationGrace, retryDelay, retryMax, Clock.systemUTC());
    }

    RotatedScanner(FileSender sender, String app, String glob, Duration rotationGrace, Duration retryDelay,
        Duration retryMax, Clock clock) {
        this.sender = sender;
        this.app = app;
        this.glob = new GlobFiles(glob);
        this.rotationGrace = rotationGrace;
        this.retryDelay = retryDelay;
        this.retryMax = retryMax;
        this.clock = clock;
    }

    /**
     * Finds the rotated files and delivers what is missing.
     *
     * @throws InterruptedException if interrupted
     */
    public void scan() throws InterruptedException {
        Instant now = clock.instant();
        for (Path file : files()) {
            BasicFileAttributes attributes = attributes(file);
            if (attributes == null) {
                continue;
            }
            String key = attributes.size() + "/" + attributes.lastModifiedTime().toMillis();
            if (key.equals(complete.get(file))) {
                continue;
            }
            Failure failure = failed.get(file);
            if (failure != null && failure.key().equals(key)
                && (failure.kind() == Kind.BROKEN || now.isBefore(failure.next()))) {
                continue;
            }
            boolean close = file.getFileName().toString().endsWith(".gz")
                || !attributes.lastModifiedTime().toInstant().plus(rotationGrace).isAfter(now);
            if (!close && attributes.size() < Fingerprint.PREFIX_BYTES) {
                // a small file is fingerprinted over all of it, so it waits until nothing writes to it anymore
                continue;
            }
            try {
                boolean closed = sender.send(file, app, close).map(StreamStatus::closed).orElse(close);
                if (failed.remove(file) != null) {
                    LOGGER.info("{} can be sent again", file);
                }
                if (closed) {
                    complete.put(file, key);
                }
            } catch (IngestClient.RejectedException e) {
                boolean again = failure != null && failure.kind() == Kind.REFUSED;
                Duration delay = again ? failure.delay().multipliedBy(2) : retryDelay;
                delay = delay.compareTo(retryMax) > 0 ? retryMax : delay;
                failed.put(file, new Failure(key, now.plus(delay), delay, Kind.REFUSED));
                if (!again) {
                    LOGGER.error("{} refused, trying again in {} and then with growing waits up to {}: {}", file, delay,
                        retryMax, e.getMessage());
                }
            } catch (IngestClient.ServerException e) {
                LOGGER.warn("cannot send {}: {}", file, e.getMessage());
                return;
            } catch (AccessDeniedException e) {
                failed.put(file, new Failure(key, now, Duration.ZERO, Kind.UNREADABLE));
                if (failure == null || failure.kind() != Kind.UNREADABLE) {
                    LOGGER.error("cannot read {}, check the rights of the agent, trying again with every scan", file);
                }
            } catch (IOException e) {
                failed.put(file, new Failure(key, now, Duration.ZERO, Kind.BROKEN));
                if (failure == null || failure.kind() != Kind.BROKEN) {
                    LOGGER.error("cannot read {}, trying again once it changes: {}", file, e.toString());
                }
            }
        }
        complete.keySet().removeIf(path -> !Files.exists(path));
        failed.keySet().removeIf(path -> !Files.exists(path));
    }

    /**
     * The rotated files that could not be delivered because they are unreadable, broken or the server refused them.
     * They are tried again.
     *
     * @return the files waiting for another attempt
     */
    public Set<Path> failed() {
        return Set.copyOf(failed.keySet());
    }

    private List<Path> files() {
        GlobFiles.Result result = glob.find();
        for (Path dir : result.unreadable()) {
            if (warned.add(dir)) {
                LOGGER.warn("cannot list {}, its rotated files are skipped", dir);
            }
        }
        warned.retainAll(result.unreadable());
        return result.files();
    }

    private static BasicFileAttributes attributes(Path file) {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * A file that could not be delivered.
     *
     * @param key size and modification time at the attempt
     * @param next earliest time of the next attempt
     * @param delay wait before the next attempt
     * @param kind why the file could not be delivered
     */
    private record Failure(String key, Instant next, Duration delay, Kind kind) {
    }

    /**
     * Why a file could not be delivered.
     */
    private enum Kind {
        /** The server refused the file. */
        REFUSED,
        /** The agent may not read the file. */
        UNREADABLE,
        /** The file cannot be read to its end, e.g. a truncated {@code .gz}. */
        BROKEN
    }
}
