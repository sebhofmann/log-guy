package de.gbv.logguy.server.alert;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Sends notifications in the background and tries again after a failure, so a slow or unreachable mail server or
 * webhook does not hold up the alerts. Every attempt runs in its own virtual thread, so a target that hangs does not
 * hold up the other targets either. The scheduler only starts the attempts and the retries.
 */
public class Deliveries implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger();

    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("notify-schedule").factory());

    private final ExecutorService workers =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("notify-", 0).factory());

    private final int attempts;

    private final Duration retryDelay;

    private final AtomicLong sent = new AtomicLong();

    private final AtomicLong failed = new AtomicLong();

    private final AtomicLong pending = new AtomicLong();

    /**
     * Creates the queue.
     *
     * @param attempts how often a delivery is tried, at least once
     * @param retryDelay wait before the second attempt, doubled for every further one
     */
    public Deliveries(int attempts, Duration retryDelay) {
        this.attempts = Math.max(1, attempts);
        this.retryDelay = retryDelay;
    }

    /**
     * One delivery, e.g. a mail.
     */
    @FunctionalInterface
    public interface Delivery {

        /**
         * Sends.
         *
         * @throws Exception if sending failed, it is tried again
         */
        void deliver() throws Exception;
    }

    /**
     * Counters since the start.
     *
     * @param sent deliveries that succeeded
     * @param failed deliveries given up after the last attempt
     * @param pending deliveries waiting for an attempt
     */
    public record Stats(long sent, long failed, long pending) {
    }

    /**
     * Queues a delivery.
     *
     * @param label what is sent to whom, for the log
     * @param delivery the delivery
     */
    public void submit(String label, Delivery delivery) {
        pending.incrementAndGet();
        schedule(label, delivery, 1, Duration.ZERO);
    }

    /**
     * The counters.
     *
     * @return sent, failed and pending deliveries
     */
    public Stats stats() {
        return new Stats(sent.get(), failed.get(), pending.get());
    }

    private void schedule(String label, Delivery delivery, int attempt, Duration delay) {
        try {
            scheduler.schedule(() -> start(label, delivery, attempt), delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            pending.decrementAndGet();
            failed.incrementAndGet();
            LOGGER.error("notification {} dropped, the server is stopping", label);
        }
    }

    private void start(String label, Delivery delivery, int attempt) {
        try {
            workers.execute(() -> run(label, delivery, attempt));
        } catch (RejectedExecutionException e) {
            pending.decrementAndGet();
            failed.incrementAndGet();
            LOGGER.error("notification {} dropped, the server is stopping", label);
        }
    }

    private void run(String label, Delivery delivery, int attempt) {
        try {
            delivery.deliver();
            pending.decrementAndGet();
            sent.incrementAndGet();
            LOGGER.info("notification {} sent", label);
        } catch (Exception e) {
            if (attempt < attempts) {
                Duration delay = retryDelay.multipliedBy(1L << Math.min(attempt - 1, 20));
                LOGGER.warn("notification {} failed in attempt {} of {}, next in {}: {}", label, attempt, attempts,
                    delay, e.toString());
                schedule(label, delivery, attempt + 1, delay);
            } else {
                pending.decrementAndGet();
                failed.incrementAndGet();
                LOGGER.error("notification {} given up after {} attempts", label, attempts, e);
            }
        }
    }

    /**
     * Stops the queue. Deliveries already running finish, waiting retries are dropped.
     */
    @Override
    public void close() {
        scheduler.shutdownNow();
        workers.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS) || !workers.awaitTermination(10, TimeUnit.SECONDS)) {
                LOGGER.warn("notifications still running at shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long dropped = pending.get();
        if (dropped > 0) {
            LOGGER.warn("{} notifications not sent at shutdown", dropped);
        }
    }
}
