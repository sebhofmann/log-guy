package de.gbv.logguy.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentCheckTest {

    @TempDir
    Path dir;

    private final FakeServer server = new FakeServer();

    AgentCheckTest() throws IOException {
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private String check(AgentConfig config, int expected) throws Exception {
        Path status = dir.resolve("status");
        Files.writeString(status, "Name:\tjava\nUid:\t501\t501\t501\t501\nGid:\t501\t501\t501\t501\nGroups:\t4 8983\n");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code = new AgentCheck(config, new IngestClient(config), new PrintStream(bytes, true,
            StandardCharsets.UTF_8), status).run();
        String report = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(expected, code, report);
        return report;
    }

    @Test
    void everythingReadable() throws Exception {
        Path live = Files.writeString(dir.resolve("mycore.log"), "x\n");
        Files.createDirectories(dir.resolve("2026-10"));
        Files.writeString(dir.resolve("2026-10/mycore-1.log.gz"), "x");
        String report = check(server.config(List.of(new AgentConfig.Source("digibib", live,
            dir + "/*/mycore-*.log.gz"))), 0);
        assertTrue(report.contains("UID 501, GID 501, Gruppen 4,8983"), report);
        assertTrue(report.contains("Token agent-app1 für Host app1 und digibib"), report);
        assertTrue(report.contains("1 rotierte Dateien lesbar"), report);
        assertTrue(report.contains("Ergebnis: in Ordnung"), report);
    }

    @Test
    void wrongApplicationAndUnreadableFiles() throws Exception {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")), "root reads everything");
        Path live = Files.writeString(dir.resolve("mycore.log"), "x\n");
        Files.setPosixFilePermissions(live, PosixFilePermissions.fromString("-w-------"));
        Path month = Files.createDirectories(dir.resolve("2026-10"));
        Files.setPosixFilePermissions(month, PosixFilePermissions.fromString("---------"));
        try {
            String report = check(server.config(List.of(new AgentConfig.Source("leopoldina", live,
                dir + "/*/mycore-*.log.gz"))), 1);
            assertTrue(report.contains("FEHLER  Das Token darf nicht für leopoldina senden"), report);
            assertTrue(report.contains("FEHLER  Live-Datei nicht lesbar: " + live + " (UID "), report);
            assertTrue(report.contains("-w-------)"), report);
            assertTrue(report.contains("Verzeichnis nicht lesbar, rotierte Dateien darin fehlen: " + month),
                report);
            assertTrue(report.contains("Ergebnis: 3 Fehler"), report);
        } finally {
            Files.setPosixFilePermissions(month, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void wrongToken() throws Exception {
        AgentConfig good = server.config(List.of());
        AgentConfig config = new AgentConfig(good.server(), "wrong", "app1", good.chunkSize(), good.sendInterval(),
            good.rotatedScanInterval(), good.connectTimeout(), good.requestTimeout(), good.retryDelay(),
            good.rotationGrace(),
            good.rotatedRetryMax(), List.of());
        String report = check(config, 1);
        assertTrue(report.contains("FEHLER  Der Server lehnt das Token ab: server answered 401"), report);
    }
}
