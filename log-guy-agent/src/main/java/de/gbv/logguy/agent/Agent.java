package de.gbv.logguy.agent;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The running agent: follows the live files and scans the rotated files of all sources.
 * <p>
 * New bytes are sent every {@code send-interval} or as soon as a full chunk is there. The {@link WatchService}
 * (inotify on Linux) wakes the agent early, the regular check by size and inode also works where file events do not
 * arrive, e.g. on NFS.
 */
public class Agent implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger();

    private final AgentConfig config;

    private final List<LiveTailer> tailers = new ArrayList<>();

    private final List<RotatedScanner> scanners = new ArrayList<>();

    private final Set<Path> denied = new HashSet<>();

    private final WatchService watcher;

    private volatile boolean running = true;

    /**
     * Creates the agent.
     *
     * @param config the configuration
     * @param client the client of the stream API
     * @throws IOException if the file watcher cannot be created
     */
    public Agent(AgentConfig config, IngestClient client) throws IOException {
        this.config = config;
        this.watcher = FileSystems.getDefault().newWatchService();
        FileSender sender = new FileSender(client, config.chunkSize());
        Set<Path> watched = new HashSet<>();
        for (AgentConfig.Source source : config.sources()) {
            if (source.live() != null) {
                tailers.add(new LiveTailer(client, source.app(), source.live(), config.chunkSize(),
                    config.rotationGrace()));
                Path dir = source.live().toAbsolutePath().getParent();
                if (watched.add(dir)) {
                    try {
                        dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY);
                    } catch (IOException e) {
                        LOGGER.warn("cannot watch {}, checking every {} only: {}", dir, config.sendInterval(),
                            e.getMessage());
                    }
                }
            }
            if (source.rotated() != null) {
                scanners.add(new RotatedScanner(sender, source.app(), source.rotated(), config.rotationGrace(),
                    config.rotatedScanInterval(), config.rotatedRetryMax()));
            }
        }
    }

    /**
     * Runs until {@link #stop()} is called or the thread is interrupted.
     *
     * @throws InterruptedException if interrupted
     */
    public void run() throws InterruptedException {
        LOGGER.info("agent for host {} sends {} live and {} rotated sources to {}", config.host(), tailers.size(),
            scanners.size(), config.server());
        Instant nextScan = Instant.now();
        Instant nextPoll = Instant.now();
        while (running) {
            Instant now = Instant.now();
            if (!now.isBefore(nextScan)) {
                scanners.forEach(this::scan);
                nextScan = Instant.now().plus(config.rotatedScanInterval());
            }
            if (!now.isBefore(nextPoll)) {
                pollAll(false);
                nextPoll = Instant.now().plus(config.sendInterval());
            }
            long wait = Math.max(1, nextPoll.toEpochMilli() - Instant.now().toEpochMilli());
            WatchKey key = watcher.poll(wait, TimeUnit.MILLISECONDS);
            if (key != null) {
                key.pollEvents();
                key.reset();
                pollAll(true);
            }
        }
    }

    private void scan(RotatedScanner scanner) {
        try {
            scanner.scan();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    private void pollAll(boolean fullChunksOnly) throws InterruptedException {
        for (LiveTailer tailer : tailers) {
            if (fullChunksOnly && !tailer.hasFullChunk()) {
                continue;
            }
            try {
                tailer.poll();
                denied.remove(tailer.path());
            } catch (IngestClient.RejectedException e) {
                LOGGER.error("{} refused: {}", tailer.path(), e.getMessage());
            } catch (AccessDeniedException e) {
                // no retry delay, the other sources go on
                if (denied.add(tailer.path())) {
                    LOGGER.error("cannot read {}, check the rights of the agent", tailer.path());
                }
            } catch (IOException e) {
                LOGGER.warn("cannot send {}: {}", tailer.path(), e.getMessage());
                Thread.sleep(config.retryDelay());
            }
        }
    }

    /**
     * Stops the loop of {@link #run()}.
     */
    public void stop() {
        running = false;
    }

    @Override
    public void close() throws IOException {
        for (LiveTailer tailer : tailers) {
            tailer.close();
        }
        watcher.close();
    }
}
