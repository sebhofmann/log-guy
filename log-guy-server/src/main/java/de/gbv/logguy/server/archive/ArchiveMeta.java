package de.gbv.logguy.server.archive;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Content of the {@code .meta.json} of an archive file.
 *
 * @param fingerprint fingerprint of the original file
 * @param host host the file comes from
 * @param app application
 * @param created day the first original byte arrived, the same as in {@code hot/}
 * @param paths paths of the original file on the host
 * @param sourceLength original bytes already archived
 * @param length bytes of the filtered file
 * @param lines lines of the filtered file
 * @param from time of the first event, {@code null} while the file is empty
 * @param to time of the last event, {@code null} while the file is empty
 * @param closed whether the original file is complete and completely archived
 * @param filters lifecycle filters that wrote the file, as {@code name@version}
 * @param filterErrors lines replaced by the placeholder because a filter failed
 */
public record ArchiveMeta(String fingerprint, String host, String app, LocalDate created, List<String> paths,
    long sourceLength, long length, long lines, Instant from, Instant to, boolean closed, List<String> filters,
    long filterErrors) {

    /**
     * Normalizes missing lists.
     */
    public ArchiveMeta {
        paths = paths == null ? List.of() : List.copyOf(paths);
        filters = filters == null ? List.of() : List.copyOf(filters);
    }

    /**
     * Creates a copy with a new frame added.
     *
     * @param frame the frame
     * @param labels filters the frame went through
     * @param newPaths current paths of the original file
     * @return the new meta data
     */
    public ArchiveMeta with(ArchiveFrame frame, List<String> labels, List<String> newPaths) {
        List<String> allFilters = new ArrayList<>(filters);
        labels.stream().filter(label -> !allFilters.contains(label)).forEach(allFilters::add);
        Instant newFrom = from == null || frame.from().isBefore(from) ? frame.from() : from;
        Instant newTo = to == null || frame.to().isAfter(to) ? frame.to() : to;
        return new ArchiveMeta(fingerprint, host, app, created, newPaths, frame.srcEnd(), frame.rawEnd(),
            lines + frame.lines(), newFrom, newTo, closed, allFilters, filterErrors + frame.errors());
    }

    /**
     * Creates a copy with the totals of the frames, after a crash may have left the meta data behind.
     *
     * @param frames all frames of the file
     * @return the new meta data
     */
    public ArchiveMeta recount(List<ArchiveFrame> frames) {
        ArchiveMeta result = new ArchiveMeta(fingerprint, host, app, created, paths, 0, 0, 0, null, null, closed,
            filters, 0);
        for (ArchiveFrame frame : frames) {
            result = result.with(frame, List.of(), paths);
        }
        return result;
    }

    /**
     * Creates a copy for a complete file.
     *
     * @param newPaths current paths of the original file
     * @return the new meta data
     */
    public ArchiveMeta asClosed(List<String> newPaths) {
        return new ArchiveMeta(fingerprint, host, app, created, newPaths, sourceLength, length, lines, from, to, true,
            filters, filterErrors);
    }
}
