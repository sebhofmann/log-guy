package de.gbv.logguy.server.script;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.apache.solr.client.solrj.SolrServerException;
import org.springframework.security.access.AccessDeniedException;

import de.gbv.logguy.server.archive.LifecycleFilters;
import de.gbv.logguy.server.archive.LifecycleFilters.LifecycleTestResult;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.Right;
import de.gbv.logguy.server.config.LogGuyProperties.ScriptProperties;
import de.gbv.logguy.server.hot.FileMeta;
import de.gbv.logguy.server.hot.HotFile;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.index.EventDocuments;
import de.gbv.logguy.server.index.EventReader;
import de.gbv.logguy.server.index.EventReader.RawEvent;
import de.gbv.logguy.server.pseudonym.PseudonymService;
import de.gbv.logguy.server.pseudonym.PseudonymService.PseudonymTestResult;
import de.gbv.logguy.server.script.ScriptRequestException.Reason;
import de.gbv.logguy.server.script.ScriptStore.ScriptId;
import de.gbv.logguy.server.script.ScriptStore.TestSummary;
import de.gbv.logguy.server.search.SearchRequest;
import de.gbv.logguy.server.search.SearchService;
import de.gbv.logguy.server.search.SearchService.EventView;
import de.gbv.logguy.server.security.LogGuyUser;

/**
 * Runs scripts against test sources without changing anything: an application, a file in {@code hot/} or pasted
 * lines.
 */
public class ScriptTester {

    /** Bytes per line assumed when reading the end of a file. */
    private static final int LINE_GUESS = 400;

    /** Most bytes read from one file for a test. */
    private static final long MAX_WINDOW = 64L * 1024 * 1024;

    /** Most failed events listed separately. */
    private static final int MAX_FAILED = 50;

    private final LogGuyProperties properties;

    private final ScriptProperties settings;

    private final HotStore hot;

    private final ScriptRuntime runtime;

    private final ScriptService scripts;

    private final SearchService search;

    private final ParserRegistry parsers;

    private final PseudonymService pseudonyms;

    private final LifecycleFilters lifecycle;

    private final Clock clock;

