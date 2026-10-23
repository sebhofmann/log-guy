package de.gbv.logguy.server.reindex;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.solr.common.SolrInputDocument;

import de.gbv.logguy.server.archive.ArchiveFile;
import de.gbv.logguy.server.archive.ArchiveFrame;
import de.gbv.logguy.server.archive.ArchiveMeta;
import de.gbv.logguy.server.archive.ArchiveStore;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.ReindexProperties;
import de.gbv.logguy.server.hot.FileMeta;
import de.gbv.logguy.server.hot.HotFile;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.index.EventDocuments;
import de.gbv.logguy.server.index.EventReader;
import de.gbv.logguy.server.index.EventReader.RawEvent;
import de.gbv.logguy.server.index.Indexer;
import de.gbv.logguy.server.index.Indexer.DocumentSink;
import de.gbv.logguy.server.reindex.ReindexJob.Plan;
import de.gbv.logguy.server.reindex.ReindexJob.State;
import de.gbv.logguy.server.retention.RetentionService;
import de.gbv.logguy.server.script.ParsedEvent;
import de.gbv.logguy.server.script.Parser;
import de.gbv.logguy.server.script.ParserRegistry;
import de.gbv.logguy.server.script.ParserScript;
import de.gbv.logguy.server.script.ScriptException;
import de.gbv.logguy.server.script.ScriptRuntime;

/**
 * Indexes a time range of an application again. Files of {@code hot/} in the range are handed to the indexer, which
 * reads them again from their start with the current parser. The part of the range before the events in the index
 * is loaded from the filtered files in {@code archive/}; these events get an expiry date {@code expires} and are
 * deleted again when it has passed. One job loads from the archive at a time, further jobs wait.
 */
