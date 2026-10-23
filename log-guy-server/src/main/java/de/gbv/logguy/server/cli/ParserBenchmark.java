package de.gbv.logguy.server.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;

import de.gbv.logguy.server.index.EventReader;
import de.gbv.logguy.server.script.ParsedEvent;
import de.gbv.logguy.server.script.Parser;
import de.gbv.logguy.server.script.ParserScript;
import de.gbv.logguy.server.script.ScriptException;
import de.gbv.logguy.server.script.ScriptRuntime;

/**
 * Measures a parser script on one log file, single threaded, without Solr: lines are cut into events with
 * {@code isStart}, every event goes through {@code parse}. The file is decompressed into memory first.
 */
public final class ParserBenchmark {

    private static final int READ_SIZE = 1024 * 1024;

    private static final double MIB = 1024 * 1024;

    private ParserBenchmark() {
    }

    /**
     * Runs the benchmark.
     *
     * @param runtime the script runtime
     * @param script the parser
     * @param file the log file, plain or {@code .gz}
     * @param rounds number of rounds, later rounds show the effect of the JIT compiler
     * @param out where the results go
     * @throws IOException if the file cannot be read
     * @throws ScriptException if the script cannot be loaded
     */
    public static void run(ScriptRuntime runtime, ParserScript script, Path file, int rounds, PrintWriter out)
        throws IOException, ScriptException {
        byte[] data;
        try (InputStream in = file.getFileName().toString().endsWith(".gz")
            ? new GZIPInputStream(Files.newInputStream(file), 1 << 16) : Files.newInputStream(file)) {
            data = in.readAllBytes();
        }
        out.printf("%s: %.1f MiB, script %s %s, %s %s%n", file.getFileName(), data.length / MIB, script.name(),
            script.version(), System.getProperty("java.vm.name"), System.getProperty("java.vm.version"));
        out.println("round  split only s  isStart+parse s  events      events/s  MiB/s  errors");
        out.flush();
        try (Parser parser = new Parser(runtime, script)) {
            for (int round = 1; round <= rounds; round++) {
                long start = System.nanoTime();
                long lines = split(data);
                double split = (System.nanoTime() - start) / 1e9;
                start = System.nanoTime();
                long events = 0;
                long errors = 0;
                EventReader reader = new EventReader(0, READ_SIZE);
                for (int offset = 0; offset < data.length; offset += READ_SIZE) {
                    byte[] chunk = java.util.Arrays.copyOfRange(data, offset, Math.min(data.length,
                        offset + READ_SIZE));
                    for (EventReader.RawEvent event : reader.feed(chunk, parser::isStart)) {
                        errors += parse(parser, event);
                        events++;
                    }
                }
                List<EventReader.RawEvent> tail = reader.tail(parser::isStart);
                for (EventReader.RawEvent event : tail) {
                    errors += parse(parser, event);
                    events++;
                }
                double seconds = (System.nanoTime() - start) / 1e9;
                out.printf("%5d  %12.2f  %15.2f  %9d  %10.0f  %5.1f  %6d%n", round, split, seconds, events,
                    events / seconds, data.length / MIB / seconds, errors);
                out.flush();
                if (round == 1) {
                    out.printf("       %d lines%n", lines);
                }
            }
        }
    }

    private static int parse(Parser parser, EventReader.RawEvent event) {
        ParsedEvent parsed = parser.parse(event.text());
        return parsed.error() == null ? 0 : 1;
    }

    private static long split(byte[] data) {
        EventReader reader = new EventReader(0, READ_SIZE);
        long[] lines = new long[1];
        for (int offset = 0; offset < data.length; offset += READ_SIZE) {
            byte[] chunk = java.util.Arrays.copyOfRange(data, offset, Math.min(data.length, offset + READ_SIZE));
            reader.feed(chunk, line -> {
                lines[0]++;
                return true;
            });
        }
        return lines[0];
    }
}