    /**
     * Creates the tester.
     *
     * @param properties configuration
     * @param hot the hot storage
     * @param runtime the script runtime
     * @param scripts the scripts
     * @param search the search, to find the start of a time range
     * @param parsers the parsers, to turn lines into events for scripts that are no parsers
     * @param pseudonyms runs tests of pseudonymization scripts
     * @param lifecycle runs tests of lifecycle filters
     * @param clock clock for the time of a test
     */
    public ScriptTester(LogGuyProperties properties, HotStore hot, ScriptRuntime runtime, ScriptService scripts,
        SearchService search, ParserRegistry parsers, PseudonymService pseudonyms, LifecycleFilters lifecycle,
        Clock clock) {
        this.properties = properties;
        this.settings = properties.scripts();
        this.hot = hot;
        this.runtime = runtime;
        this.scripts = scripts;
        this.search = search;
        this.parsers = parsers;
        this.pseudonyms = pseudonyms;
        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    /**
     * Tests a script with the test of its kind.
     *
     * @param user the user, needs the right to edit the script and to read the source
     * @param id the script
     * @param code code to test, {@code null} for the draft or the active version
     * @param active test the active version even if there is a draft
     * @param source the test source
     * @return the result, depending on the kind
     * @throws IOException if the source cannot be read
     */
    public Object test(LogGuyUser user, ScriptId id, String code, boolean active, TestSource source)
        throws IOException {
        return switch (id.kind()) {
            case PARSER -> testParser(user, id, code, active, source);
            case PSEUDONYM -> testPseudonym(user, id, code, active, source);
            case LIFECYCLE -> testLifecycle(user, id, code, active, source);
            default -> throw new ScriptRequestException(Reason.INVALID,
                "Für Skripte der Art " + id.kind().dir() + " gibt es noch keinen Test.");
        };
    }

    /**
     * Tests a parser.
     *
     * @param user the user, needs the right to edit the script and to read the source
     * @param id the parser
     * @param code code to test, {@code null} for the draft or the active version
     * @param active test the active version even if there is a draft
     * @param source the test source
     * @return the result
     * @throws IOException if the source cannot be read
     */
    public ParserTestResult testParser(LogGuyUser user, ScriptId id, String code, boolean active,
        TestSource source) throws IOException {
        if (id.kind() != ScriptKind.PARSER) {
            throw new ScriptRequestException(Reason.INVALID, "Kein Parser: " + id);
        }
        String tested = scripts.codeForTest(user, id, code, active);
        ParserScript script;
        try {
            script = ParserScript.load(runtime, id.name(), tested);
        } catch (ScriptException e) {
            throw new ScriptRequestException(Reason.INVALID, "Das Skript ist fehlerhaft: " + e.getMessage());
        }
        Lines lines = lines(user, source);
        ParserTestResult result = run(script, lines);
        scripts.recordTest(id, tested, new TestSummary(clock.instant(), lines.label(), result.events(),
            result.errors()));
        return result;
    }

    /**
     * Tests a pseudonymization script. The lines are parsed with the parser of their application, so the script
     * sees the same events as on the MCP endpoint.
     *
     * @param user the user, needs the right to edit the script and to read the source
     * @param id the script
     * @param code code to test, {@code null} for the draft or the active version
     * @param active test the active version even if there is a draft
     * @param source the test source
     * @return the result
     * @throws IOException if the source cannot be read
     */
    public PseudonymTestResult testPseudonym(LogGuyUser user, ScriptId id, String code, boolean active,
        TestSource source) throws IOException {
        if (id.kind() != ScriptKind.PSEUDONYM) {
            throw new ScriptRequestException(Reason.INVALID, "Keine Pseudonymisierung: " + id);
        }
        String tested = scripts.codeForTest(user, id, code, active);
        Lines lines = lines(user, source);
        List<Map<String, Object>> events = events(lines);
        PseudonymTestResult result;
        try {
            result = pseudonyms.test(id, tested, events, settings.testEvents());
        } catch (ScriptException e) {
            throw new ScriptRequestException(Reason.INVALID, "Das Skript ist fehlerhaft: " + e.getMessage());
        }
        scripts.recordTest(id, tested, new TestSummary(clock.instant(), lines.label(), result.events(),
            result.heldBack()));
        return result;
    }

    /**
     * Tests a lifecycle filter: {@code line(text)} with the lines, {@code fields(event)} with the lines parsed by
     * the parser of their application.
     *
     * @param user the user, needs the right to edit the script and to read the source
     * @param id the filter
     * @param code code to test, {@code null} for the draft or the active version
     * @param active test the active version even if there is a draft
     * @param source the test source
     * @return the result
     * @throws IOException if the source cannot be read
     */
    public LifecycleTestResult testLifecycle(LogGuyUser user, ScriptId id, String code, boolean active,
        TestSource source) throws IOException {
        if (id.kind() != ScriptKind.LIFECYCLE) {
            throw new ScriptRequestException(Reason.INVALID, "Kein Lifecycle-Filter: " + id);
        }
        String tested = scripts.codeForTest(user, id, code, active);
        Lines lines = lines(user, source);
        LifecycleTestResult result;
        try {
            result = lifecycle.test(id.name(), tested, lines.label(), lines.lines(), events(lines),
                settings.testEvents());
        } catch (ScriptException e) {
            throw new ScriptRequestException(Reason.INVALID, "Das Skript ist fehlerhaft: " + e.getMessage());
        }
        scripts.recordTest(id, tested, new TestSummary(clock.instant(), lines.label(), result.lines(),
            result.errors() + result.eventErrors()));
        return result;
    }

    /**
     * Turns test lines into events as the search returns them, with the parser of the application. Without a
     * parser every line is an event with only a message.
     *
     * @param lines the lines
     * @return the events with {@code app}, {@code ts}, {@code message} and the fields
     */
    private List<Map<String, Object>> events(Lines lines) {
        List<Map<String, Object>> events = new ArrayList<>();
        if (lines.lines().isEmpty()) {
            return events;
        }
        String app = lines.app() == null ? "" : lines.app();
        Optional<ParserScript> script = lines.app() == null ? Optional.empty() : parsers.forApp(lines.app());
        if (script.isEmpty()) {
            for (String line : lines.lines()) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("app", app);
                event.put("message", line);
                events.add(event);
            }
            return events;
        }
        try (Parser parser = new Parser(runtime, script.get())) {
            EventReader reader = new EventReader(0, (int) properties.indexer().maxEventSize().toBytes());
            byte[] data = String.join("\n", lines.lines()).concat("\n").getBytes(StandardCharsets.UTF_8);
            List<RawEvent> raw = new ArrayList<>(reader.feed(data, parser::isStart));
            raw.addAll(reader.tail(parser::isStart));
            for (RawEvent rawEvent : raw) {
                ParsedEvent parsed = parser.parse(rawEvent.text());
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("app", app);
                if (parsed.ts() != null) {
                    event.put("ts", parsed.ts().toString());
                }
                event.put("message", parsed.message());
                event.putAll(parsed.fields());
                events.add(event);
            }
        } catch (ScriptException e) {
            throw new ScriptRequestException(Reason.INVALID, "Der Parser von " + app + " ist fehlerhaft: "
                + e.getMessage());
        }
        return events;
    }

