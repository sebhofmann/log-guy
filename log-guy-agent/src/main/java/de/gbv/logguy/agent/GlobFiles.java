package de.gbv.logguy.agent;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The files matching a glob pattern like {@code /logs/digibib/*}{@code /mycore-app-*.log.gz}. Only the directories
 * below the fixed part of the pattern are searched, and a directory that cannot be read does not stop the search.
 */
public class GlobFiles {

    private final Path base;

    private final PathMatcher matcher;

    private final int depth;

    /**
     * Prepares the search.
     *
     * @param glob the absolute glob pattern
     */
    public GlobFiles(String glob) {
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
     * What a search found.
     *
     * @param files the matching regular files, sorted
     * @param unreadable directories that could not be listed, sorted
     */
    public record Result(List<Path> files, List<Path> unreadable) {
    }

    /**
     * The directory where the search starts.
     *
     * @return the fixed part of the pattern
     */
    public Path base() {
        return base;
    }

    /**
     * Searches the files.
     *
     * @return files and unreadable directories, both empty if the base directory does not exist
     */
    public Result find() {
        List<Path> files = new ArrayList<>();
        List<Path> unreadable = new ArrayList<>();
        if (!Files.isDirectory(base)) {
            return new Result(List.of(), List.of());
        }
        try {
            Files.walkFileTree(base, Set.<FileVisitOption>of(), depth, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && matcher.matches(file)) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    if (Files.isDirectory(file)) {
                        unreadable.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            unreadable.add(base);
        }
        files.sort(null);
        unreadable.sort(null);
        return new Result(files, unreadable);
    }
}
