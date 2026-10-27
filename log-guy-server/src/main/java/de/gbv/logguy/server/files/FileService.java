package de.gbv.logguy.server.files;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.re2j.PatternSyntaxException;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.server.archive.ArchiveFile;
import de.gbv.logguy.server.archive.ArchiveMeta;
import de.gbv.logguy.server.archive.ArchiveStore;
import de.gbv.logguy.server.archive.ArchiveWriter;
import de.gbv.logguy.server.backup.BackupService;
import de.gbv.logguy.server.hot.FileMeta;
import de.gbv.logguy.server.hot.HotFile;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.index.EventDocuments;
import de.gbv.logguy.server.index.Indexer;
import de.gbv.logguy.server.index.Indexer.DocumentSink;
import de.gbv.logguy.server.reindex.ReindexService;

/**
 * The log browser: lists the files of {@code hot/} and {@code archive/}, reads them page by page, searches in them
 * and deletes them. A file is shown either as the original in {@code hot/} or as the filtered file in the archive.
 */
public class FileService {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");

    private static final int CHUNK = 1 << 20;

    private final FilesProperties settings;

    private final HotStore hot;

    private final ArchiveStore archive;

    private final ArchiveWriter writer;

    private final Indexer indexer;

    private final DocumentSink sink;

    private final ReindexService reindex;

    private final DeletedFiles deleted;

    private final BackupService backup;

    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param settings settings of the log browser
     * @param hot the originals
     * @param archive the archive
     * @param writer the archive writer, {@code null} if it does not run
     * @param indexer the indexer, {@code null} if it does not run
     * @param sink the index, for deleting the events of a file
     * @param reindex loads events from the archive, {@code null} if it does not run
     * @param deleted the blocked files
     * @param backup the backup, for deleting the copies of deleted files
     * @param clock clock for the time of a deletion
     */
    public FileService(FilesProperties settings, HotStore hot, ArchiveStore archive, ArchiveWriter writer,
        Indexer indexer, DocumentSink sink, ReindexService reindex, DeletedFiles deleted, BackupService backup,
        Clock clock) {
        this.settings = settings;
        this.hot = hot;
        this.archive = archive;
        this.writer = writer;
        this.indexer = indexer;
        this.sink = sink;
        this.reindex = reindex;
        this.deleted = deleted;
        this.backup = backup;
        this.clock = clock;
    }

    /**
     * Which form of a file is read.
     */
    public enum Source {
        /** The original in {@code hot/}. */
        HOT,
        /** The filtered file in the archive. */
        ARCHIVE
    }

    /**
     * A file with its forms.
     *
     * @param fingerprint fingerprint
     * @param host host the file comes from
     * @param app application
     * @param created day the first byte arrived
     * @param paths paths of the file on the host
     * @param hot the original, {@code null} if it is no longer in {@code hot/}
     * @param archive the filtered file, {@code null} if it is not archived
     * @param indexed whether the original is completely indexed, {@code null} if it is not in {@code hot/}
     */
    public record FileInfo(String fingerprint, String host, String app, LocalDate created, List<String> paths,
        HotInfo hot, ArchiveInfo archive, Boolean indexed) {
    }

    /**
     * The original in {@code hot/}.
     *
     * @param path path below {@code hot/}
     * @param length bytes
     * @param firstReceived when the first byte arrived
     * @param lastReceived when the last byte arrived
     * @param closed whether the file is complete
     */
    public record HotInfo(String path, long length, Instant firstReceived, Instant lastReceived, boolean closed) {
    }

    /**
     * The filtered file in the archive.
     *
     * @param path path below {@code archive/}
     * @param length bytes of the filtered file
     * @param sourceLength bytes of the original already archived
     * @param compressedSize bytes on disk
     * @param lines lines of the filtered file
     * @param from time of the first event
     * @param to time of the last event
     * @param closed whether the file is complete
     * @param filters lifecycle filters that wrote it
     * @param filterErrors lines replaced by the placeholder
     */
    public record ArchiveInfo(String path, long length, long sourceLength, long compressedSize, long lines,
        Instant from, Instant to, boolean closed, List<String> filters, long filterErrors) {
    }

    /**
     * A listing.
     *
     * @param files the files, newest first
     * @param truncated whether there are more files than {@code list-limit}
     */
    public record Listing(List<FileInfo> files, boolean truncated) {
    }