    private ParserTestResult run(ParserScript script, Lines lines) {
        List<TestEvent> shown = new ArrayList<>();
        List<TestEvent> failed = new ArrayList<>();
        Set<String> undeclared = new TreeSet<>();
        Map<String, Integer> errorCounts = new LinkedHashMap<>();
        int events = 0;
        int errors = 0;
        long nanos = 0;
        try (Parser parser = new Parser(runtime, script)) {
            EventReader reader = new EventReader(0, (int) properties.indexer().maxEventSize().toBytes());
            byte[] data = String.join("\n", lines.lines()).concat("\n").getBytes(StandardCharsets.UTF_8);
            List<RawEvent> raw = new ArrayList<>(reader.feed(data, parser::isStart));
            raw.addAll(reader.tail(parser::isStart));
            int[] lineStarts = lineStarts(data);
            for (RawEvent event : raw) {
                long start = System.nanoTime();
                ParsedEvent parsed = parser.parse(event.text(), undeclared);
                nanos += System.nanoTime() - start;
                if (lines.from() != null && parsed.ts() != null
                    && (parsed.ts().isBefore(lines.from()) || !parsed.ts().isBefore(lines.to()))) {
                    continue;
                }
                events++;
                TestEvent view = new TestEvent(lineOf(lineStarts, event.offset()) + 1, parsed.ts(), parsed.message(),
                    parsed.fields(), parsed.error());
                if (parsed.error() != null || parsed.ts() == null) {
                    errors++;
                    errorCounts.merge(parsed.error() == null ? "ohne Zeitstempel" : parsed.error(), 1, Integer::sum);
                    if (failed.size() < MAX_FAILED) {
                        failed.add(view);
                    }
                }
                if (shown.size() < settings.testEvents()) {
                    shown.add(view);
                }
            }
        } catch (ScriptException e) {
            throw new ScriptRequestException(Reason.INVALID, "Das Skript ist fehlerhaft: " + e.getMessage());
        }
        double perSecond = nanos == 0 ? 0 : events * 1e9 / nanos;
        return new ParserTestResult(lines.label(), lines.lines().size(), events, errors, Math.round(perSecond),
            List.copyOf(script.fields().keySet()), List.copyOf(undeclared), errorCounts, shown, failed,
            events > shown.size());
    }

