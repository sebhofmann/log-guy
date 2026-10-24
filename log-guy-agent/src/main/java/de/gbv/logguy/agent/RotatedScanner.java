package de.gbv.logguy.agent;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Watches the rotated files of a source and delivers what the server is missing. Files the server has completely are
 * remembered by path, size and modification time, so they are not read again.
 */
public class RotatedScanner {

    private static final Logger LOGGER = LogManager.getLogger();

    private final FileSender sender;

    private final String app;

    private final GlobFiles glob;

    private final Set<Path> warned = new HashSet<>();

    private final Map<Path, String> complete = new HashMap<>();

    /**
     * Creates the scanner.
     *
     * @param sender sends a complete file
     * @param app the application
     * @param glob glob pattern of the rotated files, e.g. {@code /logs/digibib/*}{@code /mycore-app-*.log.gz}
     */
    public RotatedScanner(FileSender sender, String app, String glob) {
        this.sender = sender;
        this.app = app;
        this.glob = new GlobFiles(glob);
    }

    /**
     * Finds the rotated files and delivers what is missing.
     *
     * @throws InterruptedException if interrupted
     */
    public void scan() throws InterruptedException {
        for (Path file : files()) {
            String key = key(file);
            if (key == null || key.equals(complete.get(file))) {
                continue;
            }
            try {
                if (sender.send(file, app).map(s -> s.closed()).orElse(true)) {
                    complete.put(file, key);
                }
            } catch (IngestClient.RejectedException e) {
                LOGGER.error("{} refused: {}", file, e.getMessage());
                complete.put(file, key);
            } catch (AccessDeniedException e) {
                // tried again when the file changes or the agent restarts
                LOGGER.error("cannot read {}, check the rights of the agent", file);
                complete.put(file, key);
            } catch (IOException e) {
                LOGGER.warn("cannot send {}: {}", file, e.getMessage());
                return;
            }
        }
        complete.keySet().removeIf(path -> !Files.exists(path));
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

    private static String key(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return attributes.size() + "/" + attributes.lastModifiedTime().toMillis();
        } catch (IOException e) {
            return null;
        }
    }
}
