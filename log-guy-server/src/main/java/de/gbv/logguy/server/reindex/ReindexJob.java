package de.gbv.logguy.server.reindex;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One reindex job: files of {@code hot/} handed to the indexer and a time range loaded from the archive. The counters
 * are updated by the worker thread and read by status requests.
 */
public final class ReindexJob {

    private final String id;

    private final String user;

    private final Plan plan;

    private final Instant started;

    private final Instant expires;

    private final List<String> failed = new ArrayList<>();

    private State state = State.RUNNING;

    private Instant finished;

    private String message;

    private int archiveFilesDone;

    private long bytesDone;

    private long events;

    private volatile boolean cancelled;

    ReindexJob(String id, String user, Plan plan, Instant started, Instant expires) {
        this.id = id;
        this.user = user;
        this.plan = plan;
        this.started = started;
        this.expires = expires;
    }

    /**
     * The id of the job.
     *
     * @return the id
     */
    public String id() {
        return id;
    }

    /**
     * What the job does.
     *
     * @return the plan
     */
    public Plan plan() {
        return plan;
    }

    /**
     * When the events loaded from the archive are deleted again.
     *
     * @return the expiry
     */
    public Instant expires() {
        return expires;
    }

    boolean cancelled() {
        return cancelled;
    }

    /**
     * Whether the job still runs.
     *
     * @return true while loading from the archive
     */
    public synchronized boolean running() {
        return state == State.RUNNING;
    }

    /**
     * Asks the job to stop after the current batch.
     *
     * @return false if it had finished already
     */
    public synchronized boolean cancel() {
        if (state != State.RUNNING) {
            return false;
        }
        cancelled = true;
        return true;
    }

    synchronized void progress(long bytes, long newEvents) {
        bytesDone += bytes;
        events += newEvents;
    }

    synchronized void fileDone() {
        archiveFilesDone++;
    }

    synchronized void fail(String file) {
        failed.add(file);
    }

    synchronized void finish(State end, String text, Instant now) {
        state = end;
        message = text;
        finished = now;
    }

    /**
     * A snapshot of the job.
     *
     * @return the status
     */
    public synchronized Status status() {
        return new Status(id, user, state, message, plan.app(), plan.from(), plan.to(), started, finished,
            plan.hotFiles().size(), plan.archiveFrom(), plan.archiveTo(), plan.archiveFiles().size(),
            archiveFilesDone, plan.archiveBytes(), bytesDone, events, plan.archiveFiles().isEmpty() ? null : expires,
            List.copyOf(failed));
    }

    /**
     * States of a job.
     */
    public enum State {
        /** Loading from the archive. */
        RUNNING,
        /** Everything loaded, files of {@code hot/} are handed to the indexer. */
        DONE,
        /** Stopped on request. */
        CANCELLED,
        /** Stopped by an error. */
        FAILED
    }

    /**
     * What a job does, computed before it starts.
     *
     * @param app the application
     * @param from start of the requested time range
     * @param to end of the requested time range
     * @param hotFiles fingerprints of the files in {@code hot/} indexed again
     * @param archiveFrom start of the range loaded from the archive, {@code null} if nothing is loaded
     * @param archiveTo end of the range loaded from the archive, events from there on are in the index already
     * @param archiveFiles fingerprints of the archive files to read
     * @param archiveBytes filtered bytes of those files
     */
    public record Plan(String app, Instant from, Instant to, List<String> hotFiles, Instant archiveFrom,
        Instant archiveTo, List<String> archiveFiles, long archiveBytes) {

        /**
         * Copies the lists.
         */
        public Plan {
            hotFiles = List.copyOf(hotFiles);
            archiveFiles = List.copyOf(archiveFiles);
        }

        /**
         * Whether files of {@code hot/} are indexed again.
         *
         * @return true if there are any
         */
        public boolean indexesHot() {
            return !hotFiles.isEmpty();
        }

        /**
         * Whether events are loaded from the archive.
         *
         * @return true if a range of the archive is loaded
         */
        public boolean loadsArchive() {
            return archiveFrom != null;
        }
    }

    /**
     * The status of a job, as the API returns it.
     *
     * @param id the job
     * @param user who started it
     * @param state the state
     * @param message why it stopped, for the user
     * @param app the application
     * @param from start of the requested range
     * @param to end of the requested range
     * @param started when it started
     * @param finished when it finished, {@code null} while it runs
     * @param hotFiles files of {@code hot/} handed to the indexer
     * @param archiveFrom start of the range loaded from the archive
     * @param archiveTo end of the range loaded from the archive
     * @param archiveFiles archive files to read
     * @param archiveFilesDone archive files read
     * @param bytes filtered bytes of the archive files
     * @param bytesDone filtered bytes read
     * @param events events loaded from the archive
     * @param expires when the loaded events are deleted again, {@code null} if nothing is loaded
     * @param failed archive files that could not be read
     */
    public record Status(String id, String user, State state, String message, String app, Instant from, Instant to,
        Instant started, Instant finished, int hotFiles, Instant archiveFrom, Instant archiveTo, int archiveFiles,
        int archiveFilesDone, long bytes, long bytesDone, long events, Instant expires, List<String> failed) {
    }
}
