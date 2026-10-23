package de.gbv.logguy.server.archive;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.hot.FileMeta;
import tools.jackson.databind.json.JsonMapper;

/**
 * All files in {@code archive/}, found by the fingerprint of their original. The fingerprints stay known after the
 * originals left {@code hot/}, so a later backfill of the same file is recognized.
 */
public class ArchiveStore {

    private static final Logger LOGGER = LogManager.getLogger();

    private final Path root;

    /** Directory below {@code archive/} for files written again, deeper than the files are loaded from. */
    private static final String STAGING = ".refilter";

    private final JsonMapper json;

    private final ConcurrentMap<String, ArchiveFile> files = new ConcurrentHashMap<>();

    /**
     * Loads all files in {@code archive/}.
     *
     * @param properties for the data directory
     * @param json mapper for the meta data
     * @throws IOException if the directory cannot be read
     */
    public ArchiveStore(LogGuyProperties properties, JsonMapper json) throws IOException {
        this.root = properties.archiveDir();
        this.json = json;
        Files.createDirectories(root);
        deleteStaging();
        try (Stream<Path> paths = Files.walk(root, 3)) {
            for (Path meta : paths.filter(p -> p.getFileName().toString().endsWith(ArchiveFile.META_SUFFIX))
                .toList()) {
                ArchiveFile file = ArchiveFile.load(root, meta, json);
                ArchiveFile other = files.putIfAbsent(file.fingerprint(), file);
                if (other != null) {
                    LOGGER.error("fingerprint {} exists twice in the archive: {} and {}", file.fingerprint(),
                        other.relativePath(), file.relativePath());
                }
            }
        }
        LOGGER.info("loaded {} files from {}", files.size(), root);
    }

    /**
     * Finds a file.
     *
     * @param fingerprint fingerprint of the original
     * @return the file, empty if it is not archived
     */
    public Optional<ArchiveFile> find(String fingerprint) {
        return Optional.ofNullable(files.get(fingerprint));
    }

    /**
     * Finds or creates the archive file of an original.
     *
     * @param source meta data of the original in {@code hot/}
     * @return the archive file
     */
    public ArchiveFile getOrCreate(FileMeta source) {
        return files.computeIfAbsent(source.fingerprint(), fp -> {
            try {
                ArchiveFile file = ArchiveFile.create(root, source, json);
                LOGGER.info("new archive file {}", file.relativePath());
                return file;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * All files.
     *
     * @return the files, unordered
     */
    public Collection<ArchiveFile> files() {
        return files.values();
    }

    /**
     * Creates an empty file in the staging directory, to write an archive file again beside the current one.
     *
     * @param source meta data of the original in {@code hot/}
     * @return the staged file, see {@link #replace(ArchiveFile)}
     * @throws IOException if the file cannot be created
     */
    public ArchiveFile stage(FileMeta source) throws IOException {
        Path staging = root.resolve(STAGING);
        ArchiveFile file = ArchiveFile.create(staging, source, json);
        return file;
    }

    /**
     * Replaces an archive file by a staged one for the same original.
     *
     * @param staged a file from {@link #stage(FileMeta)}
     * @return the new archive file
     * @throws IOException if moving fails
     */
    public ArchiveFile replace(ArchiveFile staged) throws IOException {
        ArchiveFile current = files.get(staged.fingerprint());
        if (current == null) {
            throw new IllegalStateException(staged.fingerprint() + " is not archived");
        }
        Path meta = current.replaceBy(staged);
        ArchiveFile loaded = ArchiveFile.load(root, meta, json);
        files.put(loaded.fingerprint(), loaded);
        return loaded;
    }

    private void deleteStaging() throws IOException {
        Path staging = root.resolve(STAGING);
        if (!Files.exists(staging)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(staging)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
