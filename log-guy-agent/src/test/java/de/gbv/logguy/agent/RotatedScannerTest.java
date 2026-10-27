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
