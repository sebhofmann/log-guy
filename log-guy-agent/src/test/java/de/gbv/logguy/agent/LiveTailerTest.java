package de.gbv.logguy.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
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

    private final TestClock clock = new TestClock();

    private final Duration grace = server.config(List.of()).rotationGrace();

    LiveTailerTest() throws IOException {
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private LiveTailer tailer(Path live) {
        AgentConfig config = server.config(List.of());
        return new LiveTailer(new IngestClient(config), "digibib", live, config.chunkSize(), config.rotationGrace(),
            clock);
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
    void rotationByRenameClosesTheOldStreamAfterTheGracePeriod() throws Exception {
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
            assertFalse(server.closed.get(old), "the old file may still grow");
            String current = Fingerprint.of(Arrays.copyOfRange(sample, 60_000, 90_000));
            assertArrayEquals(Arrays.copyOfRange(sample, 60_000, 90_000), server.content(current));
            assertFalse(server.closed.get(current));

            clock.advance(grace);
            tailer.poll();
            assertTrue(server.closed.get(old));
            assertArrayEquals(Arrays.copyOf(sample, 60_000), server.content(old));
            assertFalse(server.closed.get(current));
        }
    }

    @Test
    void writerOnTheOldDescriptorAfterRenameIsFollowed() throws Exception {
        Path live = dir.resolve("access.log");
        Path rotated = dir.resolve("access.log.1");
        String old = Fingerprint.of(sample);
        try (LiveTailer tailer = tailer(live);
            FileChannel writer = FileChannel.open(live, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            writer.write(ByteBuffer.wrap(sample, 0, 30_000));
            tailer.poll();
            // logrotate renames the file and creates a new one, httpd keeps writing to the old descriptor
            Files.move(live, rotated);
            Files.write(live, new byte[0]);
            writer.write(ByteBuffer.wrap(sample, 30_000, 10_000));
            tailer.poll();
            assertArrayEquals(Arrays.copyOf(sample, 40_000), server.content(old));
            assertFalse(server.closed.get(old));

            clock.advance(grace.dividedBy(2));
            writer.write(ByteBuffer.wrap(sample, 40_000, 10_000));
            tailer.poll();
            clock.advance(grace.dividedBy(2));
            writer.write(ByteBuffer.wrap(sample, 50_000, 10_000));
            tailer.poll();
            assertArrayEquals(Arrays.copyOf(sample, 60_000), server.content(old));
            assertFalse(server.closed.get(old), "the grace period starts again with every write");

            // the reload reopens the log, the old file stops growing
            append(live, 60_000, 70_000);
            clock.advance(grace.minusSeconds(1));
            tailer.poll();
            assertFalse(server.closed.get(old));
            clock.advance(Duration.ofSeconds(1));
            tailer.poll();
            assertArrayEquals(Arrays.copyOf(sample, 60_000), server.content(old));
            assertTrue(server.closed.get(old));
            String current = Fingerprint.of(Arrays.copyOfRange(sample, 60_000, 70_000));
            assertArrayEquals(Arrays.copyOfRange(sample, 60_000, 70_000), server.content(current));
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