    /**
     * A line of a file.
     *
     * @param offset byte offset of the line
     * @param text the line, cut after {@code max-line-length}
     */
    public record Line(long offset, String text) {
    }

    /**
     * A page of a file.
     *
     * @param length bytes of the file now
     * @param lines the lines
     * @param prev offset to read the page before from, {@code null} at the start of the file
     * @param next offset of the next page, {@code null} at the end of the file
     */
    public record Page(long length, List<Line> lines, Long prev, Long next) {
    }

    /**
     * The hits of a search in a file.
     *
     * @param hits the lines that match
     * @param scannedTo offset to continue the search from
     * @param done whether the end of the file was reached
     */
    public record SearchResult(List<Line> hits, long scannedTo, boolean done) {
    }

    /**
     * What a deletion did.
     *
     * @param fingerprint fingerprint
     * @param hot whether the original was deleted
     * @param archive whether the filtered file was deleted
     * @param blocked whether the file is blocked for the agents
     * @param indexError why the events could not be deleted from the index, {@code null} if they were
     */
    public record DeleteResult(String fingerprint, boolean hot, boolean archive, boolean blocked,
        String indexError) {
    }

    /**
     * The applications and hosts of the visible files, for the filters of a listing.
     *
     * @param apps the applications
     * @param hosts the hosts
     */
    public record Facets(SortedSet<String> apps, SortedSet<String> hosts) {
    }

    /**
     * The applications and hosts of the files the caller may see.
     *
     * @param visible whether the caller may see the files of an application
     * @return applications and hosts, sorted
     */
    public Facets facets(Predicate<String> visible) {
        SortedSet<String> apps = new TreeSet<>();
        SortedSet<String> hosts = new TreeSet<>();
        for (HotFile file : hot.files()) {
            FileMeta meta = file.meta();
            if (visible.test(meta.app())) {
                apps.add(meta.app());
                hosts.add(meta.host());
            }
        }
        for (ArchiveFile file : archive.files()) {
            ArchiveMeta meta = file.meta();
            if (visible.test(meta.app())) {
                apps.add(meta.app());
                hosts.add(meta.host());
            }
        }
        return new Facets(apps, hosts);
    }

    /**
     * Lists files, newest first.
     *
     * @param visible whether the caller may see the files of an application
     * @param app only this application, {@code null} for all
     * @param host only this host, {@code null} for all
     * @param from only files created on or after this day, {@code null} for no limit
     * @param to only files created on or before this day, {@code null} for no limit
     * @return the files
     */
    public Listing list(Predicate<String> visible, String app, String host, LocalDate from, LocalDate to) {
        Map<String, HotFile> hotFiles = new LinkedHashMap<>();
        hot.files().forEach(file -> hotFiles.put(file.fingerprint(), file));
        Map<String, ArchiveFile> archiveFiles = new LinkedHashMap<>();
        archive.files().forEach(file -> archiveFiles.put(file.fingerprint(), file));
        List<String> fingerprints = new ArrayList<>(hotFiles.keySet());
        archiveFiles.keySet().stream().filter(fp -> !hotFiles.containsKey(fp)).forEach(fingerprints::add);
        List<Head> heads = new ArrayList<>();
        for (String fingerprint : fingerprints) {
            HotFile hotFile = hotFiles.get(fingerprint);
            ArchiveFile archiveFile = archiveFiles.get(fingerprint);
            Head head = hotFile != null ? new Head(hotFile.meta(), hotFile, archiveFile)
                : new Head(archiveFile.meta(), archiveFile);
            if (visible.test(head.app) && (app == null || app.equals(head.app))
                && (host == null || host.equals(head.host)) && (from == null || !head.created.isBefore(from))
                && (to == null || !head.created.isAfter(to))) {
                heads.add(head);
            }
        }
        heads.sort(Comparator.comparing((Head head) -> head.created).reversed()
            .thenComparing(head -> head.host).thenComparing(head -> head.app)
            .thenComparing(head -> head.fingerprint));
        boolean truncated = heads.size() > settings.listLimit();
        List<FileInfo> files = heads.stream().limit(settings.listLimit()).map(Head::info).toList();
        return new Listing(files, truncated);
    }

