package de.gbv.logguy.server.search;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Pattern;

import org.springframework.security.access.AccessDeniedException;

import de.gbv.logguy.server.archive.ArchiveFile;
import de.gbv.logguy.server.archive.ArchiveStore;
import de.gbv.logguy.server.archive.LifecycleFilters;
import de.gbv.logguy.server.archive.LifecycleFilters.Filter;
import de.gbv.logguy.server.archive.LifecycleFilters.LineResult;
import de.gbv.logguy.server.archive.LifecycleFilters.Runner;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.ArchiveProperties;
import de.gbv.logguy.server.config.LogGuyProperties.Right;
import de.gbv.logguy.server.config.LogGuyProperties.SearchProperties;
import de.gbv.logguy.server.hot.FileMeta;
import de.gbv.logguy.server.hot.HotFile;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.index.EventDocuments;
import de.gbv.logguy.server.script.ScriptException;
import de.gbv.logguy.server.security.LogGuyUser;

/**
 * Reads the lines around an event by the byte offset in the event id. Everybody sees them as the archive has them:
 * lines from {@code hot/} pass the {@code line(text)} filters of the application like on the way into the archive,
 * events loaded from the archive are read from the filtered file in {@code archive/}. In both cases every line also
 * passes the {@code fields(event)} filters with the line as {@code message}, so a value that a filter removes from
 * the message in the index does not show up in the context either. Only an administrator of the application can ask
 * for the unfiltered original from {@code hot/}, like {@code source=HOT} in the log browser.
 */
public class ContextService implements AutoCloseable {

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");

    private static final String UNAVAILABLE = "Ein Lifecycle-Filter der Anwendung lässt sich nicht laden. "
        + "Der Kontext ist erst nach der Reparatur wieder abrufbar.";

    private final SearchProperties settings;

    private final ArchiveProperties archiveSettings;

    private final HotStore store;

    private final ArchiveStore archive;

    private final LifecycleFilters filters;

    // a runner is not thread safe, every request borrows one and gives it back
    private final Deque<Runner> runners = new ConcurrentLinkedDeque<>();

    /**
     * Creates the service.
     *
     * @param properties configuration
     * @param store the hot storage
     * @param archive the archive, {@code null} if it is disabled
     * @param filters the lifecycle filters
     */
    public ContextService(LogGuyProperties properties, HotStore store, ArchiveStore archive,
        LifecycleFilters filters) {
        this.settings = properties.search();
        this.archiveSettings = properties.archive();
        this.store = store;
        this.archive = archive;
        this.filters = filters;
    }

    /**
     * Reads the lines before an event and the lines starting with it, filtered as in the archive.
     *
     * @param user the user, needs {@link Right#READ} for the application of the file
     * @param id the event id {@code <fingerprint>:<offset>}
     * @param before lines before the event, {@code null} for the default
     * @param after lines from the start of the event on, {@code null} for the default
     * @return the lines, without ANSI colors
     * @throws IOException if reading fails
     * @throws NoSuchElementException if the file is not in {@code hot/} or {@code archive/}
     * @throws AccessDeniedException if the user may not read the application
     * @throws ContextUnavailableException if a lifecycle filter cannot be loaded
     */
    public ContextView context(LogGuyUser user, String id, Integer before, Integer after) throws IOException {
        return context(user, id, before, after, false);
    }

    /**
     * Reads the lines before an event and the lines starting with it.
     *
     * @param user the user, needs {@link Right#READ} for the application of the file
     * @param id the event id {@code <fingerprint>:<offset>}
     * @param before lines before the event, {@code null} for the default
     * @param after lines from the start of the event on, {@code null} for the default
     * @param original the unfiltered original from {@code hot/}, needs {@link Right#ADMIN} for the application
     * @return the lines, without ANSI colors
     * @throws IOException if reading fails
     * @throws NoSuchElementException if the file is not in {@code hot/} or {@code archive/}
     * @throws AccessDeniedException if the user may not read the application or not the original
     * @throws ContextUnavailableException if a lifecycle filter cannot be loaded
     */
    public ContextView context(LogGuyUser user, String id, Integer before, Integer after, boolean original)
        throws IOException {
        int separator = id.lastIndexOf(':');
        String position = id.substring(separator + 1);
        boolean archived = position.startsWith(EventDocuments.ARCHIVE_MARK);
        long offset;
        try {
            offset = Long.parseLong(archived ? position.substring(EventDocuments.ARCHIVE_MARK.length()) : position);
        } catch (NumberFormatException e) {
            throw new SearchException("Ungültige Event-ID „" + id + "“.");
        }
        if (separator <= 0 || offset < 0) {
            throw new SearchException("Ungültige Event-ID „" + id + "“.");
        }
        String fingerprint = id.substring(0, separator);
        Source file = archived ? archiveSource(fingerprint) : hotSource(fingerprint);
        if (!user.mayRead(file.app())) {
            throw new AccessDeniedException("app " + file.app());
        }
        if (original && (archived || !user.may(Right.ADMIN, file.app()))) {
            throw new AccessDeniedException("original of app " + file.app());
        }
        int linesBefore = before == null ? settings.contextLines() : Math.clamp(before, 0, settings.maxContextLines());
        int linesAfter = after == null ? settings.contextLines() : Math.clamp(after, 1, settings.maxContextLines());
        long window = settings.contextBytes().toBytes();
        long length = file.length();
        if (offset > length) {
            throw new NoSuchElementException("Das Event liegt hinter dem Ende der Datei.");
        }
        Runner runner = original ? null : borrow();
        try {
            LineFilter filter = original ? (lineOffset, text) -> text : filter(runner, file, archived);

            long start = Math.max(0, offset - window);
            List<Line> head = lines(read(file, start, offset), start);
            if (start > 0 && !head.isEmpty()) {
                head.removeFirst();
            }
            List<String> previous = new ArrayList<>();
            int i = head.size() - 1;
            for (; i >= 0 && previous.size() < linesBefore; i--) {
                String text = filter.apply(head.get(i).offset(), head.get(i).text());
                if (text != null) {
                    previous.addFirst(strip(text));
                }
            }
            boolean atStart = start == 0 && i < 0;

            long end = Math.min(length, offset + window);
            List<Line> tail = lines(read(file, offset, end), offset);
            boolean complete = end == length;
            if (!complete && tail.size() > 1) {
                tail.removeLast();
            }
            List<String> lines = new ArrayList<>();
            int j = 0;
            for (; j < tail.size() && lines.size() < linesAfter; j++) {
                String text = filter.apply(tail.get(j).offset(), tail.get(j).text());
                if (text != null) {
                    lines.add(strip(text));
                }
            }
            boolean atEnd = complete && j == tail.size();
            return new ContextView(id, file.app(), file.host(), previous, lines, atStart, atEnd);
        } finally {
            if (runner != null) {
                runners.offerFirst(runner);
            }
        }
    }

