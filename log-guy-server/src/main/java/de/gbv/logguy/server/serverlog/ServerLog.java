package de.gbv.logguy.server.serverlog;

import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy;
import org.apache.logging.log4j.core.appender.rolling.TimeBasedTriggeringPolicy;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.layout.PatternLayout;

import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.ServerLogProperties;

/**
 * The own log of log-guy: a daily rotated file in {@code logs/} and a ring buffer in memory. Both appenders are added
 * to the root logger of the running Log4j configuration, so the console output stays as configured.
 */
public class ServerLog implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger();

    static final String FILE_NAME = "log-guy.log";

    static final String ROTATED_PREFIX = "log-guy-";

    private final ServerLogProperties settings;

    private final Path dir;

    private final Clock clock;

    private final RingBufferAppender ring;

    private final RollingFileAppender file;

    private final PropertyChangeListener reattach;

    /**
     * Creates the log file and the ring buffer and attaches both to the root logger.
     *
     * @param properties configuration
     * @param clock clock for the cleanup of old files
     * @throws IOException if the log directory cannot be created
     */
    public ServerLog(LogGuyProperties properties, Clock clock) throws IOException {
        this.settings = properties.serverLog();
        this.dir = properties.logDir();
        this.clock = clock;
        Files.createDirectories(dir);
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration config = context.getConfiguration();
        // Unique names, appenders with a name already attached to a logger are ignored by Log4j.
        String suffix = "-" + Integer.toHexString(System.identityHashCode(this));
        this.ring = new RingBufferAppender("log-guy-ring" + suffix, settings.ringSize());
        this.file = RollingFileAppender.newBuilder()
            .setName("log-guy-file" + suffix)
            .withFileName(dir.resolve(FILE_NAME).toString())
            .withFilePattern(dir.resolve(ROTATED_PREFIX + "%d{yyyy-MM-dd}.log.gz").toString())
            .withPolicy(TimeBasedTriggeringPolicy.newBuilder().withInterval(1).build())
            .withStrategy(DefaultRolloverStrategy.newBuilder().withConfig(config).build())
            .setLayout(PatternLayout.newBuilder().withPattern(settings.pattern()).withConfiguration(config).build())
            .setConfiguration(config)
            .build();
        ring.start();
        file.start();
        this.reattach = event -> {
            if (LoggerContext.PROPERTY_CONFIG.equals(event.getPropertyName())
                && event.getNewValue() instanceof Configuration changed) {
                attach(changed);
            }
        };
        context.addPropertyChangeListener(reattach);
        attach(config);
        context.updateLoggers();
        LOGGER.info("logging to {}", dir.resolve(FILE_NAME));
    }

    // A reconfiguration of Log4j, e.g. by a second Spring context, would drop the appenders otherwise.
    // updateLoggers() fires the same event again, so attaching twice must do nothing.
    private void attach(Configuration config) {
        if (config.getRootLogger().getAppenders().containsKey(ring.getName())) {
            return;
        }
        config.addAppender(ring);
        config.addAppender(file);
        config.getRootLogger().addAppender(ring, null, null);
        config.getRootLogger().addAppender(file, null, null);
    }

    /**
     * Entries of the ring buffer newer than {@code after}, oldest first.
     *
     * @param after sequence number of the last known entry, {@code -1} for all
     * @param minLevel least severe level, {@code null} for all
     * @param logger part of the logger name, {@code null} for all
     * @param limit largest number of entries
     * @return the entries
     */
    public List<LogEntry> entries(long after, Level minLevel, String logger, int limit) {
        return ring.entries(after, minLevel, logger, limit);
    }

    /**
     * Sequence number of the next entry.
     *
     * @return the number
     */
    public long nextSeq() {
        return ring.nextSeq();
    }

    /**
     * Deletes rotated log files older than {@code keep-days}.
     *
     * @return number of deleted files
     * @throws IOException if the directory cannot be read
     */
    public int deleteOldFiles() throws IOException {
        Instant limit = clock.instant().minus(Duration.ofDays(settings.keepDays()));
        int deleted = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : files.filter(p -> p.getFileName().toString().startsWith(ROTATED_PREFIX)).toList()) {
                if (Files.getLastModifiedTime(path).toInstant().isBefore(limit)) {
                    Files.delete(path);
                    deleted++;
                }
            }
        }
        return deleted;
    }

    @Override
    public void close() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        context.removePropertyChangeListener(reattach);
        Configuration config = context.getConfiguration();
        config.getRootLogger().removeAppender(ring.getName());
        config.getRootLogger().removeAppender(file.getName());
        context.updateLoggers();
        ring.stop();
        file.stop();
    }
}