    /**
     * Fields of a file shared by both forms, for sorting before the details are collected.
     */
    private final class Head {

        private final String fingerprint;

        private final String host;

        private final String app;

        private final LocalDate created;

        private final HotFile hotFile;

        private final ArchiveFile archiveFile;

        private Head(FileMeta meta, HotFile hotFile, ArchiveFile archiveFile) {
            this.fingerprint = meta.fingerprint();
            this.host = meta.host();
            this.app = meta.app();
            this.created = meta.created();
            this.hotFile = hotFile;
            this.archiveFile = archiveFile;
        }

        private Head(ArchiveMeta meta, ArchiveFile archiveFile) {
            this.fingerprint = meta.fingerprint();
            this.host = meta.host();
            this.app = meta.app();
            this.created = meta.created();
            this.hotFile = null;
            this.archiveFile = archiveFile;
        }

        private FileInfo info() {
            return FileService.this.info(hotFile, archiveFile);
        }
    }

    /**
     * Details of a file.
     *
     * @param fingerprint fingerprint
     * @return the file
     * @throws NoSuchElementException if there is no such file
     */
    public FileInfo info(String fingerprint) {
        HotFile hotFile = hot.find(fingerprint).orElse(null);
        ArchiveFile archiveFile = archive.find(fingerprint).orElse(null);
        if (hotFile == null && archiveFile == null) {
            throw unknown(fingerprint);
        }
        return info(hotFile, archiveFile);
    }

    private FileInfo info(HotFile hotFile, ArchiveFile archiveFile) {
        HotInfo hotInfo = null;
        Boolean indexed = null;
        String fingerprint;
        String host;
        String app;
        LocalDate created;
        List<String> paths;
        if (hotFile != null) {
            FileMeta meta = hotFile.meta();
            fingerprint = meta.fingerprint();
            host = meta.host();
            app = meta.app();
            created = meta.created();
            paths = meta.paths();
            hotInfo = new HotInfo(hotFile.relativePath(), hotFile.length(), meta.firstReceived(), meta.lastReceived(),
                meta.closed());
            indexed = indexer != null && indexer.isIndexed(fingerprint);
        } else {
            ArchiveMeta meta = archiveFile.meta();
            fingerprint = meta.fingerprint();
            host = meta.host();
            app = meta.app();
            created = meta.created();
            paths = meta.paths();
        }
        ArchiveInfo archiveInfo = null;
        if (archiveFile != null) {
            ArchiveMeta meta = archiveFile.meta();
            archiveInfo = new ArchiveInfo(archiveFile.relativePath(), meta.length(), meta.sourceLength(),
                archiveFile.compressedSize(), meta.lines(), meta.from(), meta.to(), meta.closed(), meta.filters(),
                meta.filterErrors());
        }
        return new FileInfo(fingerprint, host, app, created, paths, hotInfo, archiveInfo, indexed);
    }

    /**
     * The application of a file, to check the rights before reading it.
     *
     * @param fingerprint fingerprint
     * @return the application
     * @throws NoSuchElementException if there is no such file
     */
    public String app(String fingerprint) {
        return hot.find(fingerprint).map(file -> file.meta().app())
            .or(() -> archive.find(fingerprint).map(file -> file.meta().app()))
            .orElseThrow(() -> unknown(fingerprint));
    }