public class ReindexService implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger();

    private final LogGuyProperties properties;

    private final ReindexProperties settings;

    private final HotStore hot;

    private final ArchiveStore archive;

    private final Indexer indexer;

    private final ParserRegistry parsers;

    private final ScriptRuntime runtime;

    private final DocumentSink sink;

    private final OldestEvent oldest;

    private final RetentionService retention;

    private final Clock clock;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(
        Thread.ofPlatform().name("reindex").factory());

    private final Deque<ReindexJob> jobs = new ArrayDeque<>();

    /**
     * Creates the service.
     *
     * @param properties configuration
     * @param hot the originals
     * @param archive the archive
     * @param indexer the indexer, {@code null} if indexing is disabled
     * @param parsers the parser scripts
     * @param runtime the script runtime
     * @param sink where loaded events go, usually Solr
     * @param oldest finds the oldest event of an application in the index
     * @param retention knows the index window of each application
     * @param clock clock for expiry and the limits
     */
    public ReindexService(LogGuyProperties properties, HotStore hot, ArchiveStore archive, Indexer indexer,
        ParserRegistry parsers, ScriptRuntime runtime, DocumentSink sink, OldestEvent oldest,
        RetentionService retention, Clock clock) {
        this.properties = properties;
        this.settings = properties.reindex();
        this.hot = hot;
        this.archive = archive;
        this.indexer = indexer;
        this.parsers = parsers;
        this.runtime = runtime;
        this.sink = sink;
        this.oldest = oldest;
        this.retention = retention;
        this.clock = clock;
    }

    /**
     * Computes what a job for a time range would do.
     *
     * @param app the application
     * @param from start of the range
     * @param to end of the range, {@code null} for now
     * @param source which storage to read
     * @return the plan
     * @throws ReindexException if the request is invalid or there is nothing to do
     */
    public Plan plan(String app, Instant from, Instant to, Source source) throws ReindexException {
        if (app == null || app.isBlank()) {
            throw new ReindexException("Keine Anwendung angegeben.");
        }
        if (parsers.forApp(app).isEmpty()) {
            throw new ReindexException("Für die Anwendung " + app + " gibt es keinen Parser.");
        }
        if (from == null) {
            throw new ReindexException("Kein Beginn des Zeitraums angegeben.");
        }
        Instant end = to == null ? clock.instant() : to;
        if (!from.isBefore(end)) {
            throw new ReindexException("Der Beginn des Zeitraums liegt nicht vor seinem Ende.");
        }
        Instant window = retention.windowStart(app);

        List<String> hotFiles = new ArrayList<>();
        if (source != Source.ARCHIVE && end.isAfter(window)) {
            if (indexer == null) {
                throw new ReindexException("Der Indexer ist abgeschaltet (log-guy.indexer.enabled).");
            }
            hot.files().stream()
                .filter(file -> file.meta().app().equals(app) && overlaps(file.meta(), from, end))
                .sorted(Comparator.comparing(HotFile::relativePath))
                .forEach(file -> hotFiles.add(file.fingerprint()));
        }

        Instant archiveFrom = null;
        Instant archiveTo = null;
        List<String> archiveFiles = new ArrayList<>();
        long bytes = 0;
        if (source != Source.HOT && from.isBefore(window)) {
            Instant limit = end;
            try {
                Optional<Instant> first = oldest.of(app);
                if (first.isPresent() && first.get().isBefore(limit)) {
                    limit = first.get();
                }
            } catch (Exception e) {
                throw new ReindexException("Der Index ist nicht erreichbar: " + e.getMessage(), e);
            }
            if (from.isBefore(limit)) {
                if (Duration.between(from, limit).compareTo(settings.maxRange()) > 0) {
                    throw new ReindexException("Aus dem Archiv lassen sich höchstens " + settings.maxRange().toDays()
                        + " Tage auf einmal laden.");
                }
                archiveFrom = from;
                archiveTo = limit;
                for (ArchiveFile file : archive.files()) {
                    ArchiveMeta meta = file.meta();
                    if (!meta.app().equals(app) || meta.from() == null) {
                        continue;
                    }
                    FrameRange range = FrameRange.of(file.frames(), archiveFrom, archiveTo);
                    if (range != null) {
                        archiveFiles.add(file.fingerprint());
                        bytes += range.bytes();
                    }
                }
            }
        }
        boolean archiveEmpty = archiveFrom != null && archiveFiles.isEmpty();
        if (archiveEmpty) {
            archiveFrom = null;
            archiveTo = null;
        }
        if (hotFiles.isEmpty() && archiveFrom == null) {
            if (archiveEmpty) {
                throw new ReindexException("Im Archiv liegt für " + app + " in diesem Zeitraum nichts.");
            }
            throw new ReindexException(source == Source.ARCHIVE
                ? "Der Zeitraum liegt ganz im Index, aus dem Archiv ist nichts zu laden."
                : "Im Zeitraum gibt es nichts neu zu indexieren.");
        }
        return new Plan(app, from, end, hotFiles, archiveFrom, archiveTo, archiveFiles, bytes);
    }

    private static boolean overlaps(FileMeta meta, Instant from, Instant to) {
        return (meta.lastReceived() == null || !meta.lastReceived().isBefore(from))
            && (meta.firstReceived() == null || meta.firstReceived().isBefore(to));
    }

    /**
     * Starts a job: hands the files of {@code hot/} to the indexer and loads the archive part in the background.
     *
     * @param user who starts it
     * @param plan what to do, from {@link #plan(String, Instant, Instant, Source)}
     * @return the job
     */
    public ReindexJob start(String user, Plan plan) {
        Instant now = clock.instant();
        ReindexJob job = new ReindexJob(UUID.randomUUID().toString(), user, plan, now,
            now.plus(settings.expiresAfter()));
        for (String fingerprint : plan.hotFiles()) {
            indexer.reindex(fingerprint);
        }
        synchronized (jobs) {
            jobs.addFirst(job);
            while (jobs.size() > settings.keepJobs()) {
                if (jobs.getLast().running()) {
                    break;
                }
                jobs.removeLast();
            }
        }
        LOGGER.info("reindex {} of {} from {} to {} by {}: {} files of hot/, {} files of archive/", job.id(),
            plan.app(), plan.from(), plan.to(), user, plan.hotFiles().size(), plan.archiveFiles().size());
        if (plan.loadsArchive()) {
            worker.execute(() -> run(job));
        } else {
            job.finish(State.DONE, null, now);
        }
        return job;
    }

    /**
     * A job by its id.
     *
     * @param id the job
     * @param visible whether the caller may see a job of an application
     * @return the job
     * @throws NoSuchElementException if there is no such job the caller may see
     */
    public ReindexJob job(String id, Predicate<String> visible) {
        synchronized (jobs) {
            return jobs.stream().filter(job -> job.id().equals(id) && visible.test(job.plan().app())).findFirst()
                .orElseThrow(() -> new NoSuchElementException("Unbekannter Auftrag " + id));
        }
    }

    /**
     * The recent jobs, newest first.
     *
     * @param visible whether the caller may see a job of an application
     * @return the jobs
     */
    public List<ReindexJob> jobs(Predicate<String> visible) {
        synchronized (jobs) {
            return jobs.stream().filter(job -> visible.test(job.plan().app())).toList();
        }
    }

    /**
     * Deletes the loaded events whose expiry date has passed. Errors are logged, the next run tries again.
     */
    public void expire() {
        try {
            sink.delete(RetentionService.expiredQuery(clock.instant()));
        } catch (Exception e) {
            LOGGER.error("cannot delete expired events", e);
        }
    }

    private void run(ReindexJob job) {
        Plan plan = job.plan();
        ParserScript script = parsers.forApp(plan.app()).orElse(null);
        if (script == null) {
            job.finish(State.FAILED, "Der Parser der Anwendung ist nicht mehr vorhanden.", clock.instant());
            return;
        }
        try (Parser parser = new Parser(runtime, script)) {
            for (String fingerprint : plan.archiveFiles()) {
                if (job.cancelled()) {
                    break;
                }
                ArchiveFile file = archive.find(fingerprint).orElse(null);
                if (file == null) {
                    job.fail(fingerprint);
                    continue;
                }
                try {
                    load(job, file, parser);
                } catch (IOException e) {
                    LOGGER.error("reindex {}: cannot read {}", job.id(), file.relativePath(), e);
                    job.fail(file.relativePath());
                }
                job.fileDone();
            }
            boolean cancelled = job.cancelled();
            job.finish(cancelled ? State.CANCELLED : State.DONE, cancelled ? "Abgebrochen." : null,
                clock.instant());
            LOGGER.info("reindex {} {}: {} events loaded", job.id(), cancelled ? "cancelled" : "done",
                job.status().events());
        } catch (ScriptException e) {
            job.finish(State.FAILED, "Der Parser ist fehlerhaft: " + e.getMessage(), clock.instant());
        } catch (Exception e) {
            LOGGER.error("reindex {} failed", job.id(), e);
            job.finish(State.FAILED, "Fehler beim Laden: " + e.getMessage(), clock.instant());
        }
    }

    private void load(ReindexJob job, ArchiveFile file, Parser parser) throws Exception {
        Plan plan = job.plan();
        List<ArchiveFrame> frames = file.frames();
        FrameRange range = FrameRange.of(frames, plan.archiveFrom(), plan.archiveTo());
        if (range == null) {
            return;
        }
        ArchiveMeta meta = file.meta();
        Loader loader = new Loader(job, file, meta, parser, range.first() > 0);
        EventReader reader = new EventReader(frames.get(range.first()).raw(),
            (int) properties.indexer().maxEventSize().toBytes());
        for (int i = range.first(); i <= range.last() && !job.cancelled(); i++) {
            byte[] data = file.readFrame(frames.get(i));
            for (RawEvent event : reader.feed(data, parser::isStart)) {
                loader.add(event);
            }
            job.progress(data.length, 0);
        }
        if (!job.cancelled()) {
            for (RawEvent event : reader.tail(parser::isStart)) {
                loader.add(event);
            }
        }
        loader.flush();
    }

    @Override
    public void close() throws InterruptedException {
        synchronized (jobs) {
            jobs.forEach(ReindexJob::cancel);
        }
        worker.shutdown();
        if (!worker.awaitTermination(30, TimeUnit.SECONDS)) {
            worker.shutdownNow();
        }
    }

    /**
     * Which storage a job reads.
     */
    public enum Source {
        /** {@code hot/} in the index window and {@code archive/} before. */
        ALL,
        /** Only the files of {@code hot/}, e.g. after a change of the parser. */
        HOT,
        /** Only the archive, for time ranges before the index window. */
        ARCHIVE
    }

    /**
     * Finds the oldest event of an application in the index, without the events loaded from the archive.
     */
    @FunctionalInterface
    public interface OldestEvent {

        /**
         * Finds the time of the oldest event.
         *
         * @param app the application
         * @return the time, empty if the application has no events in the index
         * @throws Exception if the index cannot be asked
         */
        Optional<Instant> of(String app) throws Exception;
    }

    /**
     * The frames of an archive file to read for a time range: one before the first frame reaching into the range,
     * because its last event may continue there, and one after the range, because the last event of the range may
     * continue into it.
     */
    record FrameRange(int first, int last, long bytes) {

        static FrameRange of(List<ArchiveFrame> frames, Instant from, Instant to) {
            int hit = 0;
            while (hit < frames.size() && frames.get(hit).to().isBefore(from)) {
                hit++;
            }
            if (hit == frames.size() || !frames.get(hit).from().isBefore(to)) {
                return null;
            }
            int first = Math.max(0, hit - 1);
            int last = hit;
            while (last < frames.size() - 1 && frames.get(last).from().isBefore(to)) {
                last++;
            }
            long bytes = 0;
            for (int i = first; i <= last; i++) {
                bytes += frames.get(i).rlen();
            }
            return new FrameRange(first, last, bytes);
        }
    }

    private final class Loader {

        private final ReindexJob job;

        private final ArchiveFile file;

        private final ArchiveMeta meta;

        private final Parser parser;

        private final Date expires;

        private final List<SolrInputDocument> batch = new ArrayList<>();

        private boolean checkStart;

        private Instant lastTs;

        private Loader(ReindexJob job, ArchiveFile file, ArchiveMeta meta, Parser parser, boolean checkStart) {
            this.job = job;
            this.file = file;
            this.meta = meta;
            this.parser = parser;
            this.checkStart = checkStart;
            this.expires = Date.from(job.expires());
        }

        private void add(RawEvent event) throws Exception {
            if (checkStart) {
                checkStart = false;
                // reading started in the middle of the file, the first lines may be the rest of an event
                int newline = event.text().indexOf('\n');
                if (!parser.isStart(newline < 0 ? event.text() : event.text().substring(0, newline))) {
                    return;
                }
            }
            ParsedEvent parsed = parser.parse(event.text());
            Instant ts = parsed.ts() != null ? parsed.ts() : lastTs;
            if (ts == null) {
                return;
            }
            lastTs = ts;
            Plan plan = job.plan();
            if (ts.isBefore(plan.archiveFrom()) || !ts.isBefore(plan.archiveTo())) {
                return;
            }
            SolrInputDocument doc = EventDocuments.create(EventDocuments.archiveId(meta.fingerprint(),
                event.offset()), meta.app(), meta.host(), file.relativePath(), event.offset(), ts, parsed,
                parser.script());
            doc.setField("expires", expires);
            batch.add(doc);
            if (batch.size() >= properties.indexer().batchSize()) {
                flush();
            }
        }

        private void flush() throws Exception {
            if (!batch.isEmpty()) {
                sink.add(batch);
                job.progress(0, batch.size());
                batch.clear();
            }
        }
    }
}
