package de.gbv.logguy.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.common.TestLogs;

class RotatedScannerTest {

    @TempDir
    Path dir;

    private final FakeServer server = new FakeServer();

    private final byte[] sample = TestLogs.mycoreSample();

    private final TestClock clock = new TestClock();

    RotatedScannerTest() throws IOException {
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private RotatedScanner scanner(String glob) {
        AgentConfig config = server.config(List.of());
        return new RotatedScanner(new FileSender(new IngestClient(config), config.chunkSize()), "digibib", glob,
            config.rotationGrace(), config.rotatedScanInterval(), config.rotatedRetryMax(), clock);
    }

    private void gzip(Path file, byte[] data) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(data);
        }
    }

    @Test
    void deliversMissingRestFromGzipAndSkipsCompleteFiles() throws Exception {
        AgentConfig config = server.config(List.of());
        IngestClient client = new IngestClient(config);
        String fp = Fingerprint.of(sample);
        // the live file was sent up to 100.000 bytes before the agent went down
        client.send(fp, 0, sample, 100_000, "digibib", dir.resolve("mycore-app.log"));
        gzip(dir.resolve("2026-10/mycore-app-10-01-2026-1.log.gz"), sample);
        byte[] other = Arrays.copyOfRange(sample, 200_000, 300_000);
        gzip(dir.resolve("2026-10/mycore-app-10-02-2026-1.log.gz"), other);
        Files.writeString(dir.resolve("2026-10/unrelated.txt"), "x");

        RotatedScanner scanner = scanner(dir + "/*/mycore-app-*.log.gz");
        scanner.scan();
        assertArrayEquals(sample, server.content(fp));
        assertTrue(server.closed.get(fp));
        assertArrayEquals(other, server.content(Fingerprint.of(other)));
        assertTrue(server.closed.get(Fingerprint.of(other)));

        int before = server.requests;
        scanner.scan();
        assertEquals(before, server.requests, "complete files are not read again");
    }

    @Test
    void unreadableDirectoryAndFileDoNotStopTheScan() throws Exception {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")), "root reads everything");
        byte[] first = Arrays.copyOfRange(sample, 0, 50_000);
        byte[] second = Arrays.copyOfRange(sample, 50_000, 100_000);
        byte[] third = Arrays.copyOfRange(sample, 100_000, 150_000);
        gzip(dir.resolve("2026-09/mycore-app-09-30-2026-1.log.gz"), first);
        gzip(dir.resolve("2026-10/mycore-app-10-01-2026-1.log.gz"), second);
        gzip(dir.resolve("2026-11/mycore-app-11-01-2026-1.log.gz"), third);
        Files.setPosixFilePermissions(dir.resolve("2026-09"), PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(dir.resolve("2026-10"), PosixFilePermissions.fromString("---------"));
        Files.setPosixFilePermissions(dir.resolve("2026-09/mycore-app-09-30-2026-1.log.gz"),
            PosixFilePermissions.fromString("---------"));
        try {
            GlobFiles.Result found = new GlobFiles(dir + "/*/mycore-app-*.log.gz").find();
            assertEquals(List.of(dir.resolve("2026-10")), found.unreadable());
            assertEquals(2, found.files().size());

            scanner(dir + "/*/mycore-app-*.log.gz").scan();
            assertEquals(0, server.content(Fingerprint.of(first)).length);
            assertEquals(0, server.content(Fingerprint.of(second)).length);
            assertArrayEquals(third, server.content(Fingerprint.of(third)));
        } finally {
            Files.setPosixFilePermissions(dir.resolve("2026-10"), PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(dir.resolve("2026-09/mycore-app-09-30-2026-1.log.gz"),
                PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void unreadableFileIsSentOnceItBecomesReadable() throws Exception {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")), "root reads everything");
        Path file = dir.resolve("mycore-app-10-01-2026-1.log.gz");
        gzip(file, sample);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        RotatedScanner scanner = scanner(dir + "/mycore-app-*.log.gz");
        scanner.scan();
        assertTrue(server.streams.isEmpty());
        assertEquals(java.util.Set.of(file), scanner.failed());

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        scanner.scan();
        String fp = Fingerprint.of(sample);
        assertArrayEquals(sample, server.content(fp));
        assertTrue(server.closed.get(fp));
        assertTrue(scanner.failed().isEmpty());
    }

    @Test
    void brokenGzipDoesNotStopTheScan() throws Exception {
        byte[] first = Arrays.copyOfRange(sample, 0, 50_000);
        byte[] second = Arrays.copyOfRange(sample, 50_000, 100_000);
        byte[] third = Arrays.copyOfRange(sample, 100_000, 150_000);
        byte[] fourth = Arrays.copyOfRange(sample, 150_000, 200_000);
        gzip(dir.resolve("mycore-app-10-01-2026-1.log.gz"), first);
        Path truncated = dir.resolve("mycore-app-10-02-2026-1.log.gz");
        gzip(truncated, second);
        byte[] compressed = Files.readAllBytes(truncated);
        Files.write(truncated, Arrays.copyOf(compressed, compressed.length / 2));
        Path noGzip = dir.resolve("mycore-app-10-03-2026-1.log.gz");
        Files.write(noGzip, third);
        gzip(dir.resolve("mycore-app-10-04-2026-1.log.gz"), fourth);

        RotatedScanner scanner = scanner(dir + "/mycore-app-*.log.gz");
        scanner.scan();
        assertArrayEquals(first, server.content(Fingerprint.of(first)));
        assertArrayEquals(fourth, server.content(Fingerprint.of(fourth)));
        assertTrue(server.closed.get(Fingerprint.of(fourth)));
        assertFalse(server.closed.getOrDefault(Fingerprint.of(second), false));
        assertEquals(java.util.Set.of(truncated, noGzip), scanner.failed());

        int before = server.requests;
        clock.advance(Duration.ofHours(2));
        scanner.scan();
        assertEquals(before, server.requests, "a broken file is only tried again once it changes");

        // logrotate finishes the file
        gzip(truncated, second);
        scanner.scan();
        assertArrayEquals(second, server.content(Fingerprint.of(second)));
        assertTrue(server.closed.get(Fingerprint.of(second)));
        assertEquals(java.util.Set.of(noGzip), scanner.failed());
    }

    @Test
    void unreachableServerStopsTheScan() throws Exception {
        gzip(dir.resolve("mycore-app-10-01-2026-1.log.gz"), sample);
        RotatedScanner scanner = scanner(dir + "/mycore-app-*.log.gz");
        server.close();
        scanner.scan();
        assertTrue(scanner.failed().isEmpty(), "the file is not to blame");
    }

    @Test
    void refusedFileIsTriedAgainWithGrowingWaits() throws Exception {
        Path file = dir.resolve("mycore-app-10-01-2026-1.log.gz");
        gzip(file, sample);
        Duration interval = server.config(List.of()).rotatedScanInterval();
        RotatedScanner scanner = scanner(dir + "/mycore-app-*.log.gz");
        server.reject = true;
        scanner.scan();
        assertEquals(java.util.Set.of(file), scanner.failed());

        int before = server.requests;
        scanner.scan();
        assertEquals(before, server.requests, "no new attempt before the wait is over");
        clock.advance(interval);
        scanner.scan();
        assertEquals(before + 1, server.requests, "second attempt after rotated-scan-interval");
        clock.advance(interval);
        scanner.scan();
        assertEquals(before + 1, server.requests, "the wait has doubled");

        // the token may write the application again
        server.reject = false;
        clock.advance(interval);
        scanner.scan();
        String fp = Fingerprint.of(sample);
        assertArrayEquals(sample, server.content(fp));
        assertTrue(server.closed.get(fp));
        assertTrue(scanner.failed().isEmpty());
    }

    @Test
    void uncompressedFileStaysOpenUntilItIsOlderThanTheGracePeriod() throws Exception {
        Duration grace = server.config(List.of()).rotationGrace();
        Path file = dir.resolve("access.log.1");
        Files.write(file, Arrays.copyOf(sample, 40_000));
        Files.setLastModifiedTime(file, FileTime.from(clock.instant()));
        RotatedScanner scanner = scanner(dir + "/access.log.*");
        scanner.scan();
        String fp = Fingerprint.of(sample);
        assertArrayEquals(Arrays.copyOf(sample, 40_000), server.content(fp));
        assertFalse(server.closed.get(fp), "a process may still write to the renamed file");

        // httpd writes to the old descriptor until the reload
        Files.write(file, Arrays.copyOfRange(sample, 40_000, 50_000), StandardOpenOption.APPEND);
        Files.setLastModifiedTime(file, FileTime.from(clock.instant().plusSeconds(10)));
        clock.advance(grace);
        scanner.scan();
        assertArrayEquals(Arrays.copyOf(sample, 50_000), server.content(fp));
        assertFalse(server.closed.get(fp));

        clock.advance(Duration.ofSeconds(10));
        scanner.scan();
        assertTrue(server.closed.get(fp));
        int before = server.requests;
        scanner.scan();
        assertEquals(before, server.requests, "complete files are not read again");
    }

    /**
     * Debian's logrotate for Apache httpd: {@code rotate 14}, {@code compress}, {@code delaycompress} and a graceful
     * reload. Every rotation shifts all numbers by one, the files keep their fingerprints.
     */
    @Test
    void numberedRotationWithDelaycompressSendsEachFileOnce() throws Exception {
        Duration grace = server.config(List.of()).rotationGrace();
        RotatedScanner scanner = scanner(dir + "/access.log.*");
        Path live = dir.resolve("access.log");
        List<byte[]> days = List.of(Arrays.copyOfRange(sample, 0, 30_000), Arrays.copyOfRange(sample, 30_000, 70_000),
            Arrays.copyOfRange(sample, 70_000, 90_000), Arrays.copyOfRange(sample, 90_000, 150_000));
        for (byte[] day : days) {
            Files.write(live, Arrays.copyOf(day, day.length - 1000));
            Files.setLastModifiedTime(live, FileTime.from(clock.instant()));
            logrotate(14);
            // old children write to the renamed file until their requests are done
            Files.write(dir.resolve("access.log.1"), Arrays.copyOfRange(day, day.length - 1000, day.length),
                StandardOpenOption.APPEND);
            Files.setLastModifiedTime(dir.resolve("access.log.1"), FileTime.from(clock.instant()));
            scanner.scan();
            clock.advance(grace.plusSeconds(1));
            scanner.scan();
            clock.advance(Duration.ofDays(1));
        }
        assertEquals(days.size(), server.streams.size());
        for (byte[] day : days) {
            String fp = Fingerprint.of(day);
            assertArrayEquals(day, server.content(fp));
            assertTrue(server.closed.get(fp));
        }
        assertTrue(Files.exists(dir.resolve("access.log.3.gz")));
        assertTrue(scanner.failed().isEmpty());
    }

    /**
     * A rotated file below the fingerprint prefix, e.g. a quiet {@code error.log.1}, may still grow until the reload is
     * done. Its fingerprint is hashed over the whole content, so it must not be sent before it is complete.
     */
    @Test
    void smallUncompressedFileWaitsUntilItIsComplete() throws Exception {
        Duration grace = server.config(List.of()).rotationGrace();
        Path file = dir.resolve("error.log.1");
        byte[] content = Arrays.copyOf(sample, 600);
        Files.write(file, Arrays.copyOf(content, 400));
        Files.setLastModifiedTime(file, FileTime.from(clock.instant()));
        RotatedScanner scanner = scanner(dir + "/error.log.*");
        scanner.scan();
        assertTrue(server.streams.isEmpty(), "the fingerprint of a growing small file is not final");

        // the parent logs the graceful restart to the old descriptor
        Files.write(file, Arrays.copyOfRange(content, 400, 600), StandardOpenOption.APPEND);
        Files.setLastModifiedTime(file, FileTime.from(clock.instant().plusSeconds(1)));
        clock.advance(grace.plusSeconds(2));
        scanner.scan();
        assertEquals(1, server.streams.size());
        assertArrayEquals(content, server.content(Fingerprint.of(content)));
        assertTrue(server.closed.get(Fingerprint.of(content)));
    }

    /**
     * Rotates like logrotate with {@code compress} and {@code delaycompress}: shifts the numbers, compresses the former
     * {@code .1} and renames the live file to {@code .1}.
     */
    private void logrotate(int keep) throws IOException {
        Files.deleteIfExists(dir.resolve("access.log." + keep + ".gz"));
        for (int i = keep - 1; i >= 2; i--) {
            Path from = dir.resolve("access.log." + i + ".gz");
            if (Files.exists(from)) {
                Files.move(from, dir.resolve("access.log." + (i + 1) + ".gz"));
            }
        }
        Path first = dir.resolve("access.log.1");
        if (Files.exists(first)) {
            Path second = dir.resolve("access.log.2");
            Files.move(first, second);
            gzip(dir.resolve("access.log.2.gz"), Files.readAllBytes(second));
            Files.delete(second);
        }
        Files.move(dir.resolve("access.log"), first);
        Files.createFile(dir.resolve("access.log"));
    }

    @Test
    void backfillSendsEachFileOnce() throws Exception {
        AgentConfig config = server.config(List.of());
        Path file = dir.resolve("mycore-app-10-01-2026-1.log.gz");
        gzip(file, sample);
        assertEquals(0, AgentMain.backfill(config, "digibib", 2, List.of(file.toString(), file.toString())));
        assertArrayEquals(sample, server.content(Fingerprint.of(sample)));
        assertEquals(1, server.streams.size());
    }
}
