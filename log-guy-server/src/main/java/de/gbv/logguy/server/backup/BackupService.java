package de.gbv.logguy.server.backup;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.gbv.logguy.server.archive.ArchiveFile;
import de.gbv.logguy.server.archive.ArchiveStore;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.security.ControlToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * Copies what must survive the loss of the server into the backup directory: {@code archive/} file by file in a
 * consistent state, {@code state/}, {@code audit/} and the configuration directory as mirrors. {@code hot/} is left
 * out, its files come from the agents again. A backup tool takes the directory from there, it never sees a file in
 * the middle of a write.
 */
public class BackupService {

    /** Name of the report in the backup directory. */
    public static final String REPORT_FILE = "backup.json";

    private static final Logger LOGGER = LogManager.getLogger();

    private static final String TMP_SUFFIX = ".tmp";

    private final LogGuyProperties properties;

    private final ArchiveStore archive;

    private final JsonMapper json;

    private final Clock clock;

    private final ReentrantLock running = new ReentrantLock();

    private volatile Report last;

    /**
     * Creates the service and reads the report of the last backup.
     *
     * @param properties configuration
     * @param archive the archive, {@code null} if it is switched off
     * @param json mapper for the report
     * @param clock clock for the report
     */
    public BackupService(LogGuyProperties properties, ArchiveStore archive, JsonMapper json, Clock clock) {
        this.properties = properties;
        this.archive = archive;
        this.json = json;
        this.clock = clock;
        if (enabled()) {
            Path dir = properties.backup().dir().toAbsolutePath().normalize();
            if (dir.startsWith(properties.dataDir().toAbsolutePath().normalize())
                || properties.backup().configDir() != null
                    && dir.startsWith(properties.backup().configDir().toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("log-guy.backup.dir " + dir
                    + " must be outside of the data and configuration directory");
            }
            Path report = dir.resolve(REPORT_FILE);
            if (Files.exists(report)) {
                try {
                    last = json.readValue(report.toFile(), Report.class);
                } catch (RuntimeException e) {
                    LOGGER.warn("cannot read {}: {}", report, e.toString());
                }
            }
        }
    }

    /**
     * What a backup did.
     *
     * @param started start
     * @param finished end
     * @param archiveFiles files in the archive
     * @param archiveCopied archive files that were new or had changed
     * @param archiveBytes compressed bytes written for the archive
     * @param otherCopied files of {@code state/}, {@code audit/} and the configuration that were new or had changed
     * @param otherDeleted files removed from the mirrors because they are gone
     * @param errors what failed, empty if the backup is complete
     */
    public record Report(Instant started, Instant finished, int archiveFiles, int archiveCopied, long archiveBytes,
        int otherCopied, int otherDeleted, List<String> errors) {

        /**
         * Whether everything was copied.
         *
         * @return {@code true} without errors
         */
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /**
     * Shown on the status page.
     *
     * @param enabled whether a backup directory is set
     * @param dir the backup directory
     * @param cron when it runs
     * @param running whether a backup is running now
     * @param last the last backup, also from before the start of the server
     */
    public record Status(boolean enabled, String dir, String cron, boolean running, Report last) {
    }

    /**
     * Whether a backup directory is set.
     *
     * @return {@code true} if backups are switched on
     */
    public boolean enabled() {
        return properties.backup().enabled();
    }

    /**
     * The state for the status page.
     *
     * @return directory, schedule and the last backup
     */
    public Status status() {
        return new Status(enabled(), enabled() ? properties.backup().dir().toString() : null,
            properties.backup().cron(), running.isLocked(), last);
    }

    /**
     * Runs a backup by schedule. Errors are in the report and in the log.
     */
    public void scheduled() {
        try {
            run();
        } catch (IllegalStateException e) {
            LOGGER.warn("scheduled backup skipped: {}", e.getMessage());
        }
    }

    /**
     * Runs a backup now.
     *
     * @return the report, also written to {@code backup.json} in the backup directory
     * @throws IllegalStateException if backups are off or a backup is already running
     */
    public Report run() {
        if (!enabled()) {
            throw new IllegalStateException("Kein Backup-Verzeichnis gesetzt (log-guy.backup.dir)");
        }
        if (!running.tryLock()) {
            throw new IllegalStateException("Es läuft bereits ein Backup");
        }
        try {
            Report report = backup(properties.backup().dir());
            last = report;
            return report;
        } finally {
            running.unlock();
        }
    }

    private Report backup(Path target) {
        Instant started = clock.instant();
        List<String> errors = new ArrayList<>();
        int archiveFiles = 0;
        int archiveCopied = 0;
        long archiveBytes = 0;
        Counter other = new Counter();
        try {
            Files.createDirectories(target);
            if (archive != null) {
                Path archiveTarget = target.resolve("archive");
                for (ArchiveFile file : archive.files()) {
                    archiveFiles++;
                    try {
                        long written = file.backupTo(archiveTarget);
                        if (written >= 0) {
                            archiveCopied++;
                            archiveBytes += written;
                        }
                    } catch (IOException | RuntimeException e) {
                        LOGGER.error("backup of archive/{} failed", file.relativePath(), e);
                        errors.add("archive/" + file.relativePath() + ": " + e.getMessage());
                    }
                }
            }
            mirror(properties.stateDir(), target.resolve("state"), other, errors);
            mirror(properties.dataDir().resolve("audit"), target.resolve("audit"), other, errors);
            if (properties.backup().configDir() != null) {
                mirror(properties.backup().configDir(), target.resolve("config"), other, errors);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("backup to {} failed", target, e);
            errors.add(e.getMessage());
        }
        Report report = new Report(started, clock.instant(), archiveFiles, archiveCopied, archiveBytes, other.copied,
            other.deleted, List.copyOf(errors));
        try {
            Path tmp = target.resolve(REPORT_FILE + TMP_SUFFIX);
            Files.write(tmp, json.writeValueAsBytes(report));
            Files.move(tmp, target.resolve(REPORT_FILE), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("cannot write the backup report to {}", target, e);
        }
        if (report.ok()) {
            LOGGER.info("backup to {} done in {} ms: {} of {} archive files with {} bytes, {} other files, {} deleted",
                target, report.finished().toEpochMilli() - started.toEpochMilli(), archiveCopied, archiveFiles,
                archiveBytes, other.copied, other.deleted);
        } else {
            LOGGER.error("backup to {} incomplete, {} errors", target, errors.size());
        }
        return report;
    }

    private static final class Counter {

        private int copied;

        private int deleted;
    }

    /**
     * Makes {@code target} a copy of {@code source}. Files are compared by size and modification time and written
     * atomically. Lines files ({@code .ndjson}) are cut after the last complete line.
     */
    private void mirror(Path source, Path target, Counter counter, List<String> errors) throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }
        Files.createDirectories(target);
        Set<Path> present = new HashSet<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(source)) {
            files = walk.filter(Files::isRegularFile).filter(path -> !skipped(path)).toList();
        }
        for (Path file : files) {
            Path relative = source.relativize(file);
            present.add(relative);
            Path copy = target.resolve(relative.toString());
            try {
                FileTime modified = Files.getLastModifiedTime(file);
                boolean lines = file.getFileName().toString().endsWith(".ndjson");
                // a copy of a lines file may be shorter by an incomplete last line
                if (Files.exists(copy) && Files.getLastModifiedTime(copy).equals(modified)
                    && (lines || Files.size(copy) == Files.size(file))) {
                    continue;
                }
                byte[] content = Files.readAllBytes(file);
                if (lines) {
                    int end = content.length;
                    while (end > 0 && content[end - 1] != '\n') {
                        end--;
                    }
                    if (end < content.length) {
                        content = Arrays.copyOf(content, end);
                    }
                }
                Files.createDirectories(copy.getParent());
                Path tmp = copy.resolveSibling(copy.getFileName() + TMP_SUFFIX);
                try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                    channel.write(ByteBuffer.wrap(content));
                    channel.force(false);
                }
                Files.setLastModifiedTime(tmp, modified);
                Files.move(tmp, copy, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                counter.copied++;
            } catch (IOException e) {
                if (Files.exists(file)) {
                    LOGGER.error("backup of {} failed", file, e);
                    errors.add(file + ": " + e.getMessage());
                }
            }
        }
        List<Path> copies;
        try (Stream<Path> walk = Files.walk(target)) {
            copies = walk.filter(Files::isRegularFile).toList();
        }
        for (Path copy : copies) {
            if (!present.contains(target.relativize(copy))) {
                Files.delete(copy);
                counter.deleted++;
            }
        }
    }

    /**
     * Files that are not copied: the control token, which is new at every start, and files in the middle of a write.
     */
    private static boolean skipped(Path path) {
        String name = path.getFileName().toString();
        return name.equals(ControlToken.FILE_NAME) || name.endsWith(TMP_SUFFIX);
    }
}
