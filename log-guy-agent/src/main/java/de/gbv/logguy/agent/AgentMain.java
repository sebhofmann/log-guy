package de.gbv.logguy.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Command line of the agent.
 *
 * <pre>
 * log-guy-agent [run] [--config agent.yml]
 * log-guy-agent backfill --app digibib [--config agent.yml] [--server URL] [--token-file F] [--host H]
 *     [--parallel N] files...
 * </pre>
 */
public final class AgentMain {

    private static final Logger LOGGER = LogManager.getLogger();

    private AgentMain() {
    }

    /**
     * Runs the agent or a command.
     *
     * @param args the arguments
     * @throws Exception on errors
     */
    public static void main(String[] args) throws Exception {
        String command = args.length > 0 && !args[0].startsWith("--") ? args[0] : "run";
        Map<String, String> options = new HashMap<>();
        List<String> files = new ArrayList<>();
        for (int i = command.equals(args.length > 0 ? args[0] : "") ? 1 : 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                options.put(args[i].substring(2), args[++i]);
            } else {
                files.add(args[i]);
            }
        }
        Path configFile = Path.of(options.getOrDefault("config", AgentConfig.DEFAULT_FILE.toString()));
        Map<String, Object> overrides = new HashMap<>();
        for (String key : List.of("server", "token-file", "host")) {
            if (options.containsKey(key)) {
                overrides.put(key, options.get(key));
            }
        }
        if (command.equals("backfill")) {
            overrides.putIfAbsent("sources", List.of());
        }
        AgentConfig config = AgentConfig.load(configFile, overrides);
        switch (command) {
            case "run" -> run(config);
            case "backfill" -> System.exit(backfill(config, require(options, "app"),
                Integer.parseInt(options.getOrDefault("parallel", "1")), files));
            default -> {
                System.err.println("unknown command " + command + ", expected run or backfill");
                System.exit(2);
            }
        }
    }

    private static String require(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing option --" + key);
        }
        return value;
    }

    private static void run(AgentConfig config) throws Exception {
        try (Agent agent = new Agent(config, new IngestClient(config))) {
            Thread main = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                agent.stop();
                main.interrupt();
            }));
            agent.run();
        } catch (InterruptedException e) {
            LOGGER.info("agent stopped");
        }
    }

    /**
     * Sends existing files completely.
     *
     * @param config the configuration
     * @param app the application
     * @param parallel number of files sent at the same time
     * @param files the files
     * @return exit code, 1 if a file failed
     * @throws InterruptedException if interrupted
     */
    static int backfill(AgentConfig config, String app, int parallel, List<String> files)
        throws InterruptedException {
        FileSender sender = new FileSender(new IngestClient(config), config.chunkSize());
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        try (ExecutorService executor = Executors.newFixedThreadPool(parallel)) {
            List<Future<Void>> futures = new ArrayList<>();
            for (String file : files) {
                futures.add(executor.submit((java.util.concurrent.Callable<Void>) () -> {
                    Path path = Path.of(file);
                    for (int attempt = 1;; attempt++) {
                        try {
                            sender.send(path, app);
                            LOGGER.info("{}/{} {}", done.incrementAndGet(), files.size(), path);
                            return null;
                        } catch (IngestClient.RejectedException e) {
                            LOGGER.error("{} refused: {}", path, e.getMessage());
                            failed.incrementAndGet();
                            return null;
                        } catch (java.io.IOException e) {
                            if (attempt >= 5) {
                                LOGGER.error("{} failed: {}", path, e.getMessage());
                                failed.incrementAndGet();
                                return null;
                            }
                            LOGGER.warn("{} attempt {} failed: {}", path, attempt, e.getMessage());
                            Thread.sleep(config.retryDelay());
                        }
                    }
                }));
            }
            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    LOGGER.error("backfill failed", e.getCause());
                    failed.incrementAndGet();
                }
            }
        }
        LOGGER.info("backfill done: {} files, {} failed", files.size(), failed.get());
        return failed.get() == 0 ? 0 : 1;
    }
}
