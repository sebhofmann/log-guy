package de.gbv.logguy.server.archive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.gbv.logguy.server.hot.FileMeta;
import de.gbv.logguy.server.storage.DamagedFileException;
import de.gbv.logguy.server.storage.FailingOpener;
import tools.jackson.databind.json.JsonMapper;

class ArchiveFileTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    private static final FileMeta SOURCE = new FileMeta("abc123", "host1", "app1", LocalDate.of(2026, 10, 23),
        List.of("/logs/app.log"), NOW, NOW, 0, false);

    @TempDir
    Path root;

    private static byte[] chunk(int index) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            text.append("2026-10-23T10:00:00,000 INFO  chunk ").append(index).append(" line ").append(i).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void append(ArchiveFile file, int index) throws IOException {
        long src = (long) index * 10_000;
        file.append(chunk(index), src, src + 10_000, 50, 0, NOW, NOW, NOW, List.of(), List.of("/logs/app.log"), 3);
    }

    private Path path(String suffix) {
        return root.resolve("host1/app1/2026-10-23-abc123" + suffix);
    }

    private ArchiveFile reload() throws IOException {
        return ArchiveFile.load(root, path(ArchiveFile.META_SUFFIX), JSON);
    }

    private static byte[] expected(int chunks) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < chunks; i++) {
            out.write(chunk(i));
        }
        return out.toByteArray();
    }

    private static byte[] readAll(ArchiveFile file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (ArchiveFrame frame : file.frames()) {
            out.write(file.readFrame(frame));
        }
        return out.toByteArray();
    }

    @Test
    void failedIndexWriteIsRolledBackAndTheFrameWrittenAgain() throws Exception {
        FailingOpener opener = FailingOpener.forSuffixes(".idx");
        ArchiveFile file = ArchiveFile.create(root, SOURCE, JSON, opener);
        append(file, 0);
        append(file, 1);
        long idxSize = Files.size(path(".idx"));
        long zstSize = Files.size(path(".log.zst"));
        opener.failAfter(20);
        assertThrows(IOException.class, () -> append(file, 2));
        assertEquals(idxSize, Files.size(path(".idx")));
        assertEquals(zstSize, Files.size(path(".log.zst")));
        assertEquals(List.of(), file.problems());
        opener.heal();
        append(file, 2);
        append(file, 3);
        ArchiveFile loaded = reload();
        assertEquals(List.of(), loaded.problems());
        assertEquals(4, loaded.frames().size());
        assertEquals(file.meta(), loaded.meta());
        assertArrayEquals(expected(4), readAll(loaded));
    }

    @Test
    void failedDataWriteIsRolledBack() throws Exception {
        FailingOpener opener = FailingOpener.forSuffixes(".log.zst");
        ArchiveFile file = ArchiveFile.create(root, SOURCE, JSON, opener);
        append(file, 0);
        long zstSize = Files.size(path(".log.zst"));
        opener.failAfter(5);
        assertThrows(IOException.class, () -> append(file, 1));
        assertEquals(zstSize, Files.size(path(".log.zst")));
        opener.heal();
        append(file, 1);
        ArchiveFile loaded = reload();
        assertEquals(List.of(), loaded.problems());
        assertArrayEquals(expected(2), readAll(loaded));
    }

    @Test
    void damagedIndexIsCutBackBeforeTheNextFrameAndSurvivesARestart() throws Exception {
        FailingOpener opener = FailingOpener.forSuffixes(".idx");
        ArchiveFile file = ArchiveFile.create(root, SOURCE, JSON, opener);
        append(file, 0);
        long idxSize = Files.size(path(".idx"));
        opener.failAfter(20).failTruncate();
        assertThrows(DamagedFileException.class, () -> append(file, 1));
        assertEquals(idxSize + 20, Files.size(path(".idx")));
        assertEquals(1, file.problems().size(), file.problems().toString());
        // still damaged: nothing lands behind the rest of the failed line
        assertThrows(DamagedFileException.class, () -> append(file, 1));
        assertEquals(idxSize + 20, Files.size(path(".idx")));
        // a restart in this state only cuts the incomplete last line
        ArchiveFile restarted = reload();
        assertEquals(List.of(), restarted.problems());
        assertEquals(1, restarted.frames().size());
        opener.heal();
        append(file, 1);
        assertEquals(List.of(), file.problems());
        ArchiveFile loaded = reload();
        assertEquals(List.of(), loaded.problems());
        assertArrayEquals(expected(2), readAll(loaded));
    }

    @Test
    void brokenIndexLineInTheMiddleIsReportedAndLaterFramesAreKept() throws Exception {
        ArchiveFile file = ArchiveFile.create(root, SOURCE, JSON);
        for (int i = 0; i < 4; i++) {
            append(file, i);
        }
        List<String> lines = Files.readAllLines(path(".idx"));
        // what a failed write without rollback left behind: half a line, the next line directly behind it
        Files.writeString(path(".idx"), lines.get(0) + "\n" + lines.get(1).substring(0, 30) + lines.get(1) + "\n"
            + lines.get(2) + "\n" + lines.get(3) + "\n");
        ArchiveFile loaded = reload();
        assertEquals(1, loaded.problems().size(), loaded.problems().toString());
        assertEquals(4, loaded.frames().size());
        assertArrayEquals(expected(4), readAll(loaded));
        assertEquals(lines, Files.readAllLines(path(".idx")));
        assertEquals(List.of(), reload().problems());
    }

    @Test
    void framesBehindALostLineAreDroppedAndReported() throws Exception {
        ArchiveFile file = ArchiveFile.create(root, SOURCE, JSON);
        for (int i = 0; i < 4; i++) {
            append(file, i);
        }
        List<String> lines = Files.readAllLines(path(".idx"));
        Files.writeString(path(".idx"), lines.get(0) + "\ngarbage\n" + lines.get(2) + "\n" + lines.get(3) + "\n");
        ArchiveFile loaded = reload();
        assertEquals(2, loaded.problems().size(), loaded.problems().toString());
        assertEquals(1, loaded.frames().size());
        assertEquals(10_000, loaded.meta().sourceLength());
        assertArrayEquals(expected(1), readAll(loaded));
    }
}
