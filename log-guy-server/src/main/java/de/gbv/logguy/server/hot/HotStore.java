package de.gbv.logguy.server.hot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.gbv.logguy.server.config.LogGuyProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * All log files in {@code hot/}, by fingerprint.
 */
public class HotStore implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger();

    private final LogGuyProperties properties;

    private final JsonMapper json;

    private final Clock clock;

    private final ConcurrentMap<String, HotFile> files = new ConcurrentHashMap<>();

    private final List<Consumer<HotFile>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Creates the store and loads all files from {@code hot/}.
     *
     * @param properties configuration
     * @param json mapper for the meta files
     * @param clock clock for receive times
     * @throws IOException if {@code hot/} cannot be read
     */
    public HotStore(LogGuyProperties properties, JsonMapper json, Clock clock) throws IOException {
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        load();
    }

    private void load() throws IOException {
        Path root = properties.hotDir();
        Files.createDirectories(root);
        Instant now = clock.instant();
        try (Stream<Path> paths = Files.walk(root, 3)) {
            for (Path meta : paths.filter(p -> p.getFileName().toString().endsWith(HotFile.META_SUFFIX)).toList()) {
                HotFile file = HotFile.load(root, meta, properties.hot(), json, now);
                HotFile other = files.putIfAbsent(file.fingerprint(), file);
                if (other != null) {
                    LOGGER.error("fingerprint {} exists twice: {} and {}", file.fingerprint(), other.relativePath(),
                        file.relativePath());
                }
            }
        }
        LOGGER.info("loaded {} files from {}", files.size(), root);
    }

    /**
     * Looks up a file.
     *
     * @param fingerprint the fingerprint
     * @return the file, if it exists
     */
    public Optional<HotFile> find(String fingerprint) {
        return Optional.ofNullable(files.get(fingerprint));
    }

    /**
     * Returns the file with the fingerprint or creates it.
     *
     * @param fingerprint the fingerprint, already verified against the content
     * @param host host the file comes from, already validated
     * @param app application, already validated
     * @return the existing or new file
     */
    public HotFile getOrCreate(String fingerprint, String host, String app) {
        return files.computeIfAbsent(fingerprint, fp -> {
            Instant now = clock.instant();
            LocalDate day = LocalDate.ofInstant(now, properties.zone());
            try {
                HotFile file = HotFile.create(properties.hotDir(), fp, host, app, day, now, properties.hot(), json);
                LOGGER.info("new file {}", file.relativePath());
                return file;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * All files.
     *
     * @return a live view of all files
     */
    public Collection<HotFile> files() {
        return files.values();
    }

    /**
     * Registers a listener that is called after new bytes arrived or a file was closed.
     *
     * @param listener the listener
     */
    public void addListener(Consumer<HotFile> listener) {
        listeners.add(listener);
    }

    /**
     * Tells the listeners that a file changed.
     *
     * @param file the changed file
     */
    public void changed(HotFile file) {
        for (Consumer<HotFile> listener : listeners) {
            listener.accept(file);
        }
    }

    /**
     * Writes frames for pending bytes that are older than {@code frame-max-age}.
     */
    public void flushAged() {
        Instant now = clock.instant();
        for (HotFile file : files.values()) {
            try {
                file.flushAged(now);
            } catch (IOException e) {
                LOGGER.error("cannot write frame of {}", file.relativePath(), e);
            }
        }
    }

    /**
     * Current time of the store's clock.
     *
     * @return now
     */
    public Instant now() {
        return clock.instant();
    }

    @Override
    public void close() {
        for (HotFile file : files.values()) {
            try {
                file.release();
            } catch (IOException e) {
                LOGGER.warn("cannot release {}", file.relativePath(), e);
            }
        }
    }
}