    /**
     * Reads the lines of a test source.
     *
     * @param user the user, needs {@link Right#READ} for the application
     * @param source the source
     * @return the lines without line breaks
     * @throws IOException if reading fails
     */
    public Lines lines(LogGuyUser user, TestSource source) throws IOException {
        int max = source.lines() == null ? settings.testLines() : Math.clamp(source.lines(), 1, settings.testLines());
        if (source.text() != null) {
            List<String> lines = split(source.text());
            String app = source.app() != null && properties.apps().containsKey(source.app()) ? source.app() : null;
            return new Lines(lines.subList(0, Math.min(lines.size(), max)), "eingefügte Zeilen", null, null, app);
        }
        if (source.file() != null) {
            HotFile file = hot.files().stream()
                .filter(f -> f.fingerprint().equals(source.file()) || f.relativePath().equals(source.file()))
                .findFirst()
                .orElseThrow(() -> new ScriptRequestException(Reason.NOT_FOUND,
                    "Die Datei " + source.file() + " liegt nicht in hot/."));
            requireRead(user, file.meta().app());
            List<String> lines = readLines(file, 0, max);
            return new Lines(lines, "Datei " + file.relativePath() + ", " + lines.size() + " Zeilen", null, null,
                file.meta().app());
        }
        if (source.app() == null) {
            throw new ScriptRequestException(Reason.INVALID, "Eine Testquelle fehlt: Anwendung, Datei oder Text.");
        }
        if (!properties.apps().containsKey(source.app())) {
            throw new ScriptRequestException(Reason.NOT_FOUND, "Die Anwendung " + source.app() + " gibt es nicht.");
        }
        requireRead(user, source.app());
        if (source.from() != null) {
            return range(user, source, max);
        }
        return last(source.app(), max);
    }

    private Lines last(String app, int max) throws IOException {
        List<HotFile> files = filesOf(app);
        Collections.reverse(files);
        List<String> lines = new ArrayList<>();
        for (HotFile file : files) {
            if (lines.size() >= max) {
                break;
            }
            int missing = max - lines.size();
            long length = file.length();
            long window = Math.min(length, Math.min(MAX_WINDOW, (long) missing * LINE_GUESS));
            List<String> tail;
            while (true) {
                tail = split(new String(read(file, length - window, length), StandardCharsets.UTF_8));
                if (window < length && !tail.isEmpty()) {
                    tail.removeFirst();
                }
                if (tail.size() >= missing || window >= length || window >= MAX_WINDOW) {
                    break;
                }
                window = Math.min(length, Math.min(MAX_WINDOW, window * 4));
            }
            lines.addAll(0, tail.subList(Math.max(0, tail.size() - missing), tail.size()));
        }
        return new Lines(lines, "Anwendung " + app + ", letzte " + lines.size() + " Zeilen", null, null, app);
    }

    private Lines range(LogGuyUser user, TestSource source, int max) throws IOException {
        Instant to = source.to() == null ? clock.instant() : source.to();
        EventView first;
        try {
            List<EventView> found = search.search(search.scope(user, new SearchRequest(List.of(source.app()), null,
                source.from(), to, Map.of())), null, 1, true).events();
            if (found.isEmpty()) {
                return new Lines(List.of(), "Anwendung " + source.app() + " ab " + source.from()
                    + ", keine Events im Index", source.from(), to, source.app());
            }
            first = found.getFirst();
        } catch (SolrServerException e) {
            throw new IOException(e);
        }
        String id = first.id();
        int separator = id.lastIndexOf(':');
        if (id.startsWith(EventDocuments.ARCHIVE_MARK, separator + 1)) {
            throw new ScriptRequestException(Reason.NOT_FOUND,
                "Das erste Event im Zeitraum wurde aus dem Archiv nachgeladen und liegt nicht in hot/.");
        }
        HotFile file = hot.find(id.substring(0, separator)).orElseThrow(() -> new ScriptRequestException(
            Reason.NOT_FOUND, "Die Datei des ersten Events liegt nicht mehr in hot/."));
        List<String> lines = readLines(file, Long.parseLong(id.substring(separator + 1)), max);
        return new Lines(lines, "Anwendung " + source.app() + " ab " + source.from() + ", " + lines.size()
            + " Zeilen", source.from(), to, source.app());
    }

