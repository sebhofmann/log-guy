package de.gbv.logguy.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.common.TestLogs;

class LiveTailerTest {

    @TempDir
    Path dir;

    private final FakeServer server = new FakeServer();

    private final byte[] sample = TestLogs.mycoreSample();

    LiveTailerTest() throws IOException {
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private LiveTailer tailer(Path live) {
        AgentConfig config = server.config(List.of());
        return new LiveTailer(new IngestClient(config), "digibib", live, config.chunkSize());
    }

    private void append(Path file, int from, int to) throws IOException {
        Files.write(file, Arrays.copyOfRange(sample, from, to), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Test
    void followsGrowingFile() throws Exception {
        Path live = dir.resolve("mycore-app.log");
        try (LiveTailer tailer = tailer(live)) {
            tailer.poll();
            append(live, 0, 500);
            tailer.poll();
            assertTrue(server.streams.isEmpty(), "files below 1 KB wait");
            append(live, 500, 30_000);
            tailer.poll();
            append(live, 30_000, 100_000);
            tailer.poll();
            String fp = Fingerprint.of(sample);
            assertArrayEquals(Arrays.copyOf(sample, 100_000), server.content(fp));
            assertFalse(server.closed.get(fp));
        }
    }

    @Test
    void rotationByRenameClosesTheOldStream() throws Exception {
        Path live = dir.resolve("mycore-app.log");
        try (LiveTailer tailer = tailer(live)) {
            append(live, 0, 50_000);
            tailer.poll();
            Path rotated = dir.resolve("mycore-app-1.log");
            Files.move(live, rotated);
            // bytes written to the old file before the logger switched over
            append(rotated, 50_000, 60_000);
            append(live, 60_000, 90_000);
            tailer.poll();
            String old = Fingerprint.of(sample);
            assertArrayEquals(Arrays.copyOf(sample, 60_000), server.content(old));
            assertTrue(server.closed.get(old));
            tailer.poll();
            String current = Fingerprint.of(Arrays.copyOfRange(sample, 60_000, 90_000));
            assertArrayEquals(Arrays.copyOfRange(sample, 60_000, 90_000), server.content(current));
            assertFalse(server.closed.get(current));
        }
    }

    @Test
    void restartContinuesWhereTheServerIs() throws Exception {
        Path live = dir.resolve("mycore-app.log");
        append(live, 0, 40_000);
        try (LiveTailer tailer = tailer(live)) {
            tailer.poll();
        }
        append(live, 40_000, 80_000);
        int before = server.requests;
        try (LiveTailer tailer = tailer(live)) {
            tailer.poll();
        }
        assertArrayEquals(Arrays.copyOf(sample, 80_000), server.content(Fingerprint.of(sample)));
        // one status request and 40.000 bytes in chunks of 4 KB
        assertEquals(1 + 10, server.requests - before);
    }

    @Test
    void copytruncateStartsANewStream() throws Exception {
        Path live = dir.resolve("access.log");
        try (LiveTailer tailer = tailer(live)) {
            append(live, 0, 20_000);
            tailer.poll();
            Path copy = dir.resolve("access.log.1");
            Files.copy(live, copy);
            Files.write(live, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
            append(live, 20_000, 25_000);
            tailer.poll();
            String old = Fingerprint.of(sample);
            assertArrayEquals(Arrays.copyOf(sample, 20_000), server.content(old));
            assertFalse(server.closed.get(old), "the copy delivers the rest and closes the stream");
            String current = Fingerprint.of(Arrays.copyOfRange(sample, 20_000, 25_000));
            assertArrayEquals(Arrays.copyOfRange(sample, 20_000, 25_000), server.content(current));
        }
    }
}