    /**
     * Reads a page of lines. Without {@code before} the page starts with the line containing {@code at}, with
     * {@code before} it ends with the line before that offset.
     *
     * @param fingerprint fingerprint
     * @param source which form to read
     * @param at offset to read forward from, {@code null} for the start
     * @param before offset to read backward from, {@code null} to read forward
     * @return the page
     * @throws IOException if reading fails
     * @throws NoSuchElementException if the file does not exist in this form
     */
    public Page content(String fingerprint, Source source, Long at, Long before) throws IOException {
        Reader reader = reader(fingerprint, source);
        long length = reader.length();
        int pageSize = (int) Math.min(settings.pageSize().toBytes(), Integer.MAX_VALUE);
        List<Line> lines = new ArrayList<>();
        Long prev;
        Long next;
        if (before != null) {
            long end = Math.clamp(before, 0, length);
            long start = Math.max(0, end - pageSize);
            byte[] data = reader.read(start, (int) (end - start));
            int pos = 0;
            if (start > 0) {
                // the first line begins before the page, without a line break it is shown from the page start
                pos = indexOf(data, 0, data.length) + 1;
            }
            List<Line> all = new ArrayList<>();
            while (pos < data.length) {
                int newline = indexOf(data, pos, data.length);
                int lineEnd = newline < 0 ? data.length : newline;
                all.add(line(start + pos, data, pos, lineEnd));
                pos = lineEnd + 1;
            }
            lines.addAll(all.subList(Math.max(0, all.size() - settings.pageLines()), all.size()));
            long first = lines.isEmpty() ? end : lines.getFirst().offset();
            prev = first > 0 ? first : null;
            next = end < length ? end : null;
        } else {
            long start = at == null ? 0 : lineStart(reader, Math.clamp(at, 0, length));
            byte[] data = reader.read(start, (int) Math.min(pageSize, length - start));
            int pos = 0;
            while (pos < data.length && lines.size() < settings.pageLines()) {
                int newline = indexOf(data, pos, data.length);
                if (newline < 0 && start + data.length < length && pos > 0) {
                    // the line goes on behind the page, it starts the next page
                    break;
                }
                int lineEnd = newline < 0 ? data.length : newline;
                lines.add(line(start + pos, data, pos, lineEnd));
                pos = lineEnd + 1;
            }
            long end = start + Math.min(pos, data.length);
            if (pos > data.length && end < length) {
                // a single line longer than the page, the next page starts behind it
                end = lineEnd(reader, end, length);
            }
            prev = start > 0 ? start : null;
            next = end < length ? end : null;
        }
        return new Page(length, lines, prev, next);
    }

