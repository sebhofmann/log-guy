package de.gbv.logguy.server.retention;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.gbv.logguy.server.archive.ArchiveFile;
import de.gbv.logguy.server.archive.ArchiveMeta;
import de.gbv.logguy.server.archive.ArchiveStore;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.hot.FileMeta;
import de.gbv.logguy.server.hot.HotFile;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.index.Indexer;
import de.gbv.logguy.server.script.ParserRegistry;

/**
 * Deletes originals from {@code hot/} once their days are over: a file goes when it is closed, completely archived
 * and indexed, and its last event is older than {@code log-guy.archive.hot-days} days. Days are counted like
 * {@code index-days}: today and the {@code hot-days} days before stay.
 */
public class HotCleaner {

    private static final Logger LOGGER = LogManager.getLogger();

    private final LogGuyProperties properties;

    private final HotStore hot;

    private final ArchiveStore archive;

    private final Indexer indexer;

    private final ParserRegistry parsers;

    private final Clock clock;

    /**
     * Creates the cleaner.
     *
     * @param properties configuration
     * @param hot the originals
     * @param archive the archive
     * @param indexer the indexer, {@code null} if indexing is disabled
     * @param parsers the parsers, a file of an application without parser is never indexed
     * @param clock clock for the limit
     */
    public HotCleaner(LogGuyProperties properties, HotStore hot, ArchiveStore archive, Indexer indexer,
        ParserRegistry parsers, Clock clock) {
        this.properties = properties;
        this.hot = hot;
        this.archive = archive;
        this.indexer = indexer;
        this.parsers = parsers;
        this.clock = clock;
    }

    /**
     * Files whose last event is before this instant may leave {@code hot/}.
     *
     * @return the start of the oldest day that stays
     */
    public Instant limit() {
        return limit(properties, clock.instant());
    }

    /**
     * Files whose last event is before this instant may leave {@code hot/}: the start of the day {@code hot-days}
     * ago in {@code log-guy.zone}.
     *
     * @param properties configuration
     * @param now the current time
     * @return the start of the oldest day that stays
     */
    public static Instant limit(LogGuyProperties properties, Instant now) {
        ZoneId zone = properties.zone();
        LocalDate first = LocalDate.ofInstant(now, zone).minusDays(properties.archive().hotDays());
        return first.atStartOfDay(zone).toInstant();
    }

    /**
     * Deletes all files whose days are over.
     *
     * @return relative paths of the deleted files
     */
    public List<String> run() {
        Instant limit = limit();
        List<String> deleted = new ArrayList<>();
        for (HotFile file : List.copyOf(hot.files())) {
            if (!deletable(file, limit)) {
                continue;
            }
            try {
                hot.delete(file);
                if (indexer != null) {
                    indexer.forget(file.fingerprint());
                }
                deleted.add(file.relativePath());
            } catch (IOException e) {
                LOGGER.error("cannot delete {} from hot/", file.relativePath(), e);
            }
        }
        LOGGER.info("deleted {} files from hot/ with events before {}", deleted.size(), limit);
        return deleted;
    }

    /**
     * Whether a file may leave {@code hot/}.
     *
     * @param file the original
     * @param limit see {@link #limit()}
     * @return true if it is closed, archived, indexed and its last event is before the limit
     */
    boolean deletable(HotFile file, Instant limit) {
        FileMeta meta = file.meta();
        if (!meta.closed()) {
            return false;
        }
        Optional<ArchiveMeta> archived = archive.find(file.fingerprint()).map(ArchiveFile::meta);
        if (archived.isEmpty() || !archived.get().closed() || archived.get().sourceLength() != file.length()) {
            return false;
        }
        Instant last = archived.get().to() != null ? archived.get().to() : meta.lastReceived();
        if (!last.isBefore(limit)) {
            return false;
        }
        return indexer == null || parsers.forApp(meta.app()).isEmpty() || indexer.isIndexed(file.fingerprint());
    }
}
