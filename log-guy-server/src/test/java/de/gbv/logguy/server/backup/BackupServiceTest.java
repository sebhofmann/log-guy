package de.gbv.logguy.server.backup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.gbv.logguy.server.archive.ArchiveFile;
import de.gbv.logguy.server.archive.ArchiveStore;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.BackupProperties;
import de.gbv.logguy.server.hot.FileMeta;
import tools.jackson.databind.json.JsonMapper;

class BackupServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Instant T0 = Instant.parse("2026-10-24T02:00:00Z");

    @TempDir
    Path dir;

    private Path dataDir;

    private Path configDir;

    private Path backupDir;

    private LogGuyProperties properties;

    private ArchiveStore archive;

    private BackupService backup;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = dir.resolve("data");
        configDir = dir.resolve("etc");
        backupDir = dir.resolve("backup");
        Files.createDirectories(configDir.resolve("scripts/parser"));
        Files.writeString(configDir.resolve("application.yml"), "log-guy:\n");
        Files.writeString(configDir.resolve("scripts/parser/digibib.js"), "// parser\n");
        Files.writeString(configDir.resolve("scripts/parser/.digibib.js.tmp"), "half");
        Files.createDirectories(dataDir.resolve("state/index"));
        Files.writeString(dataDir.resolve("state/index/f00"), "{}");
        Files.writeString(dataDir.resolve("state/control.token"), "secret");
        Files.writeString(dataDir.resolve("state/alerts.ndjson"), "{\"a\":1}\n{\"a\":");
        Files.createDirectories(dataDir.resolve("audit"));
        Files.writeString(dataDir.resolve("audit/2026-10-24.ndjson"), "{\"action\":\"login\"}\n");
        properties = properties(dataDir, new BackupProperties(backupDir, "-", configDir));
        archive = new ArchiveStore(properties, JSON);
        backup = new BackupService(properties, archive, JSON, Clock.systemUTC());
    }

    private static LogGuyProperties properties(Path dataDir, BackupProperties backup) {
        return new LogGuyProperties(dataDir, ZoneId.of("Europe/Berlin"), Map.of(), List.of(), List.of(), null, null,
            null, null, null, null, null, null, null, null, null, null, null, backup);
    }

    private ArchiveFile archived(String fingerprint, int frames) throws IOException {
        ArchiveFile file = archive.getOrCreate(new FileMeta(fingerprint, "app1", "digibib", LocalDate.of(2026, 10,
            24), List.of("mycore-app.log"), T0, T0, 0, false));
        append(file, frames);
        return file;
    }

    private static void append(ArchiveFile file, int frames) throws IOException {
        for (int i = 0; i < frames; i++) {
            long src = file.meta().sourceLength();
            byte[] data = ("line " + src + " of " + file.fingerprint() + "\n").repeat(100)
                .getBytes(StandardCharsets.UTF_8);
            file.append(data, src, src + data.length, 100, 0, T0, T0, T0, List.of(), List.of("mycore-app.log"), 3);
        }
    }

    private ArchiveStore restored() throws IOException {
        return new ArchiveStore(properties(backupDir, null), JSON);
    }

    @Test
    void copiesArchiveStateAuditAndConfiguration() throws IOException {
        ArchiveFile file = archived("f00", 3);
        BackupService.Report report = backup.run();

        assertTrue(report.ok(), report.errors().toString());
        assertEquals(1, report.archiveCopied());
        assertEquals(file.compressedSize(), report.archiveBytes());
        ArchiveFile copy = restored().find("f00").orElseThrow();
        assertEquals(file.meta(), copy.meta());
        assertEquals(file.frames(), copy.frames());
        assertArrayEquals(file.read(0, 100_000), copy.read(0, 100_000));

        assertEquals("{}", Files.readString(backupDir.resolve("state/index/f00")));
        assertEquals("{\"a\":1}\n", Files.readString(backupDir.resolve("state/alerts.ndjson")),
            "an incomplete last line is left out");
        assertFalse(Files.exists(backupDir.resolve("state/control.token")));
        assertTrue(Files.exists(backupDir.resolve("audit/2026-10-24.ndjson")));
        assertEquals("// parser\n", Files.readString(backupDir.resolve("config/scripts/parser/digibib.js")));
        assertFalse(Files.exists(backupDir.resolve("config/scripts/parser/.digibib.js.tmp")));
        assertTrue(Files.exists(backupDir.resolve(BackupService.REPORT_FILE)));
    }

    @Test
    void secondRunCopiesOnlyWhatChanged() throws IOException {
        ArchiveFile file = archived("f00", 2);
        archived("f01", 1);
        backup.run();
        long before = file.compressedSize();

        BackupService.Report unchanged = backup.run();
        assertEquals(0, unchanged.archiveCopied());
        assertEquals(0, unchanged.otherCopied());

        append(file, 2);
        file.close(List.of("mycore-app.log", "mycore-app-1.log.gz"));
        Files.delete(configDir.resolve("scripts/parser/digibib.js"));
        BackupService.Report changed = backup.run();
        assertEquals(1, changed.archiveCopied());
        assertEquals(file.compressedSize() - before, changed.archiveBytes(), "only the new frames are copied");
        assertEquals(1, changed.otherDeleted());
        assertFalse(Files.exists(backupDir.resolve("config/scripts/parser/digibib.js")));

        ArchiveFile copy = restored().find("f00").orElseThrow();
        assertTrue(copy.meta().closed());
        assertEquals(file.frames(), copy.frames());
        assertArrayEquals(file.read(0, 100_000), copy.read(0, 100_000));
    }

    @Test
    void replacedFileIsCopiedAgain() throws IOException {
        ArchiveFile file = archived("f00", 3);
        backup.run();

        ArchiveFile staged = archive.stage(new FileMeta("f00", "app1", "digibib",
            LocalDate.of(2026, 10, 24), List.of("mycore-app.log"), T0, T0, 0, false));
        append(staged, 1);
        ArchiveFile replaced = archive.replace(staged);
        BackupService.Report report = backup.run();

        assertEquals(1, report.archiveCopied());
        assertEquals(replaced.compressedSize(), report.archiveBytes());
        ArchiveFile copy = restored().find("f00").orElseThrow();
        assertEquals(replaced.frames(), copy.frames());
        assertArrayEquals(replaced.read(0, 100_000), copy.read(0, 100_000));
    }

    @Test
    void refusesWithoutDirectoryAndInsideTheDataDirectory() {
        BackupService off = new BackupService(properties(dataDir, null), archive, JSON, Clock.systemUTC());
        assertFalse(off.enabled());
        assertThrows(IllegalStateException.class, off::run);
        assertThrows(IllegalArgumentException.class, () -> new BackupService(
            properties(dataDir, new BackupProperties(dataDir.resolve("backup"), "-", configDir)), archive, JSON,
            Clock.systemUTC()));
    }
}