    /**
     * Searches lines from an offset on. Without a hit the search stops after {@code search-bytes} and can be
     * continued from {@code scannedTo}.
     *
     * @param fingerprint fingerprint
     * @param source which form to read
     * @param query the text or regular expression
     * @param regex whether {@code query} is a regular expression
     * @param ignoreCase whether case is ignored
     * @param from offset to start at, moved back to the start of its line
     * @return the hits
     * @throws IOException if reading fails
     * @throws IllegalArgumentException if the query is empty or not a valid expression
     * @throws NoSuchElementException if the file does not exist in this form
     */
    public SearchResult search(String fingerprint, Source source, String query, boolean regex, boolean ignoreCase,
        long from) throws IOException {
        if (query == null || query.isEmpty()) {
            throw new IllegalArgumentException("Kein Suchbegriff angegeben.");
        }
        com.google.re2j.Pattern pattern;
        try {
            pattern = com.google.re2j.Pattern.compile(regex ? query : com.google.re2j.Pattern.quote(query),
                ignoreCase ? com.google.re2j.Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Ungültiger regulärer Ausdruck: " + e.getDescription(), e);
        }
        Reader reader = reader(fingerprint, source);
        long length = reader.length();
        long pos = lineStart(reader, Math.clamp(from, 0, length));
        long limit = settings.searchBytes().toBytes();
        long scanned = 0;
        List<Line> hits = new ArrayList<>();
        while (pos < length && hits.size() < settings.searchHits() && scanned < limit) {
            byte[] data = reader.read(pos, (int) Math.min(CHUNK, length - pos));
            int offset = 0;
            while (offset < data.length && hits.size() < settings.searchHits()) {
                int newline = indexOf(data, offset, data.length);
                if (newline < 0 && pos + data.length < length && offset > 0) {
                    break;
                }
                int lineEnd = newline < 0 ? data.length : newline;
                String raw = plain(data, offset, lineEnd);
                if (pattern.matcher(raw).find()) {
                    hits.add(new Line(pos + offset, cut(raw)));
                }
                offset = lineEnd + 1;
            }
            int consumed = Math.min(offset, data.length);
            if (consumed == 0) {
                // a line longer than a chunk: skip what was read of it
                consumed = data.length;
            }
            pos += consumed;
            scanned += consumed;
        }
        return new SearchResult(hits, pos, pos >= length);
    }

    /**
     * Writes a whole file.
     *
     * @param fingerprint fingerprint
     * @param source which form to write
     * @param out the target
     * @throws IOException if reading or writing fails
     * @throws NoSuchElementException if the file does not exist in this form
     */
    public void download(String fingerprint, Source source, OutputStream out) throws IOException {
        Reader reader = reader(fingerprint, source);
        long length = reader.length();
        long pos = 0;
        while (pos < length) {
            byte[] data = reader.part(pos, (int) Math.min(CHUNK, length - pos));
            if (data.length == 0) {
                break;
            }
            out.write(data);
            pos += data.length;
        }
    }

    /**
     * A file name for the download.
     *
     * @param fingerprint fingerprint
     * @param source which form
     * @return e.g. {@code app1-digibib-2026-10-26-<fingerprint>.log}
     */
    public String fileName(String fingerprint, Source source) {
        FileInfo info = info(fingerprint);
        return info.host() + "-" + info.app() + "-" + info.created() + "-" + fingerprint
            + (source == Source.ARCHIVE ? ".filtered.log" : ".log");
    }

    /**
     * Deletes a file from {@code hot/}, the archive and the index. The archive writer and the indexer are held off
     * the file while it is deleted, and jobs loading events from the archive drop the events of the file until they
     * are deleted from the index. With {@code block} the stream API answers the file as closed afterwards, so an
     * agent does not send it again.
     *
     * @param fingerprint fingerprint
     * @param block whether to block the file
     * @param user who deletes it
     * @return what was done
     * @throws IOException if a file cannot be deleted or the block cannot be stored
     * @throws InterruptedException if waiting for the writer or the indexer is interrupted
     * @throws NoSuchElementException if there is no such file
     */
    public DeleteResult delete(String fingerprint, boolean block, String user)
        throws IOException, InterruptedException {
        FileInfo info = info(fingerprint);
        if (block) {
            long length = info.hot() != null ? info.hot().length() : info.archive().sourceLength();
            String path = info.hot() != null ? info.hot().path() : info.archive().path();
            deleted.add(new DeletedFiles.Entry(fingerprint, info.host(), info.app(), path, length,
                clock.instant(), user));
        }
        if (reindex != null) {
            reindex.hold(fingerprint);
        }
        try {
            return delete(info, block, user);
        } finally {
            if (reindex != null) {
                reindex.release(fingerprint);
            }
        }
    }

    private DeleteResult delete(FileInfo info, boolean block, String user) throws IOException, InterruptedException {
        String fingerprint = info.fingerprint();
        boolean hotDeleted = false;
        boolean archiveDeleted = false;
        String archivePath = null;
        if (writer != null) {
            writer.hold(fingerprint);
        }
        try {
            Optional<HotFile> hotFile = hot.find(fingerprint);
            if (hotFile.isPresent()) {
                hot.delete(hotFile.get());
                hotDeleted = true;
            }
            Optional<ArchiveFile> archiveFile = archive.find(fingerprint);
            if (archiveFile.isPresent()) {
                archivePath = archiveFile.get().relativePath();
                archive.delete(archiveFile.get());
                archiveDeleted = true;
            }
        } finally {
            if (writer != null) {
                writer.release(fingerprint);
            }
        }
        if (indexer != null) {
            indexer.remove(fingerprint);
        }
        String indexError = null;
        try {
            sink.delete(EventDocuments.eventsOf(fingerprint));
        } catch (Exception e) {
            LOGGER.error("cannot delete the events of {} from the index", fingerprint, e);
            indexError = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        if (archivePath != null && backup != null) {
            backup.forget(archivePath);
        }
        LOGGER.info("{} deleted {}/{}/{}{}", user, info.host(), info.app(), fingerprint, block ? ", blocked" : "");
        return new DeleteResult(fingerprint, hotDeleted, archiveDeleted, block, indexError);
    }

    /**
     * The blocked files.
     *
     * @param visible whether the caller may see the files of an application
     * @return the entries, last deleted first
     */
    public List<DeletedFiles.Entry> blocked(Predicate<String> visible) {
        return deleted.list().stream().filter(entry -> visible.test(entry.app())).toList();
    }

    /**
     * A blocked file.
     *
     * @param fingerprint fingerprint
     * @return the entry
     * @throws NoSuchElementException if the file is not blocked
     */
    public DeletedFiles.Entry blockedEntry(String fingerprint) {
        return deleted.blocked(fingerprint)
            .orElseThrow(() -> new NoSuchElementException("Die Datei " + fingerprint + " ist nicht gesperrt."));
    }

    /**
     * Lifts the block of a file, the agent may send it again.
     *
     * @param fingerprint fingerprint
     * @return the removed entry
     * @throws IOException if the list cannot be written
     * @throws NoSuchElementException if the file is not blocked
     */
    public DeletedFiles.Entry unblock(String fingerprint) throws IOException {
        return deleted.remove(fingerprint)
            .orElseThrow(() -> new NoSuchElementException("Die Datei " + fingerprint + " ist nicht gesperrt."));
    }

    private Reader reader(String fingerprint, Source source) {
        if (!Fingerprint.isValid(fingerprint)) {
            throw unknown(fingerprint);
        }
        if (source == Source.HOT) {
            HotFile file = hot.find(fingerprint)
                .orElseThrow(() -> new NoSuchElementException("Das Original von " + fingerprint
                    + " liegt nicht mehr in hot/."));
            return new Reader(file::length, file::read);
        }
        ArchiveFile file = archive.find(fingerprint)
            .orElseThrow(() -> new NoSuchElementException("Die Datei " + fingerprint + " ist nicht archiviert."));
        return new Reader(() -> file.meta().length(), file::read);
    }

    private static NoSuchElementException unknown(String fingerprint) {
        return new NoSuchElementException("Die Datei " + fingerprint + " gibt es nicht.");
    }

    /**
     * Moves an offset back to the start of its line.
     */
    private long lineStart(Reader reader, long offset) throws IOException {
        long end = offset;
        long maxBack = settings.maxLineLength().toBytes() + 1;
        while (end > 0 && offset - end < maxBack) {
            long start = Math.max(0, end - Math.min(CHUNK, maxBack));
            byte[] data = reader.read(start, (int) (end - start));
            for (int i = data.length - 1; i >= 0; i--) {
                if (data[i] == '\n') {
                    return start + i + 1;
                }
            }
            end = start;
        }
        return end;
    }

    private static long lineEnd(Reader reader, long offset, long length) throws IOException {
        long pos = offset;
        while (pos < length) {
            byte[] data = reader.part(pos, (int) Math.min(CHUNK, length - pos));
            if (data.length == 0) {
                break;
            }
            int newline = indexOf(data, 0, data.length);
            if (newline >= 0) {
                return pos + newline + 1;
            }
            pos += data.length;
        }
        return pos;
    }

    private Line line(long offset, byte[] data, int from, int to) {
        return new Line(offset, cut(new String(data, from, trimCr(data, from, to) - from, StandardCharsets.UTF_8)));
    }

    private String cut(String line) {
        int max = (int) settings.maxLineLength().toBytes();
        return line.length() > max ? line.substring(0, max) + " …" : line;
    }

    private static String plain(byte[] data, int from, int to) {
        return ANSI.matcher(new String(data, from, trimCr(data, from, to) - from, StandardCharsets.UTF_8))
            .replaceAll("");
    }

    /**
     * Removes the escape sequences of terminal colors, e.g. before a line is pseudonymized.
     *
     * @param line the line
     * @return the line without colors
     */
    public static String withoutColors(String line) {
        return ANSI.matcher(line).replaceAll("");
    }

    private static int trimCr(byte[] data, int from, int to) {
        return to > from && data[to - 1] == '\r' ? to - 1 : to;
    }

    private static int indexOf(byte[] data, int from, int to) {
        for (int i = from; i < to; i++) {
            if (data[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    @FunctionalInterface
    private interface Length {
        long get();
    }

    @FunctionalInterface
    private interface Part {
        byte[] read(long offset, int max) throws IOException;
    }

    /**
     * Reads a range of a file across frames.
     */
    private static final class Reader {

        private final Length length;

        private final Part part;

        private Reader(Length length, Part part) {
            this.length = length;
            this.part = part;
        }

        long length() {
            return length.get();
        }

        // at most one frame
        byte[] part(long offset, int max) throws IOException {
            return part.read(offset, max);
        }

        byte[] read(long offset, int count) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(0, count));
            long pos = offset;
            while (out.size() < count) {
                byte[] data = part.read(pos, count - out.size());
                if (data.length == 0) {
                    break;
                }
                out.write(data);
                pos += data.length;
            }
            return out.toByteArray();
        }
    }
}