    private List<HotFile> filesOf(String app) {
        return hot.files().stream()
            .filter(file -> file.meta().app().equals(app) && file.length() > 0)
            .sorted(Comparator.comparing((HotFile file) -> file.meta().lastReceived(),
                Comparator.nullsFirst(Comparator.naturalOrder())))
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    private static List<String> readLines(HotFile file, long offset, int max) throws IOException {
        List<String> lines = new ArrayList<>();
        long position = offset;
        long length = file.length();
        StringBuilder pending = new StringBuilder();
        while (position < length && lines.size() < max) {
            byte[] data = read(file, position, Math.min(length, position + 1024 * 1024));
            if (data.length == 0) {
                break;
            }
            position += data.length;
            int cut = lastNewline(data);
            pending.append(new String(data, 0, cut + 1, StandardCharsets.UTF_8));
            List<String> complete = split(pending.toString());
            pending.setLength(0);
            pending.append(new String(data, cut + 1, data.length - cut - 1, StandardCharsets.UTF_8));
            lines.addAll(complete.subList(0, Math.min(complete.size(), max - lines.size())));
        }
        if (lines.size() < max && !pending.isEmpty()) {
            lines.add(pending.toString());
        }
        return lines;
    }

    private static int lastNewline(byte[] data) {
        for (int i = data.length - 1; i >= 0; i--) {
            if (data[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static byte[] read(HotFile file, long from, long to) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(0, to - from));
        long position = from;
        while (position < to) {
            byte[] data = file.read(position, (int) Math.min(Integer.MAX_VALUE, to - position));
            if (data.length == 0) {
                break;
            }
            out.write(data);
            position += data.length;
        }
        return out.toByteArray();
    }

    private static List<String> split(String text) {
        if (text.isEmpty()) {
            return new ArrayList<>();
        }
        String trimmed = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        return new ArrayList<>(List.of(trimmed.split("\r?\n", -1)));
    }

    private static int[] lineStarts(byte[] data) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n' && i + 1 < data.length) {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int lineOf(int[] starts, long offset) {
        int index = Arrays.binarySearch(starts, (int) offset);
        return index >= 0 ? index : -index - 2;
    }

    private static void requireRead(LogGuyUser user, String app) {
        if (!user.mayRead(app)) {
            throw new AccessDeniedException("no read right for " + app);
        }
    }

    /**
     * Where the test lines come from: {@code text}, otherwise {@code file}, otherwise {@code app}.
     *
     * @param app an application: its last lines, or the lines from {@code from} on. With {@code text} only the
     *     parser of the application is used for scripts that need events.
     * @param lines the most lines, {@code null} for {@code log-guy.scripts.test-lines}
     * @param from start of a time range for {@code app}
     * @param to end of the time range, {@code null} for now
     * @param file a file in {@code hot/}, by fingerprint or relative path
     * @param text pasted lines
     */
    public record TestSource(String app, Integer lines, Instant from, Instant to, String file, String text) {
    }

    /**
     * The lines of a test source.
     *
     * @param lines the lines
     * @param label a description for the result and the draft
     * @param from events before are left out, {@code null} for all
     * @param to events from here on are left out
     * @param app the application of the lines, {@code null} if unknown
     */
    public record Lines(List<String> lines, String label, Instant from, Instant to, String app) {
    }

    /**
     * An event of a test.
     *
     * @param line the line the event starts at, from 1
     * @param ts the time stamp
     * @param message the message
     * @param fields the declared fields
     * @param error the error of the script
     */
    public record TestEvent(int line, Instant ts, String message, Map<String, Object> fields, String error) {
    }

    /**
     * Result of a parser test.
     *
     * @param source the test source
     * @param lines lines read
     * @param events events parsed
     * @param errors events with an error or without time stamp
     * @param eventsPerSecond speed of the script
     * @param declared the declared fields
     * @param undeclared names the script returned without declaring them
     * @param errorCounts errors by message
     * @param sample the first events
     * @param failed the first events with errors
     * @param truncated whether more events were parsed than shown
     */
    public record ParserTestResult(String source, int lines, int events, int errors, long eventsPerSecond,
        List<String> declared, List<String> undeclared, Map<String, Integer> errorCounts, List<TestEvent> sample,
        List<TestEvent> failed, boolean truncated) {
    }
}
