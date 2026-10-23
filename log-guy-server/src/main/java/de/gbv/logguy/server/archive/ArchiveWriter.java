package de.gbv.logguy.server.archive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.gbv.logguy.server.archive.LifecycleFilters.Filter;
import de.gbv.logguy.server.archive.LifecycleFilters.LineResult;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.ArchiveProperties;
import de.gbv.logguy.server.hot.FileMeta;
import de.gbv.logguy.server.hot.HotFile;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.script.ParsedEvent;
import de.gbv.logguy.server.script.Parser;
import de.gbv.logguy.server.script.ParserRegistry;
import de.gbv.logguy.server.script.ParserScript;
import de.gbv.logguy.server.script.ScriptException;
import de.gbv.logguy.server.script.ScriptRuntime;

/**
 * Copies complete lines from {@code hot/} to {@code archive/} and runs them through the lifecycle filters on the
 * way. A line dropped by a filter is missing in the archive. A line whose filter fails is replaced by the
 * placeholder, it never reaches the archive unfiltered. If a filter cannot be loaded at all, the writer pauses.
 * <p>
 * The writer works on frames of {@code hot/}, so a line arrives in the archive at the latest {@code frame-max-age}
 * after it arrived in {@code hot/}. Each file is written by one thread at a time, the archived length in its meta
 * data is the progress.
 */
