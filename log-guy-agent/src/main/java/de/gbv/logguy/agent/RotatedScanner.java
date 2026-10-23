package de.gbv.logguy.agent;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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

    private final Path base;

    private final PathMatcher matcher;

    private final int depth;

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
        Path pattern = Path.of(glob);
        Path fixed = pattern.getRoot() == null ? Path.of("") : pattern.getRoot();
        int i = 0;
        for (; i < pattern.getNameCount() - 1; i++) {
            String part = pattern.getName(i).toString();
            if (part.contains("*") || part.contains("?") || part.contains("[") || part.contains("{")) {
                break;
            }
            fixed = fixed.resolve(part);
        }
        this.base = fixed;
        this.depth = pattern.getNameCount() - i;
        this.matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
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
            } catch (IOException e) {
                LOGGER.warn("cannot send {}: {}", file, e.getMessage());
                return;
            }
        }
        complete.keySet().removeIf(path -> !Files.exists(path));
    }

    private List<Path> files() {
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.find(base, depth,
            (path, attributes) -> attributes.isRegularFile() && matcher.matches(path))) {
            paths.sorted(Comparator.naturalOrder()).forEach(files::add);
        } catch (IOException e) {
            LOGGER.warn("cannot list {}: {}", base, e.getMessage());
        }
        return files;
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
