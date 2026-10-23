package de.gbv.logguy.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.common.TestLogs;

class RotatedScannerTest {

    @TempDir
    Path dir;

    private final FakeServer server = new FakeServer();

    private final byte[] sample = TestLogs.mycoreSample();

    RotatedScannerTest() throws IOException {
    }

    @AfterEach
    void stop() {
        server.close();
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

        RotatedScanner scanner = new RotatedScanner(new FileSender(client, config.chunkSize()), "digibib",
            dir + "/*/mycore-app-*.log.gz");
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
    void backfillSendsEachFileOnce() throws Exception {
        AgentConfig config = server.config(List.of());
        Path file = dir.resolve("mycore-app-10-01-2026-1.log.gz");
        gzip(file, sample);
        assertEquals(0, AgentMain.backfill(config, "digibib", 2, List.of(file.toString(), file.toString())));
        assertArrayEquals(sample, server.content(Fingerprint.of(sample)));
        assertEquals(1, server.streams.size());
    }
}
