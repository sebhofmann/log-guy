package de.gbv.logguy.server.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import tools.jackson.databind.json.JsonMapper;

/**
 * Appends who did what to {@code audit/<date>.ndjson}, one JSON object per line: script changes, reloads and every
 * MCP call. A failed write is logged and does not stop the action, the server log then has the entry.
 */
public class AuditLog {

    private static final Logger LOGGER = LogManager.getLogger();

    private final Path dir;

    private final ZoneId zone;

    private final Clock clock;

    private final JsonMapper json;

    /**
     * Creates the log.
     *
     * @param dir the directory, usually {@code <data-dir>/audit}
     * @param zone zone for the date in the file name
     * @param clock clock for the time of an entry
     * @param json mapper for the lines
     */
    public AuditLog(Path dir, ZoneId zone, Clock clock, JsonMapper json) {
        this.dir = dir;
        this.zone = zone;
        this.clock = clock;
        this.json = json;
    }

    /**
     * Writes an entry.
     *
     * @param actor who did it
     * @param action what, e.g. {@code script.activate}
     * @param target on what, e.g. {@code parser/digibib}
     * @param details further values, may be empty
     */
    public void log(Actor actor, String action, String target, Map<String, ?> details) {
        Instant now = clock.instant();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", now.toString());
        entry.put("user", actor.user());
        entry.put("token", actor.token());
        entry.put("via", actor.via().name().toLowerCase(Locale.ROOT));
        entry.put("action", action);
        entry.put("target", target);
        if (details != null && !details.isEmpty()) {
            entry.put("details", details);
        }
        String line = json.writeValueAsString(entry) + "\n";
        LOGGER.info("audit {} {} {}", actor.label(), action, target);
        synchronized (this) {
            try {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(LocalDate.ofInstant(now, zone) + ".ndjson"), line,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                LOGGER.error("cannot write audit entry {}", line.strip(), e);
            }
        }
    }
}
