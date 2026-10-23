package de.gbv.logguy.server.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.solr.common.SolrInputDocument;

import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.IndexerProperties;
import de.gbv.logguy.server.hot.FileMeta;
import de.gbv.logguy.server.hot.HotFile;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.script.FieldDefinition;
import de.gbv.logguy.server.script.ParsedEvent;
import de.gbv.logguy.server.script.Parser;
import de.gbv.logguy.server.script.ParserRegistry;
import de.gbv.logguy.server.script.ParserScript;
import de.gbv.logguy.server.script.ScriptException;
import de.gbv.logguy.server.script.ScriptRuntime;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads new bytes of every file in {@code hot/}, cuts them into events with the parser script of the application and
 * sends the documents to Solr.
 * <p>
 * The document ID is {@code <fingerprint>:<offset of the event>}, so indexing the same bytes again overwrites the same
 * documents. The progress per file is stored in {@code state/index/<fingerprint>}. An event that is still growing,
 * e.g. a stack trace, is indexed provisionally after {@code provisional-after} without new bytes; when more lines
 * arrive, the complete event replaces it under the same ID.
 */
public class Indexer implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger();

    private final IndexerProperties settings;

    private final HotStore store;

    private final ParserRegistry parsers;

    private final ScriptRuntime runtime;

    private final DocumentSink sink;

    private final Clock clock;

    private final JsonMapper json;

    private final Path stateDir;

    private final Map<String, Cursor> cursors = new ConcurrentHashMap<>();

    private final Set<Parser> allParsers = ConcurrentHashMap.newKeySet();

    private final ThreadLocal<Map<String, Parser>> threadParsers = ThreadLocal.withInitial(HashMap::new);

    private final Set<String> missingParsers = ConcurrentHashMap.newKeySet();

    private final ExecutorService workers;

    private final Thread scheduler;

    private final AtomicLong events = new AtomicLong();

    private final AtomicLong bytes = new AtomicLong();

    private final AtomicLong parseErrors = new AtomicLong();

    private volatile boolean running = true;

    /**
     * Creates and starts the indexer.
     *
     * @param properties configuration
     * @param store the hot storage
     * @param parsers the parser scripts
     * @param runtime the script runtime
     * @param sink where documents go, usually Solr
     * @param clock clock for provisional events
     * @param json mapper for state files
     * @throws IOException if the state directory cannot be created
     */
    public Indexer(LogGuyProperties properties, HotStore store, ParserRegistry parsers, ScriptRuntime runtime,
        DocumentSink sink, Clock clock, JsonMapper json) throws IOException {
        this.settings = properties.indexer();
        this.store = store;
        this.parsers = parsers;
        this.runtime = runtime;
        this.sink = sink;
        this.clock = clock;
        this.json = json;
        this.stateDir = properties.stateDir().resolve("index");
        Files.createDirectories(stateDir);
        this.workers = Executors.newFixedThreadPool(settings.threads(),
            Thread.ofPlatform().name("indexer-", 0).factory());
        this.scheduler = Thread.ofPlatform().name("indexer-scheduler").daemon().start(this::schedule);
    }

    private void schedule() {
        Duration statsInterval = settings.statsInterval();
        Instant nextStats = clock.instant().plus(statsInterval);
        long lastEvents = 0;
        long lastBytes = 0;
        while (running) {
            try {
                scan();
                Instant now = clock.instant();
                if (!now.isBefore(nextStats)) {
                    long currentEvents = events.get();
                    long currentBytes = bytes.get();
                    if (currentEvents > lastEvents) {
                        double seconds = statsInterval.toMillis() / 1000.0;
                        LOGGER.info("indexed {} events/s, {} MB/s, {} events and {} parse errors in total",
                            (long) ((currentEvents - lastEvents) / seconds),
                            String.format("%.1f", (currentBytes - lastBytes) / seconds / 1024 / 1024), currentEvents,
                            parseErrors.get());
                    }
                    lastEvents = currentEvents;
                    lastBytes = currentBytes;
                    nextStats = now.plus(statsInterval);
                }
                Thread.sleep(settings.pollInterval());
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                LOGGER.error("indexer scan failed", e);
            }
        }
    }

    /**
     * Submits every file with work to the worker threads. Each file is processed by one worker at a time.
     */
    void scan() {
        for (HotFile file : store.files()) {
            Cursor cursor = cursors.computeIfAbsent(file.fingerprint(), fp -> new Cursor(file, loadState(fp, file)));
            if (cursor.done || cursor.busy || !cursor.hasWork()) {
                continue;
            }
            cursor.busy = true;
            workers.execute(() -> {
                try {
                    process(cursor);
                } catch (Exception e) {
                    LOGGER.error("cannot index {}", file.relativePath(), e);
                    cursor.reader = null;
                } finally {
                    cursor.busy = false;
                }
            });
        }
    }

    /**
     * Number of files that are not completely indexed.
     *
     * @return files with a backlog
     */
    public long backlog() {
        return cursors.values().stream().filter(c -> !c.done && c.hasWork()).count();
    }

    /**
     * Number of events indexed since the start.
     *
     * @return the event count
     */
    public long indexedEvents() {
        return events.get();
    }

    private void process(Cursor cursor) throws Exception {
        HotFile file = cursor.file;
        FileMeta meta = file.meta();
        ParserScript script = parsers.forApp(meta.app()).orElse(null);
        if (script == null) {
            if (missingParsers.add(meta.app())) {
                LOGGER.warn("no parser for application {}, its files are not indexed", meta.app());
            }
            return;
        }
        Parser parser = parser(script);
        if (cursor.reader == null) {
            cursor.reader = new EventReader(cursor.state.offset(), (int) settings.maxEventSize().toBytes());
            cursor.lastTs = cursor.state.lastTs() != null ? cursor.state.lastTs() : meta.firstReceived();
            cursor.provisional = -1;
        }
        EventReader reader = cursor.reader;
        List<SolrInputDocument> batch = new ArrayList<>();
        long read = 0;
        long roundBytes = settings.roundSize().toBytes();
        while (read < roundBytes) {
            byte[] data = file.read(reader.readOffset(), (int) settings.readSize().toBytes());
            if (data.length == 0) {
                break;
            }
            read += data.length;
            List<EventReader.RawEvent> found = reader.feed(data, parser::isStart);
            for (int i = 0; i < found.size(); i++) {
                batch.add(document(file, meta, parser, found.get(i), cursor));
                if (batch.size() >= settings.batchSize()) {
                    // the state must not pass events of this block that are not sent yet
                    long next = i + 1 < found.size() ? found.get(i + 1).offset() : reader.resumeOffset();
                    send(batch, cursor, next, false);
                }
            }
        }
        boolean caughtUp = reader.readOffset() >= file.length();
        if (caughtUp && meta.closed()) {
            for (EventReader.RawEvent event : reader.tail(parser::isStart)) {
                batch.add(document(file, meta, parser, event, cursor));
            }
            send(batch, cursor, file.length(), true);
            cursor.reader = null;
            LOGGER.info("indexed {} completely", file.relativePath());
            return;
        }
        if (caughtUp && reader.hasPending() && cursor.provisional != reader.readOffset()
            && !clock.instant().isBefore(meta.lastReceived().plus(settings.provisionalAfter()))) {
            // indexed now and replaced under the same ID when more lines arrive
            for (EventReader.RawEvent event : reader.tail(parser::isStart)) {
                batch.add(document(file, meta, parser, event, cursor));
            }
            cursor.provisional = reader.readOffset();
        }
        send(batch, cursor, reader.resumeOffset(), false);
    }

    private void send(List<SolrInputDocument> batch, Cursor cursor, long offset, boolean done) throws Exception {
        if (!batch.isEmpty()) {
            sink.add(batch);
            events.addAndGet(batch.size());
        }
        batch.clear();
        if (offset != cursor.state.offset() || done != cursor.state.done()) {
            bytes.addAndGet(offset - cursor.state.offset());
            cursor.state = new IndexState(offset, cursor.lastTs, done);
            cursor.done = done;
            saveState(cursor.file.fingerprint(), cursor.state);
        }
    }

    private Parser parser(ParserScript script) throws ScriptException {
        Map<String, Parser> map = threadParsers.get();
        Parser parser = map.get(script.name());
        if (parser == null || !parser.script().version().equals(script.version())) {
            if (parser != null) {
                allParsers.remove(parser);
                parser.close();
            }
            parser = new Parser(runtime, script);
            allParsers.add(parser);
            map.put(script.name(), parser);
        }
        return parser;
    }

    private SolrInputDocument document(HotFile file, FileMeta meta, Parser parser, EventReader.RawEvent event,
        Cursor cursor) {
        ParsedEvent parsed = parser.parse(event.text());
        Instant ts = parsed.ts() != null ? parsed.ts() : cursor.lastTs;
        cursor.lastTs = ts;
        SolrInputDocument doc = new SolrInputDocument();
        doc.setField("id", meta.fingerprint() + ":" + event.offset());
        doc.setField("app", meta.app());
        doc.setField("host", meta.host());
        doc.setField("file", file.relativePath());
        doc.setField("offset", event.offset());
        doc.setField("ts", Date.from(ts));
        doc.setField("message", parsed.message());
        doc.setField("parser_version", parser.script().version());
        if (parsed.error() != null) {
            parseErrors.incrementAndGet();
            doc.setField("parse_error", parsed.error());
        }
        for (Map.Entry<String, Object> field : parsed.fields().entrySet()) {
            FieldDefinition definition = parser.script().fields().get(field.getKey());
            Object value = field.getValue();
            if (value instanceof Instant instant) {
                value = Date.from(instant);
            }
            doc.setField(definition.solrField(), value);
        }
        return doc;
    }

    private IndexState loadState(String fingerprint, HotFile file) {
        Path path = stateDir.resolve(fingerprint);
        if (Files.exists(path)) {
            try {
                return json.readValue(path.toFile(), IndexState.class);
            } catch (RuntimeException e) {
                LOGGER.error("broken state {}, indexing {} again", path, file.relativePath(), e);
            }
        }
        return new IndexState(0, null, false);
    }

    private void saveState(String fingerprint, IndexState state) throws IOException {
        Path path = stateDir.resolve(fingerprint);
        Path tmp = stateDir.resolve(fingerprint + ".tmp");
        Files.write(tmp, json.writeValueAsBytes(state));
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        scheduler.interrupt();
        scheduler.join();
        workers.shutdown();
        if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
            workers.shutdownNow();
        }
        allParsers.forEach(Parser::close);
    }

    /**
     * Where the documents go.
     */
    @FunctionalInterface
    public interface DocumentSink {

        /**
         * Adds or replaces documents.
         *
         * @param documents the documents
         * @throws Exception if they cannot be stored, the indexer tries again later
         */
        void add(List<SolrInputDocument> documents) throws Exception;
    }

    private final class Cursor {

        private final HotFile file;

        private volatile IndexState state;

        private volatile boolean busy;

        private volatile boolean done;

        private EventReader reader;

        private Instant lastTs;

        private long provisional = -1;

        private Cursor(HotFile file, IndexState state) {
            this.file = file;
            this.state = state;
            this.done = state.done();
        }

        private boolean hasWork() {
            long read = reader != null ? reader.readOffset() : state.offset();
            if (read < file.length()) {
                return true;
            }
            if (reader == null) {
                return file.meta().closed() && !done;
            }
            if (file.meta().closed()) {
                return true;
            }
            return reader.hasPending() && provisional != reader.readOffset()
                && !clock.instant().isBefore(file.meta().lastReceived().plus(settings.provisionalAfter()));
        }
    }
}
