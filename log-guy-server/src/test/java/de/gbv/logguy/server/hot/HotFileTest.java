package de.gbv.logguy.server.hot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.common.TestLogs;
import de.gbv.logguy.server.config.LogGuyProperties.HotProperties;
import tools.jackson.databind.json.JsonMapper;

class HotFileTest {

    private static final HotProperties SETTINGS = new HotProperties(DataSize.ofKilobytes(16), Duration.ofSeconds(60),
        Duration.ofSeconds(5), 3);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    @TempDir
    Path root;

    private final byte[] sample = TestLogs.mycoreSample();

    private final String fingerprint = Fingerprint.of(sample);

    private HotFile create() throws IOException {
        return HotFile.create(root, fingerprint, "app1", "digibib", LocalDate.of(2026, 10, 23), NOW, SETTINGS, JSON);
    }

    private HotFile reload() throws IOException {
        Path meta = root.resolve("app1/digibib/2026-10-23-" + fingerprint + HotFile.META_SUFFIX);
        return HotFile.load(root, meta, SETTINGS, JSON, NOW);
    }

    private static byte[] readAll(HotFile file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long offset = 0;
        while (offset < file.length()) {
            byte[] bytes = file.read(offset, 100_000);
            out.write(bytes);
            offset += bytes.length;
        }
        return out.toByteArray();
    }

    @Test
    void overlappingAndRepeatedChunksAreStoredOnce() throws Exception {
        HotFile file = create();
        Random random = new Random(42);
        int offset = 0;
        while (offset < sample.length) {
            int length = Math.min(sample.length - offset, 1 + random.nextInt(20_000));
            file.append(offset, Arrays.copyOfRange(sample, offset, offset + length), length, "/logs/mycore-app.log",
                NOW);
            // send part of it again, as an agent after a timeout would
            int back = random.nextInt(length + 1);
            file.append(offset + length - back, Arrays.copyOfRange(sample, offset + length - back, offset + length),
                back, "/logs/mycore-app.log", NOW);
            offset += length;
        }
        assertEquals(sample.length, file.length());
        assertArrayEquals(sample, readAll(file));
        assertTrue(file.frameCount() > 10, "frames: " + file.frameCount());
        assertEquals(List.of("/logs/mycore-app.log"), file.meta().paths());
    }

    @Test
    void framesEndAtLineEnds() throws Exception {
        HotFile file = create();
        file.append(0, sample, sample.length, null, NOW);
        long offset = 0;
        while (offset < file.length()) {
            byte[] bytes = file.read(offset, Integer.MAX_VALUE);
            offset += bytes.length;
            if (offset < sample.length - SETTINGS.frameSize().toBytes()) {
                assertEquals('\n', bytes[bytes.length - 1]);
            }
        }
    }

    @Test
    void gapIsRejected() throws Exception {
        HotFile file = create();
        file.append(0, sample, 2000, null, NOW);
        GapException e = assertThrows(GapException.class,
            () -> file.append(2001, Arrays.copyOfRange(sample, 2001, 3000), 999, null, NOW));
        assertEquals(2000, e.getStatus().length());
    }

    @Test
    void closedFileTakesNoNewBytes() throws Exception {
        HotFile file = create();
        file.append(0, sample, 5000, "/logs/mycore-app.log", NOW);
        assertThrows(GapException.class, () -> file.close(4999, null, NOW));
        assertTrue(file.close(5000, "/logs/2026-10/mycore-app-10-23-2026-1.log.gz", NOW).closed());
        assertEquals(5000, file.append(0, sample, 5000, null, NOW).length());
        assertThrows(GapException.class, () -> file.append(0, sample, 5001, null, NOW));
        HotFile loaded = reload();
        assertTrue(loaded.status().closed());
        assertArrayEquals(Arrays.copyOf(sample, 5000), readAll(loaded));
        assertEquals(2, loaded.meta().paths().size());
        assertTrue(Files.notExists(root.resolve("app1/digibib/2026-10-23-" + fingerprint + ".tail")));
    }

    @Test
    void reloadKeepsPendingBytes() throws Exception {
        HotFile file = create();
        file.append(0, sample, 100_000, null, NOW);
        file.release();
        HotFile loaded = reload();
        assertEquals(100_000, loaded.length());
        loaded.append(100_000, Arrays.copyOfRange(sample, 100_000, sample.length), sample.length - 100_000, null,
            NOW);
        assertArrayEquals(sample, readAll(loaded));
    }

    @Test
    void crashBeforeTailRewriteDoesNotDuplicateBytes() throws Exception {
        HotFile file = create();
        file.append(0, sample, 10_000, null, NOW);
        Path tail = root.resolve("app1/digibib/2026-10-23-" + fingerprint + ".tail");
        byte[] oldTail = Files.readAllBytes(tail);
        file.append(10_000, Arrays.copyOfRange(sample, 10_000, 40_000), 30_000, null, NOW);
        file.release();
        // a crash after the frame was written but before the tail was replaced leaves the old tail behind
        Files.write(tail, oldTail);
        HotFile loaded = reload();
        assertTrue(loaded.frameCount() > 0);
        assertTrue(loaded.length() <= 40_000);
        long length = loaded.length();
        loaded.append(length, Arrays.copyOfRange(sample, (int) length, sample.length), sample.length - (int) length,
            null, NOW);
        assertArrayEquals(sample, readAll(loaded));
    }

    @Test
    void agedBytesBecomeAFrame() throws Exception {
        HotFile file = create();
        file.append(0, sample, 5000, null, NOW);
        assertEquals(0, file.frameCount());
        file.flushAged(NOW.plusSeconds(30));
        assertEquals(0, file.frameCount());
        file.flushAged(NOW.plusSeconds(61));
        assertEquals(1, file.frameCount());
        assertArrayEquals(Arrays.copyOf(sample, 5000), readAll(file));
    }
}
