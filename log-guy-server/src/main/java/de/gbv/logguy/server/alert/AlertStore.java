package de.gbv.logguy.server.alert;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import tools.jackson.databind.json.JsonMapper;

/**
 * The alerts in {@code state/alerts.ndjson}: one line per change, the last line of an id counts. The file is
 * compacted at the start.
 */
public class AlertStore implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger();

    private final Path file;

    private final JsonMapper json;

    private BufferedWriter writer;

    /**
     * Creates the store.
     *
     * @param stateDir the state directory
     * @param json mapper for the lines
     */
    public AlertStore(Path stateDir, JsonMapper json) {
        this.file = stateDir.resolve("alerts.ndjson");
        this.json = json;
    }

    /**
     * Reads the alerts and rewrites the file with one line per alert.
     *
     * @return the alerts, oldest first
     * @throws IOException if the file cannot be read or written
     */
    public synchronized List<Alert> load() throws IOException {
        Files.createDirectories(file.getParent());
        Map<String, Alert> alerts = new LinkedHashMap<>();
        if (Files.exists(file)) {
            int broken = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    Alert alert = json.readValue(line, Alert.class);
                    alerts.put(alert.id(), alert);
                } catch (RuntimeException e) {
                    broken++;
                }
            }
            if (broken > 0) {
                LOGGER.warn("{} broken lines in {} skipped", broken, file);
            }
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (BufferedWriter out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            for (Alert alert : alerts.values()) {
                out.write(json.writeValueAsString(alert));
                out.newLine();
            }
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        return List.copyOf(alerts.values());
    }

    /**
     * Appends changed alerts.
     *
     * @param alerts the alerts in their new state
     * @throws IOException if they cannot be written
     */
    public synchronized void append(Collection<Alert> alerts) throws IOException {
        if (alerts.isEmpty() || writer == null) {
            return;
        }
        for (Alert alert : alerts) {
            writer.write(json.writeValueAsString(alert));
            writer.newLine();
        }
        writer.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }
}