public class ArchiveWriter implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger();

    /** How many lines at the start and the end of a frame are tried for the time of the frame. */
    private static final int TIME_PROBES = 50;

    private final ArchiveProperties settings;

    private final HotStore hot;

    private final ArchiveStore archive;

    private final LifecycleFilters filters;

    private final ParserRegistry parsers;

    private final ScriptRuntime runtime;

    private final int frameSize;

    private final ExecutorService workers;

    private final Thread scheduler;

    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    private final List<AutoCloseable> threadResources = new CopyOnWriteArrayList<>();

    private final ThreadLocal<LifecycleFilters.Runner> runners;

    private final ThreadLocal<Map<String, Parser>> threadParsers = ThreadLocal.withInitial(HashMap::new);

    private final AtomicLong lines = new AtomicLong();

    private final AtomicLong dropped = new AtomicLong();

    private final AtomicLong filterErrors = new AtomicLong();

    private volatile boolean running = true;

    private volatile List<String> pausedBy = List.of();

    /**
     * Creates the writer and starts its threads.
     *
     * @param properties for the settings
     * @param hot the originals
     * @param archive the archive
     * @param filters the lifecycle filters
     * @param parsers the parsers, for the time range of each frame
     * @param runtime the script runtime
     */
    public ArchiveWriter(LogGuyProperties properties, HotStore hot, ArchiveStore archive, LifecycleFilters filters,
        ParserRegistry parsers, ScriptRuntime runtime) {
        this.settings = properties.archive();
        this.hot = hot;
        this.archive = archive;
        this.filters = filters;
        this.runners = ThreadLocal.withInitial(() -> {
            LifecycleFilters.Runner runner = filters.runner();
            threadResources.add(runner);
            return runner;
        });
        this.parsers = parsers;
        this.runtime = runtime;
        this.frameSize = (int) properties.hot().frameSize().toBytes();
        this.workers = Executors.newFixedThreadPool(settings.threads(),
            Thread.ofPlatform().name("archive-", 0).factory());
        this.scheduler = Thread.ofPlatform().name("archive-scheduler").daemon().start(this::schedule);
    }

    private void schedule() {
        while (running) {
            try {
                scan();
                Thread.sleep(settings.pollInterval());
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                LOGGER.error("archive scan failed", e);
            }
        }
    }

    /**
     * Submits every file with new complete lines to the worker threads, unless a filter cannot be loaded.
     */
    void scan() {
        List<String> errors = filters.errors();
        if (!errors.isEmpty()) {
            if (!errors.equals(pausedBy)) {
                LOGGER.error("archive writer paused, lifecycle filters cannot be loaded: {}", errors);
            }
            pausedBy = errors;
            return;
        }
        if (!pausedBy.isEmpty()) {
            LOGGER.info("archive writer continues, lifecycle filters are loaded again");
            pausedBy = List.of();
        }
        for (HotFile file : hot.files()) {
            if (!hasWork(file) || !busy.add(file.fingerprint())) {
                continue;
            }
            workers.execute(() -> {
                try {
                    process(file);
                } catch (Exception e) {
                    LOGGER.error("cannot archive {}", file.relativePath(), e);
                } finally {
                    busy.remove(file.fingerprint());
                }
            });
        }
    }

    private boolean hasWork(HotFile file) {
        Optional<ArchiveFile> target = archive.find(file.fingerprint());
        if (target.isEmpty()) {
            return true;
        }
        ArchiveMeta meta = target.get().meta();
        return !meta.closed() && (meta.sourceLength() < file.framedLength() || file.meta().closed());
    }

    /**
     * Archives the complete lines of a file that are not archived yet.
     *
     * @param file the original
     * @throws IOException if reading or writing fails
     * @throws ScriptException if a filter cannot be loaded into the thread
     */
    void process(HotFile file) throws IOException, ScriptException {
        process(file, archive.getOrCreate(file.meta()));
    }

    private void process(HotFile file, ArchiveFile target) throws IOException, ScriptException {
        FileMeta source = file.meta();
        List<Filter> lineFilters = runners.get().forApp(source.app()).stream().filter(Filter::filtersLines)
            .toList();
        List<String> labels = filters.labels(source.app());
        Parser parser = parser(source.app());
        long src = target.meta().sourceLength();
        long end = file.framedLength();
        boolean complete = source.closed() && end == file.length();
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        long pos = src;
        while (pos < end) {
            byte[] data = file.read(pos, frameSize);
            pending.write(data);
            pos += data.length;
            if (pending.size() < frameSize && pos < end) {
                continue;
            }
            byte[] bytes = pending.toByteArray();
            int cut = lastNewline(bytes) + 1;
            if (cut == 0) {
                if (pos < end && bytes.length < 4L * frameSize) {
                    continue;
                }
                if (pos == end && !complete && bytes.length < 4L * frameSize) {
                    break;
                }
                cut = bytes.length;
            }
            writeFrame(file, target, bytes, cut, src, lineFilters, labels, parser);
            src += cut;
            pending.reset();
            pending.write(bytes, cut, bytes.length - cut);
        }
        if (pending.size() > 0 && complete) {
            byte[] bytes = pending.toByteArray();
            writeFrame(file, target, bytes, bytes.length, src, lineFilters, labels, parser);
            src += bytes.length;
        }
        if (complete && src == end) {
            target.close(file.meta().paths());
            LOGGER.info("archived {} completely", target.relativePath());
        }
    }

    /**
     * Writes archive files again from their originals in {@code hot/} with the current filters, e.g. after a filter
     * was repaired. Each file is written beside the current one and then replaces it, the writer does not touch
     * it meanwhile.
     *
     * @param app only files of this application, {@code null} for all
     * @param all all files with an original, not only those with placeholders
     * @return what was written again
     * @throws InterruptedException if waiting for the files is interrupted
     */
    public RefilterReport refilter(String app, boolean all) throws InterruptedException {
        if (!filters.errors().isEmpty()) {
            return new RefilterReport(List.of(), List.of(), List.of(), 0, filters.errors());
        }
        List<String> missing = new ArrayList<>();
        List<Future<ArchiveFile>> futures = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (ArchiveFile current : List.copyOf(archive.files())) {
            ArchiveMeta meta = current.meta();
            if (app != null && !app.equals(meta.app()) || !all && meta.filterErrors() == 0) {
                continue;
            }
            Optional<HotFile> original = hot.find(current.fingerprint());
            if (original.isEmpty()) {
                missing.add(current.relativePath());
                continue;
            }
            // taken here and not in the worker, a waiting worker could block the task holding the file
            while (!busy.add(current.fingerprint())) {
                Thread.sleep(settings.pollInterval().toMillis());
            }
            names.add(current.relativePath());
            futures.add(workers.submit(() -> refilter(original.get())));
        }
        List<String> done = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        long placeholders = 0;
        for (int i = 0; i < futures.size(); i++) {
            try {
                ArchiveFile result = futures.get(i).get();
                done.add(result.relativePath());
                placeholders += result.meta().filterErrors();
            } catch (ExecutionException e) {
                LOGGER.error("cannot refilter {}", names.get(i), e.getCause());
                failed.add(names.get(i) + ": " + e.getCause().getMessage());
            }
        }
        LOGGER.info("refiltered {} files, {} placeholders left, {} without original, {} failed", done.size(),
            placeholders, missing.size(), failed.size());
        return new RefilterReport(done, missing, failed, placeholders, List.of());
    }

    private ArchiveFile refilter(HotFile file) throws IOException, ScriptException {
        try {
            ArchiveFile staged = archive.stage(file.meta());
            process(file, staged);
            return archive.replace(staged);
        } finally {
            busy.remove(file.fingerprint());
        }
    }

    /**
     * Result of {@link #refilter(String, boolean)}.
     *
     * @param refiltered files written again
     * @param missing files whose original is no longer in {@code hot/}
     * @param failed files that could not be written again, with the reason
     * @param placeholders placeholders left in the files written again
     * @param pausedBy errors of filters that cannot be loaded, then nothing was written
     */
    public record RefilterReport(List<String> refiltered, List<String> missing, List<String> failed,
        long placeholders, List<String> pausedBy) {
    }

    private void writeFrame(HotFile file, ArchiveFile target, byte[] bytes, int length, long src,
        List<Filter> lineFilters, List<String> labels, Parser parser) throws IOException {
        List<String> original = new ArrayList<>();
        List<Boolean> newline = new ArrayList<>();
        List<Long> offsets = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < length; i++) {
            if (bytes[i] == '\n') {
                original.add(new String(bytes, start, i - start, StandardCharsets.UTF_8));
                newline.add(true);
                offsets.add(src + start);
                start = i + 1;
            }
        }
        if (start < length) {
            original.add(new String(bytes, start, length - start, StandardCharsets.UTF_8));
            newline.add(false);
            offsets.add(src + start);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(length);
        int written = 0;
        int errors = 0;
        String firstError = null;
        for (int i = 0; i < original.size(); i++) {
            String line = original.get(i);
            String cr = line.endsWith("\r") ? "\r" : "";
            String text = cr.isEmpty() ? line : line.substring(0, line.length() - 1);
            for (Filter filter : lineFilters) {
                LineResult result = filter.line(text);
                if (result.error() != null) {
                    if (firstError == null) {
                        firstError = filter.label() + " at offset " + offsets.get(i) + ": " + result.error();
                    }
                    text = settings.placeholder().replace("{filter}", filter.name()).replace("{offset}",
                        String.valueOf(offsets.get(i)));
                    errors++;
                    break;
                }
                text = result.text();
                if (text == null) {
                    break;
                }
            }
            if (text == null) {
                dropped.incrementAndGet();
                continue;
            }
            out.writeBytes((text + cr).getBytes(StandardCharsets.UTF_8));
            if (newline.get(i)) {
                out.write('\n');
            }
            written++;
        }
        if (firstError != null) {
            LOGGER.warn("{} lines of {} replaced by the placeholder, the first by {}", errors, file.relativePath(),
                firstError);
        }
        Instant received = file.received(src + length - 1);
        Instant from = time(parser, original, true).orElse(received);
        Instant to = time(parser, original, false).orElse(received);
        if (to.isBefore(from)) {
            to = from;
        }
        target.append(out.toByteArray(), src, src + length, written, errors, from, to, received, labels,
            file.meta().paths(), settings.compressionLevel());
        lines.addAndGet(written);
        filterErrors.addAndGet(errors);
    }

    /**
     * The time of the first or last line with a time, from the original lines.
     */
    private static Optional<Instant> time(Parser parser, List<String> lines, boolean first) {
        if (parser == null) {
            return Optional.empty();
        }
        int probes = Math.min(TIME_PROBES, lines.size());
        for (int n = 0; n < probes; n++) {
            String line = lines.get(first ? n : lines.size() - 1 - n);
            if (!parser.isStart(line)) {
                continue;
            }
            ParsedEvent event = parser.parse(line);
            if (event.ts() != null) {
                return Optional.of(event.ts());
            }
        }
        return Optional.empty();
    }

    private Parser parser(String app) {
        ParserScript script = parsers.forApp(app).orElse(null);
        if (script == null) {
            return null;
        }
        Map<String, Parser> map = threadParsers.get();
        Parser parser = map.get(script.name());
        if (parser == null || !parser.script().version().equals(script.version())) {
            if (parser != null) {
                threadResources.remove(parser);
                parser.close();
            }
            try {
                parser = new Parser(runtime, script);
            } catch (ScriptException e) {
                LOGGER.warn("parser {} cannot be loaded, archive frames get the receive time", script.name(), e);
                return null;
            }
            threadResources.add(parser);
            map.put(script.name(), parser);
        }
        return parser;
    }

    private static int lastNewline(byte[] bytes) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Original bytes in {@code hot/} that are not archived yet.
     *
     * @return the backlog in bytes
     */
    public long backlog() {
        long total = 0;
        for (HotFile file : hot.files()) {
            long archived = archive.find(file.fingerprint()).map(a -> a.meta().sourceLength()).orElse(0L);
            total += Math.max(0, file.length() - archived);
        }
        return total;
    }

    /**
     * Lines written since the start.
     *
     * @return the number of lines
     */
    public long archivedLines() {
        return lines.get();
    }

    /**
     * Lines dropped by a filter since the start.
     *
     * @return the number of lines
     */
    public long droppedLines() {
        return dropped.get();
    }

    /**
     * Lines replaced by the placeholder since the start, the totals per file are in the meta data.
     *
     * @return the number of lines
     */
    public long filterErrors() {
        return filterErrors.get();
    }

    /**
     * Why the writer is paused.
     *
     * @return errors of filters that cannot be loaded, empty while the writer runs
     */
    public List<String> pausedBy() {
        return filters.errors();
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        scheduler.interrupt();
        workers.shutdown();
        if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
            workers.shutdownNow();
        }
        for (AutoCloseable resource : threadResources) {
            try {
                resource.close();
            } catch (Exception e) {
                LOGGER.debug("cannot close {}", resource, e);
            }
        }
    }
}
