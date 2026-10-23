package de.gbv.logguy.server.archive.search;

import java.util.List;

/**
 * A line found by the archive search, like a line of {@code grep -C}.
 *
 * @param app the application
 * @param host the host
 * @param file path of the archive file relative to {@code archive/}
 * @param offset byte offset of the line in the filtered file
 * @param before lines before the hit
 * @param line the line, without ANSI colors
 * @param after lines after the hit
 */
public record ArchiveHit(String app, String host, String file, long offset, List<String> before, String line,
    List<String> after) {
}