    /**
     * Closes the script contexts of all runners.
     */
    @Override
    public void close() {
        Runner runner = runners.poll();
        while (runner != null) {
            runner.close();
            runner = runners.poll();
        }
    }

    private Runner borrow() {
        Runner runner = runners.poll();
        return runner == null ? filters.runner() : runner;
    }

    /**
     * The filters as the archive writer and the indexer run them. A line filter that fails gives the placeholder of
     * the archive, a fields filter that fails the placeholder of the index, never the unfiltered line.
     */
    private LineFilter filter(Runner runner, Source file, boolean archived) {
        if (!filters.errors().isEmpty()) {
            throw new ContextUnavailableException(UNAVAILABLE);
        }
        List<Filter> active;
        try {
            active = runner.forApp(file.app());
        } catch (ScriptException e) {
            throw new ContextUnavailableException(UNAVAILABLE);
        }
        // lines from the archive already passed line(text)
        List<Filter> lineFilters = archived ? List.of() : active.stream().filter(Filter::filtersLines).toList();
        List<Filter> fieldFilters = active.stream().filter(Filter::filtersFields).toList();
        return (offset, line) -> {
            String text = line;
            for (Filter filter : lineFilters) {
                LineResult result = filter.line(text);
                if (result.error() != null) {
                    return archiveSettings.placeholder().replace("{filter}", filter.name()).replace("{offset}",
                        String.valueOf(offset));
                }
                text = result.text();
                if (text == null) {
                    return null;
                }
            }
            if (fieldFilters.isEmpty()) {
                return text;
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("app", file.app());
            event.put("host", file.host());
            event.put("message", strip(text));
            for (Filter filter : fieldFilters) {
                try {
                    event = filter.fields(event);
                } catch (ScriptException e) {
                    return archiveSettings.fieldPlaceholder().replace("{filter}", filter.name());
                }
            }
            Object message = event.get("message");
            return message == null ? "" : message.toString();
        };
    }

    private Source hotSource(String fingerprint) {
        HotFile file = store.find(fingerprint)
            .orElseThrow(() -> new NoSuchElementException("Die Datei des Events liegt nicht mehr in hot/."));
        FileMeta meta = file.meta();
        return new Source(meta.app(), meta.host(), file.length(), file::read);
    }

    private Source archiveSource(String fingerprint) {
        ArchiveFile file = (archive == null ? null : archive.find(fingerprint).orElse(null));
        if (file == null) {
            throw new NoSuchElementException("Die Datei des Events liegt nicht im Archiv.");
        }
        return new Source(file.meta().app(), file.meta().host(), file.meta().length(), file::read);
    }

    private static byte[] read(Source file, long from, long to) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) (to - from));
        long position = from;
        while (position < to) {
            byte[] data = file.read(position, (int) (to - position));
            if (data.length == 0) {
                break;
            }
            out.write(data);
            position += data.length;
        }
        return out.toByteArray();
    }

    /**
     * Splits into lines without line break and carriage return, each with its offset in the file. A line break at
     * the end gives no empty last line.
     */
    private static List<Line> lines(byte[] data, long from) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n') {
                lines.add(line(data, start, i, from));
                start = i + 1;
            }
        }
        if (start < data.length) {
            lines.add(line(data, start, data.length, from));
        }
        return lines;
    }

    private static Line line(byte[] data, int start, int stop, long from) {
        int end = stop > start && data[stop - 1] == '\r' ? stop - 1 : stop;
        return new Line(from + start, new String(data, start, end - start, StandardCharsets.UTF_8));
    }

    private static String strip(String text) {
        return ANSI.matcher(text).replaceAll("");
    }

    private record Line(long offset, String text) {
    }

    @FunctionalInterface
    private interface LineFilter {

        /** The filtered line, {@code null} if a filter drops it. */
        String apply(long offset, String text);
    }

    @FunctionalInterface
    private interface Reader {
        byte[] read(long position, int max) throws IOException;
    }

    private record Source(String app, String host, long length, Reader reader) {

        byte[] read(long position, int max) throws IOException {
            return reader.read(position, max);
        }
    }

    /**
     * Lines around an event.
     *
     * @param id the event id
     * @param app the application
     * @param host the host
     * @param before lines before the event
     * @param lines lines from the start of the event on
     * @param start whether {@code before} reaches the start of the file
     * @param end whether {@code lines} reaches the current end of the file
     */
    public record ContextView(String id, String app, String host, List<String> before, List<String> lines,
        boolean start, boolean end) {
    }
}
