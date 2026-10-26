package de.gbv.logguy.server.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deleted files that are blocked in {@code state/deleted.json}. The stream API answers a blocked file as closed, so
 * an agent that sends it again, e.g. after losing its state, stops instead of bringing it back.
 */
public class DeletedFiles {

    private static final Logger LOGGER = LogManager.getLogger();

    private final Path file;

    private final JsonMapper json;

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /**
     * A blocked file.
     *
     * @param fingerprint fingerprint of the file
     * @param host host the file came from
     * @param app application
     * @param relativePath path below {@code hot/} or {@code archive/} it had
     * @param length bytes of the original that had arrived
     * @param deletedAt when it was deleted
     * @param user who deleted it
     */
    public record Entry(String fingerprint, String host, String app, String relativePath, long length,
        Instant deletedAt, String user) {
    }

    /**
     * Creates the list and reads {@code deleted.json} if it exists.
     *
     * @param stateDir the state directory
     * @param json mapper for the file
     * @throws IOException if the file cannot be read
     */
    public DeletedFiles(Path stateDir, JsonMapper json) throws IOException {
        this.file = stateDir.resolve("deleted.json");
        this.json = json;
        if (Files.exists(file)) {
            List<Entry> loaded = json.readValue(file.toFile(), new TypeReference<List<Entry>>() {
            });
            loaded.forEach(entry -> entries.put(entry.fingerprint(), entry));
            LOGGER.info("{} blocked files in {}", entries.size(), file);
        }
    }

    /**
     * Whether a file is blocked.
     *
     * @param fingerprint fingerprint of the file
     * @return the entry, empty if the file is not blocked
     */
    public synchronized Optional<Entry> blocked(String fingerprint) {
        return Optional.ofNullable(entries.get(fingerprint));
    }

    /**
     * Blocks a file. The list is on disk before this returns.
     *
     * @param entry the file
     * @throws IOException if the list cannot be written
     */
    public synchronized void add(Entry entry) throws IOException {
        Entry before = entries.put(entry.fingerprint(), entry);
        try {
            write();
        } catch (IOException e) {
            if (before == null) {
                entries.remove(entry.fingerprint());
            } else {
                entries.put(before.fingerprint(), before);
            }
            throw e;
        }
    }

    /**
     * Lifts the block of a file, the agent may send it again.
     *
     * @param fingerprint fingerprint of the file
     * @return the removed entry, empty if the file was not blocked
     * @throws IOException if the list cannot be written
     */
    public synchronized Optional<Entry> remove(String fingerprint) throws IOException {
        Entry removed = entries.remove(fingerprint);
        if (removed != null) {
            try {
                write();
            } catch (IOException e) {
                entries.put(fingerprint, removed);
                throw e;
            }
        }
        return Optional.ofNullable(removed);
    }

    /**
     * All blocked files.
     *
     * @return the entries, last deleted first
     */
    public synchronized List<Entry> list() {
        List<Entry> list = new ArrayList<>(entries.values());
        list.sort(Comparator.comparing(Entry::deletedAt).reversed());
        return list;
    }

    private void write() throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, json.writeValueAsBytes(List.copyOf(entries.values())));
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
