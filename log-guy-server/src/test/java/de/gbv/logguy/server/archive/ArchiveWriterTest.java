package de.gbv.logguy.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.common.StreamStatus;
import de.gbv.logguy.common.TestLogs;
import de.gbv.logguy.server.config.AppGroups;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.config.LogGuyProperties.ArchiveProperties;
import de.gbv.logguy.server.config.LogGuyProperties.ArchivePseudonymizeProperties;
import de.gbv.logguy.server.config.LogGuyProperties.HotProperties;
import de.gbv.logguy.server.config.LogGuyProperties.ScriptProperties;
import de.gbv.logguy.server.hot.HotFile;
import de.gbv.logguy.server.files.DeletedFiles;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.ingest.IngestException;
import de.gbv.logguy.server.ingest.IngestService;
import de.gbv.logguy.server.pseudonym.ArchiveTokens;
import de.gbv.logguy.server.pseudonym.PseudonymService;
import de.gbv.logguy.server.retention.HotCleaner;
import de.gbv.logguy.server.script.ParsedEvent;
import de.gbv.logguy.server.script.Parser;
import de.gbv.logguy.server.script.ParserRegistry;
import de.gbv.logguy.server.script.ParserScript;
import de.gbv.logguy.server.script.ScriptKind;
import de.gbv.logguy.server.script.ScriptRuntime;
import de.gbv.logguy.server.script.ScriptStore;
import de.gbv.logguy.server.script.TextFunctions;
import de.gbv.logguy.server.security.IngestPrincipal;
import tools.jackson.databind.json.JsonMapper;

class ArchiveWriterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String PLACEHOLDER = "[zurückgehalten {filter} {offset}]";

    private static ScriptRuntime runtime;

    @TempDir
    Path dataDir;

    private LogGuyProperties properties;

    private ScriptStore scripts;

    private byte[] sample;

    @BeforeAll
    static void startRuntime() {
        runtime = new ScriptRuntime(Duration.ofMillis(200), Duration.ofSeconds(5));
    }

    @AfterAll
    static void stopRuntime() {
        runtime.close();
    }

    @BeforeEach
    void setUp() throws IOException {
        Path scriptDir = dataDir.resolve("scripts");
        Files.createDirectories(scriptDir.resolve("lifecycle"));
        properties = new LogGuyProperties(dataDir, ZoneId.of("Europe/Berlin"),
            Map.of("digibib", new AppProperties(null, "mycore-log4j")), List.of(), List.of(), null,
            new HotProperties(DataSize.ofKilobytes(64), Duration.ofSeconds(60), Duration.ofSeconds(5), 3),
            new ScriptProperties(scriptDir, Duration.ofMillis(200), Duration.ofSeconds(5), 100,
                DataSize.ofKilobytes(256), 1000, 100),
            null,
            null, null, null, null, null,
            new ArchiveProperties(true, 2, Duration.ofMillis(50), 3, 7, PLACEHOLDER, "[f {filter}]", null,
                new ArchivePseudonymizeProperties(List.of("IP"))),
            null, null,
            null);
        scripts = new ScriptStore(scriptDir, Clock.systemUTC(), JSON);
        // the sample has shortened IP addresses already, two full ones show the filter
        String text = new String(TestLogs.mycoreSample(), StandardCharsets.UTF_8);
        int middle = text.indexOf('\n', text.length() / 2) + 1;
        sample = (text.substring(0, middle)
            + "2026-10-01T09:00:00,000 WARN  guest MCRIPFilter: request from 192.0.2.123 and 2001:db8:1:2::7\n"
            + text.substring(middle)).getBytes(StandardCharsets.UTF_8);
    }

    private void writeScript(String name, String code) throws IOException {
        Files.writeString(properties.scripts().dir().resolve("lifecycle/" + name + ".js"), code);
        scripts.scan();
    }

    private static HotFile fill(HotStore hot, byte[] data, boolean close) throws Exception {
        HotFile file = hot.getOrCreate(Fingerprint.of(data), "app1", "digibib");
        for (int offset = 0; offset < data.length; offset += 30_000) {
            int length = Math.min(30_000, data.length - offset);
            file.append(offset, Arrays.copyOfRange(data, offset, offset + length), length, "/logs/mycore-app.log",
                Instant.now());
        }
        if (close) {
            file.close(data.length, "/logs/mycore-app.log", Instant.now());
        }
        return file;
    }

    private static byte[] readAll(ArchiveFile file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long offset = 0;
        while (offset < file.meta().length()) {
            byte[] bytes = file.read(offset, 100_000);
            out.write(bytes);
            offset += bytes.length;
        }
        return out.toByteArray();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not reached");
            }
            Thread.sleep(20);
        }
    }

    private static String expected(byte[] data) {
        String[] lines = new String(data, StandardCharsets.UTF_8).split("\n", -1);
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            out.add(TextFunctions.maskIp(line));
        }
        return String.join("\n", out);
    }

    private PseudonymService pseudonyms() {
        return new PseudonymService(runtime, scripts, JSON, () -> AppGroups.NONE);
    }

    private ParserRegistry parsers() {
        ParserRegistry parsers = new ParserRegistry(properties, runtime);
        assertTrue(parsers.load().isEmpty());
        return parsers;
    }

    @Test
    void archivesClosedFileWithShortenedIps() throws Exception {
        try (HotStore hot = new HotStore(properties, JSON, Clock.systemUTC())) {
            HotFile file = fill(hot, sample, true);
            ArchiveStore archive = new ArchiveStore(properties, JSON);
            LifecycleFilters filters = new LifecycleFilters(runtime, scripts, JSON, () -> AppGroups.NONE);
            assertTrue(filters.errors().isEmpty(), filters.errors().toString());
            try (ArchiveWriter writer =
                new ArchiveWriter(properties, hot, archive, filters, pseudonyms(), parsers(), runtime)) {
                await(() -> archive.find(file.fingerprint()).map(a -> a.meta().closed()).orElse(false));
                assertEquals(0, writer.backlog());
            }
            ArchiveFile target = archive.find(file.fingerprint()).orElseThrow();
            String content = new String(readAll(target), StandardCharsets.UTF_8);
            assertEquals(expected(sample), content);
            assertTrue(content.contains("request from 192.0.2.0 and 2001:db8::"), content);
            assertFalse(content.contains("192.0.2.123"));

            ArchiveMeta meta = target.meta();
            assertEquals(file.relativePath(), target.relativePath());
            assertEquals(sample.length, meta.sourceLength());
            assertEquals(content.split("\n").length, meta.lines());
            assertEquals(List.of("/logs/mycore-app.log"), meta.paths());
            assertEquals(2, meta.filters().size());
            assertTrue(meta.filters().getFirst().startsWith("ip-kuerzen@"), meta.filters().toString());
            assertTrue(meta.filters().getLast().startsWith("pseudonym@"), meta.filters().toString());
            assertEquals(0, meta.filterErrors());
            try (Parser parser = new Parser(runtime, mycore())) {
                String[] lines = new String(sample, StandardCharsets.UTF_8).split("\n");
                assertEquals(parser.parse(lines[0]).ts(), meta.from());
                ParsedEvent last = null;
                for (int i = lines.length - 1; last == null || last.ts() == null; i--) {
                    last = parser.isStart(lines[i]) ? parser.parse(lines[i]) : null;
                }
                assertEquals(last.ts(), meta.to());
            }
            assertTrue(target.frames().size() > 1, "frames " + target.frames().size());

            // reloading after a restart keeps everything
            ArchiveStore reloaded = new ArchiveStore(properties, JSON);
            assertEquals(meta, reloaded.find(file.fingerprint()).orElseThrow().meta());
        }
    }

    @Test
    void framesInsideLongStackTracesGetTheTimeOfTheirEvents() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int event = 0; event < 3; event++) {
            text.append("2025-02-13T0").append(event + 1).append(":00:00,000 ERROR guest MCRServlet: failed\n");
            // more than one frame of stack trace
            for (int i = 0; i < 3000; i++) {
                text.append("\tat org.apache.xalan.templates.ElemVariable.execute(ElemVariable.java:").append(i)
                    .append(")\n");
            }
        }
        text.append("2025-02-13T04:00:00,000 INFO  guest MCRServlet: done\n");
        byte[] data = text.toString().getBytes(StandardCharsets.UTF_8);
        try (HotStore hot = new HotStore(properties, JSON, Clock.systemUTC())) {
            HotFile file = fill(hot, data, true);
            ArchiveStore archive = new ArchiveStore(properties, JSON);
            LifecycleFilters filters = new LifecycleFilters(runtime, scripts, JSON, () -> AppGroups.NONE);
            try (ArchiveWriter writer =
                new ArchiveWriter(properties, hot, archive, filters, pseudonyms(), parsers(), runtime)) {
                await(() -> archive.find(file.fingerprint()).map(a -> a.meta().closed()).orElse(false));
            }
            List<ArchiveFrame> frames = archive.find(file.fingerprint()).orElseThrow().frames();
            assertTrue(frames.size() > 4, "frames " + frames.size());
            Instant start = Instant.parse("2025-02-13T00:00:00Z");
            Instant end = Instant.parse("2025-02-13T03:00:00Z");
            Instant previous = start;
            for (ArchiveFrame frame : frames) {
                assertFalse(frame.from().isBefore(previous), frame.toString());
                assertFalse(frame.to().isAfter(end), frame.toString());
                previous = frame.to();
            }
            assertEquals(end, frames.getLast().to());
        }
    }

    private static ParserScript mycore() throws Exception {
        return ParserScript.load(runtime, "mycore-log4j", ParserRegistry.template("mycore-log4j"));
    }

    @Test
    void dropsLinesAndWritesPlaceholderOnErrors() throws Exception {
        writeScript("cronjobs-weglassen", """
            export const meta = { apps: ["digibib"] };
            export function line(text) {
                return text.includes("Cronjob") ? null : text;
            }
            """);
        writeScript("kaputt", """
            export const meta = { apps: ["*"] };
            export function line(text) {
                if (text.includes("MCRIPFilter")) {
                    throw new Error("kaputt");
                }
                return text;
            }
            """);
        try (HotStore hot = new HotStore(properties, JSON, Clock.systemUTC())) {
            HotFile file = fill(hot, sample, true);
            ArchiveStore archive = new ArchiveStore(properties, JSON);
            LifecycleFilters filters = new LifecycleFilters(runtime, scripts, JSON, () -> AppGroups.NONE);
            try (ArchiveWriter writer =
                new ArchiveWriter(properties, hot, archive, filters, pseudonyms(), parsers(), runtime)) {
                await(() -> archive.find(file.fingerprint()).map(a -> a.meta().closed()).orElse(false));
                assertEquals(1, writer.filterErrors());
                assertTrue(writer.droppedLines() > 0);
            }
            ArchiveFile target = archive.find(file.fingerprint()).orElseThrow();
            String content = new String(readAll(target), StandardCharsets.UTF_8);
            assertFalse(content.contains("Cronjob"));
            assertFalse(content.contains("192.0.2"));
            long offset = new String(sample, StandardCharsets.UTF_8).indexOf("2026-10-01T09:00:00,000 WARN");
            assertTrue(content.contains("[zurückgehalten kaputt " + offset + "]\n"), content);
            assertEquals(1, target.meta().filterErrors());
            assertEquals(4, target.meta().filters().size(), target.meta().filters().toString());
        }
    }

    @Test
    void refilterReplacesPlaceholdersAfterTheFilterIsRepaired() throws Exception {
        String broken = """
            export const meta = { apps: ["*"] };
            export function line(text) {
                if (text.includes("MCRIPFilter")) {
                    throw new Error("kaputt");
                }
                return text;
            }
            """;
        writeScript("kaputt", broken);
        try (HotStore hot = new HotStore(properties, JSON, Clock.systemUTC())) {
            HotFile file = fill(hot, sample, true);
            byte[] other = "2026-10-02T10:00:00,000 INFO  guest MCRStartup: ohne Fehler\n"
                .getBytes(StandardCharsets.UTF_8);
            HotFile clean = fill(hot, other, true);
            ArchiveStore archive = new ArchiveStore(properties, JSON);
            LifecycleFilters filters = new LifecycleFilters(runtime, scripts, JSON, () -> AppGroups.NONE);
            try (ArchiveWriter writer =
                new ArchiveWriter(properties, hot, archive, filters, pseudonyms(), parsers(), runtime)) {
                await(() -> archive.find(file.fingerprint()).map(a -> a.meta().closed()).orElse(false)
                    && archive.find(clean.fingerprint()).map(a -> a.meta().closed()).orElse(false));
                ArchiveFile before = archive.find(file.fingerprint()).orElseThrow();
                assertEquals(1, before.meta().filterErrors());

                writeScript("kaputt", broken.replace("throw new Error(\"kaputt\")", "return text + \" [geprüft]\""));
                filters.changed(ScriptKind.LIFECYCLE, scripts);
                ArchiveWriter.RefilterReport report = writer.refilter(null, false);
                assertEquals(List.of(before.relativePath()), report.refiltered());
                assertEquals(0, report.placeholders());
                assertTrue(report.failed().isEmpty() && report.missing().isEmpty());
                assertThrows(IOException.class, () -> readAll(before));
            }
            ArchiveFile after = archive.find(file.fingerprint()).orElseThrow();
            String content = new String(readAll(after), StandardCharsets.UTF_8);
            assertTrue(content.contains("request from 192.0.2.0 and 2001:db8:: [geprüft]\n"), content);
            assertFalse(content.contains("zurückgehalten"));
            assertEquals(0, after.meta().filterErrors());
            assertTrue(after.meta().closed());
            assertFalse(Files.exists(properties.archiveDir().resolve(".refilter/app1/digibib/"
                + after.relativePath().substring("app1/digibib/".length()))));
            ArchiveStore reloaded = new ArchiveStore(properties, JSON);
            assertEquals(after.meta(), reloaded.find(file.fingerprint()).orElseThrow().meta());
            assertEquals(content, new String(readAll(reloaded.find(file.fingerprint()).orElseThrow()),
                StandardCharsets.UTF_8));
            assertFalse(Files.exists(properties.archiveDir().resolve(".refilter")));
        }
    }

    @Test
    void hotCleanerDeletesArchivedFilesAndIngestKeepsThemClosed() throws Exception {
        byte[] live = "2026-10-02T10:00:00,000 INFO  guest MCRStartup: läuft noch\n".getBytes(StandardCharsets.UTF_8);
        try (HotStore hot = new HotStore(properties, JSON, Clock.systemUTC())) {
            HotFile file = fill(hot, sample, true);
            HotFile open = fill(hot, live, false);
            ArchiveStore archive = new ArchiveStore(properties, JSON);
            LifecycleFilters filters = new LifecycleFilters(runtime, scripts, JSON, () -> AppGroups.NONE);
            try (ArchiveWriter writer =
                new ArchiveWriter(properties, hot, archive, filters, pseudonyms(), parsers(), runtime)) {
                await(() -> archive.find(file.fingerprint()).map(a -> a.meta().closed()).orElse(false));
            }
            Instant to = archive.find(file.fingerprint()).orElseThrow().meta().to();
            // today and the seven days before stay
            Clock early = Clock.fixed(to.plus(Duration.ofDays(7)), ZoneId.of("UTC"));
            assertEquals(List.of(), new HotCleaner(properties, hot, archive, null, parsers(), early).run());
            Clock late = Clock.fixed(to.plus(Duration.ofDays(9)), ZoneId.of("UTC"));
            HotCleaner cleaner = new HotCleaner(properties, hot, archive, null, parsers(), late);
            assertEquals(List.of(file.relativePath()), cleaner.run());
            assertTrue(hot.find(file.fingerprint()).isEmpty());
            assertTrue(hot.find(open.fingerprint()).isPresent());
            try (HotStore reloaded = new HotStore(properties, JSON, Clock.systemUTC())) {
                assertTrue(reloaded.find(file.fingerprint()).isEmpty());
            }

            // an agent sending the file again gets the closed stream, nothing new is stored
            IngestService ingest = new IngestService(hot, archive, new DeletedFiles(properties.stateDir(), JSON));
            IngestPrincipal agent = new IngestPrincipal("agent", "app1", Set.of("digibib"));
            StreamStatus closed = new StreamStatus(file.fingerprint(), sample.length, true);
            assertEquals(closed, ingest.status(agent, file.fingerprint()));
            assertEquals(closed, ingest.append(agent, file.fingerprint(), 0, "app1", "digibib", "/logs/x.log",
                Arrays.copyOf(sample, 100), 100));
            IngestException more = assertThrows(IngestException.class, () -> ingest.append(agent,
                file.fingerprint(), sample.length, "app1", "digibib", null, new byte[] { 'x' }, 1));
            assertEquals(HttpStatus.CONFLICT, more.getStatus());
            assertEquals(closed, ingest.close(agent, file.fingerprint(), sample.length, "app1", "digibib", null));
            assertTrue(hot.find(file.fingerprint()).isEmpty());
        }
    }

    @Test
    void pausesWhileAFilterDoesNotLoad() throws Exception {
        writeScript("syntaxfehler", "export const meta = { apps: [\"*\"] ;");
        try (HotStore hot = new HotStore(properties, JSON, Clock.systemUTC())) {
            HotFile file = fill(hot, sample, true);
            ArchiveStore archive = new ArchiveStore(properties, JSON);
            LifecycleFilters filters = new LifecycleFilters(runtime, scripts, JSON, () -> AppGroups.NONE);
            assertEquals(1, filters.errors().size());
            try (ArchiveWriter writer =
                new ArchiveWriter(properties, hot, archive, filters, pseudonyms(), parsers(), runtime)) {
                Thread.sleep(300);
                assertTrue(archive.find(file.fingerprint()).isEmpty());
                assertEquals(1, writer.pausedBy().size());

                Files.delete(properties.scripts().dir().resolve("lifecycle/syntaxfehler.js"));
                filters.changed(ScriptKind.LIFECYCLE, scriptsAfterScan());
                await(() -> archive.find(file.fingerprint()).map(a -> a.meta().closed()).orElse(false));
            }
        }
    }

    // structure of real lines from prod-logs/, the logins and the mail address are made up
    private static final String PERSONAL = """
        2025-03-05T00:03:04,550 WARN  guest MCRUserManager: Could not find requested user: mueller
        2025-03-05T00:08:30,227 INFO   MCRSessionFilter: user detected: mueller
        2025-03-05T00:09:59,999 INFO  mueller MCRMODSEmbargoReleaseCronjob: Searching embargoed objects
        2025-03-05T00:10:00,000 INFO  schulze MCRServlet: request from 192.0.2.17, mailto:anna.schulze@example.org
        2025-03-05T00:10:01,000 INFO  [Nutzer#0badc0de] MCRServlet: already pseudonymized
        """;

    private void writePseudonymScript(String name, String code) throws IOException {
        Path dir = properties.scripts().dir().resolve("pseudonym");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".js"), code);
        scripts.scan();
    }

    @Test
    void pseudonymizesWithTokensOfTheFile() throws Exception {
        byte[] first = PERSONAL.getBytes(StandardCharsets.UTF_8);
        byte[] second = ("2025-03-05T00:00:00,000 INFO  guest MCRServlet: another file\n" + PERSONAL)
            .getBytes(StandardCharsets.UTF_8);
        try (HotStore hot = new HotStore(properties, JSON, Clock.systemUTC())) {
            HotFile one = fill(hot, first, true);
            HotFile two = fill(hot, second, true);
            ArchiveStore archive = new ArchiveStore(properties, JSON);
            LifecycleFilters filters = new LifecycleFilters(runtime, scripts, JSON, () -> AppGroups.NONE);
            try (ArchiveWriter writer = new ArchiveWriter(properties, hot, archive, filters, pseudonyms(), parsers(),
                runtime)) {
                await(() -> archive.find(one.fingerprint()).map(a -> a.meta().closed()).orElse(false)
                    && archive.find(two.fingerprint()).map(a -> a.meta().closed()).orElse(false));
            }
            ArchiveTokens tokens = new ArchiveTokens(one.meta().key(), Set.of("IP"));
            String mueller = tokens.token("Nutzer", "mueller");
            String content = new String(readAll(archive.find(one.fingerprint()).orElseThrow()),
                StandardCharsets.UTF_8);
            assertEquals("""
                2025-03-05T00:03:04,550 WARN  guest MCRUserManager: Could not find requested user: %s
                2025-03-05T00:08:30,227 INFO   MCRSessionFilter: user detected: %s
                2025-03-05T00:09:59,999 INFO  %s MCRMODSEmbargoReleaseCronjob: Searching embargoed objects
                2025-03-05T00:10:00,000 INFO  %s MCRServlet: request from 192.0.2.0, mailto:%s
                2025-03-05T00:10:01,000 INFO  [Nutzer#0badc0de] MCRServlet: already pseudonymized
                """.formatted(mueller, mueller, mueller, tokens.token("Nutzer", "schulze"),
                tokens.token("Mail", "anna.schulze@example.org")), content);
            assertTrue(content.contains("[Mail#"), content);

            // another file, other tokens for the same values
            String other = new String(readAll(archive.find(two.fingerprint()).orElseThrow()),
                StandardCharsets.UTF_8);
            assertFalse(other.contains(mueller), other);
            assertTrue(other.contains(new ArchiveTokens(two.meta().key(), Set.of("IP")).token("Nutzer", "mueller")));
            assertFalse(other.contains("mueller"), other);
            assertTrue(archive.find(one.fingerprint()).orElseThrow().meta().filters().getLast()
                .startsWith("pseudonym@"));
        }
    }

    @Test
    void pseudonymizeWritesTheArchiveAgainWithANewScript() throws Exception {
        byte[] data = PERSONAL.getBytes(StandardCharsets.UTF_8);
        try (HotStore hot = new HotStore(properties, JSON, Clock.systemUTC())) {
            HotFile file = fill(hot, data, true);
            ArchiveStore archive = new ArchiveStore(properties, JSON);
            LifecycleFilters filters = new LifecycleFilters(runtime, scripts, JSON, () -> AppGroups.NONE);
            PseudonymService pseudonyms = pseudonyms();
            try (ArchiveWriter writer = new ArchiveWriter(properties, hot, archive, filters, pseudonyms, parsers(),
                runtime)) {
                await(() -> archive.find(file.fingerprint()).map(a -> a.meta().closed()).orElse(false));
                ArchiveFile before = archive.find(file.fingerprint()).orElseThrow();
                String label = before.meta().filters().getLast();
                assertEquals(new ArchiveWriter.PseudonymizeReport(List.of(), 0, 1, List.of(), 0, List.of()),
                    writer.pseudonymize(null, false));

                // a new script for a value the standard script leaves alone
                writePseudonymScript("cronjob", """
                    export const meta = { apps: ["*"] };
                    export function apply(event) {
                        event.message = event.message.replace(/\\w+Cronjob/, found => ps("Job", found));
                        return event;
                    }
                    """);
                pseudonyms.changed(ScriptKind.PSEUDONYM, scripts);
                ArchiveWriter.PseudonymizeReport report = writer.pseudonymize("digibib", false);
                assertEquals(List.of(before.relativePath()), report.pseudonymized());
                assertEquals(0, report.newKeys());
                assertTrue(report.failed().isEmpty(), report.failed().toString());
                assertThrows(IOException.class, () -> readAll(before));

                ArchiveFile after = archive.find(file.fingerprint()).orElseThrow();
                ArchiveTokens tokens = new ArchiveTokens(file.meta().key(), Set.of("IP"));
                String content = new String(readAll(after), StandardCharsets.UTF_8);
                // the tokens of the first pass stay, the new script adds its own with the key of the file
                assertTrue(content.contains("INFO  " + tokens.token("Nutzer", "mueller") + " "
                    + tokens.token("Job", "MCRMODSEmbargoReleaseCronjob") + ": Searching"), content);
                assertTrue(content.contains("[Nutzer#0badc0de] MCRServlet"), content);
                assertTrue(after.meta().closed());
                assertEquals(before.meta().lines(), after.meta().lines());
                assertFalse(after.meta().filters().contains(label), after.meta().filters().toString());
                assertEquals(1, after.meta().filters().stream().filter(f -> f.startsWith("pseudonym@")).count());
                assertEquals(1, writer.pseudonymize(null, false).unchanged());

                // without the original the key is random, the tokens of the archive stay as they are
                hot.delete(file);
                report = writer.pseudonymize(null, true);
                assertEquals(1, report.newKeys());
                assertEquals(content, new String(readAll(archive.find(file.fingerprint()).orElseThrow()),
                    StandardCharsets.UTF_8));
            }
            new ArchiveStore(properties, JSON);
            assertFalse(Files.exists(properties.archiveDir().resolve(".refilter")));
        }
    }

    @Test
    void failingPseudonymizationWritesThePlaceholder() throws Exception {
        writePseudonymScript("kaputt", """
            export const meta = { apps: ["*"] };
            export function apply(event) {
                if (event.message.includes("schulze")) {
                    throw new Error("kaputt");
                }
                return event;
            }
            """);
        byte[] data = PERSONAL.getBytes(StandardCharsets.UTF_8);
        try (HotStore hot = new HotStore(properties, JSON, Clock.systemUTC())) {
            HotFile file = fill(hot, data, true);
            ArchiveStore archive = new ArchiveStore(properties, JSON);
            LifecycleFilters filters = new LifecycleFilters(runtime, scripts, JSON, () -> AppGroups.NONE);
            try (ArchiveWriter writer = new ArchiveWriter(properties, hot, archive, filters, pseudonyms(), parsers(),
                runtime)) {
                await(() -> archive.find(file.fingerprint()).map(a -> a.meta().closed()).orElse(false));
                assertEquals(1, writer.filterErrors());
            }
            ArchiveFile target = archive.find(file.fingerprint()).orElseThrow();
            String content = new String(readAll(target), StandardCharsets.UTF_8);
            long offset = PERSONAL.indexOf("2025-03-05T00:10:00,000");
            assertTrue(content.contains("[zurückgehalten pseudonym/kaputt " + offset + "]\n"), content);
            assertFalse(content.contains("schulze"), content);
            assertEquals(1, target.meta().filterErrors());
        }
    }

    @Test
    void pausesWhileAPseudonymizationScriptDoesNotLoad() throws Exception {
        writePseudonymScript("syntaxfehler", "export const meta = { apps: [\"*\"] ;");
        try (HotStore hot = new HotStore(properties, JSON, Clock.systemUTC())) {
            HotFile file = fill(hot, PERSONAL.getBytes(StandardCharsets.UTF_8), true);
            ArchiveStore archive = new ArchiveStore(properties, JSON);
            LifecycleFilters filters = new LifecycleFilters(runtime, scripts, JSON, () -> AppGroups.NONE);
            PseudonymService pseudonyms = pseudonyms();
            try (ArchiveWriter writer = new ArchiveWriter(properties, hot, archive, filters, pseudonyms, parsers(),
                runtime)) {
                Thread.sleep(300);
                assertTrue(archive.find(file.fingerprint()).isEmpty());
                assertEquals(1, writer.pausedBy().size());

                Files.delete(properties.scripts().dir().resolve("pseudonym/syntaxfehler.js"));
                pseudonyms.changed(ScriptKind.PSEUDONYM, scriptsAfterScan());
                await(() -> archive.find(file.fingerprint()).map(a -> a.meta().closed()).orElse(false));
            }
            String content = new String(readAll(archive.find(file.fingerprint()).orElseThrow()),
                StandardCharsets.UTF_8);
            assertFalse(content.contains("mueller"), content);
        }
    }

    private ScriptStore scriptsAfterScan() throws IOException {
        scripts.scan();
        return scripts;
    }

    @Test
    void liveFileKeepsTheLastIncompleteLineUntilClosed() throws Exception {
        int cut = sample.length - 10;
        byte[] start = Arrays.copyOf(sample, cut);
        try (HotStore hot = new HotStore(properties, JSON, Clock.systemUTC())) {
            HotFile file = hot.getOrCreate(Fingerprint.of(sample), "app1", "digibib");
            file.append(0, start, start.length, "/logs/mycore-app.log", Instant.now());
            file.flushAged(Instant.now().plusSeconds(120));
            ArchiveStore archive = new ArchiveStore(properties, JSON);
            LifecycleFilters filters = new LifecycleFilters(runtime, scripts, JSON, () -> AppGroups.NONE);
            try (ArchiveWriter writer =
                new ArchiveWriter(properties, hot, archive, filters, pseudonyms(), parsers(), runtime)) {
                long framed = file.framedLength();
                assertTrue(framed < cut);
                await(() -> archive.find(file.fingerprint()).map(a -> a.meta().sourceLength() == framed)
                    .orElse(false));
                assertFalse(archive.find(file.fingerprint()).orElseThrow().meta().closed());

                file.append(cut, Arrays.copyOfRange(sample, cut, sample.length), sample.length - cut, null,
                    Instant.now());
                file.close(sample.length, null, Instant.now());
                await(() -> archive.find(file.fingerprint()).map(a -> a.meta().closed()).orElse(false));
            }
            ArchiveFile target = archive.find(file.fingerprint()).orElseThrow();
            assertEquals(expected(sample), new String(readAll(target), StandardCharsets.UTF_8));
        }
    }

    @Test
    void recoversFromACrashDuringAFrame() throws Exception {
        try (HotStore hot = new HotStore(properties, JSON, Clock.systemUTC())) {
            HotFile file = fill(hot, sample, true);
            ArchiveStore archive = new ArchiveStore(properties, JSON);
            LifecycleFilters filters = new LifecycleFilters(runtime, scripts, JSON, () -> AppGroups.NONE);
            try (ArchiveWriter writer =
                new ArchiveWriter(properties, hot, archive, filters, pseudonyms(), parsers(), runtime)) {
                await(() -> archive.find(file.fingerprint()).map(a -> a.meta().closed()).orElse(false));
            }
            ArchiveFile target = archive.find(file.fingerprint()).orElseThrow();
            ArchiveMeta meta = target.meta();
            String base = target.relativePath().substring(0, target.relativePath().length() - ".log.zst".length());
            Path dir = properties.archiveDir();
            Files.write(dir.resolve(base + ".log.zst"), new byte[] { 1, 2, 3 }, StandardOpenOption.APPEND);
            Files.writeString(dir.resolve(base + ".idx"), "{\"zst\":", StandardOpenOption.APPEND);
            ArchiveFile reloaded = new ArchiveStore(properties, JSON).find(file.fingerprint()).orElseThrow();
            assertEquals(meta, reloaded.meta());
            assertEquals(target.compressedSize(), Files.size(dir.resolve(base + ".log.zst")));
            assertEquals(expected(sample), new String(readAll(reloaded), StandardCharsets.UTF_8));
        }
    }
}
