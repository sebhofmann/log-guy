package de.gbv.logguy.server.archive.search;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import de.gbv.logguy.server.pseudonym.Pseudonyms;

/**
 * A running or finished archive search. Hits arrive in the order the frames are searched, not sorted by time.
 */
public final class ArchiveSearchJob {

    private final String id;

    private final Owner owner;

    private final ArchiveSearchRequest request;

    private final List<String> apps;

    private final boolean pseudonymized;

    private final Pseudonyms session;

    private final Instant started;

    private final Instant deadline;

    private final int maxHits;

    private final List<ArchiveHit> hits = new ArrayList<>();

    private final List<String> failed = new ArrayList<>();

    private final AtomicInteger filesDone = new AtomicInteger();

    private final AtomicInteger framesDone = new AtomicInteger();

    private final AtomicLong bytesDone = new AtomicLong();

    private int files;

    private int frames;

    private long bytes;

    private State state = State.RUNNING;

    private String message;

    private Instant finished;

    ArchiveSearchJob(String id, Owner owner, ArchiveSearchRequest request, List<String> apps, boolean pseudonymized,
        Pseudonyms session, Instant started, Instant deadline, int maxHits) {
        this.id = id;
        this.owner = owner;
        this.request = request;
        this.apps = List.copyOf(apps);
        this.pseudonymized = pseudonymized;
        this.session = session;
        this.started = started;
        this.deadline = deadline;
        this.maxHits = maxHits;
    }

    /**
     * The id of the job.
     *
     * @return a random id
     */
    public String id() {
        return id;
    }

    /**
     * Who started the job, only they may read it.
     *
     * @return the owner
     */
    public Owner owner() {
        return owner;
    }

    boolean pseudonymized() {
        return pseudonymized;
    }

    Pseudonyms session() {
        return session;
    }

    Instant deadline() {
        return deadline;
    }

    synchronized void planned(int files, int frames, long bytes) {
        this.files = files;
        this.frames = frames;
        this.bytes = bytes;
    }

    /**
     * Whether the job still searches.
     *
     * @return true until it is done, cancelled or stopped by a limit
     */
    public synchronized boolean running() {
        return state == State.RUNNING;
    }

    /**
     * Stops the job. Frames already being searched finish, their hits are dropped.
     *
     * @param now current time
     * @return true if the job was still running
     */
    public boolean cancel(Instant now) {
        return finish(State.CANCELLED, "abgebrochen", now);
    }

    synchronized boolean finish(State end, String text, Instant now) {
        if (state != State.RUNNING) {
            return false;
        }
        state = end;
        message = text;
        finished = now;
        notifyAll();
        return true;
    }

    synchronized void addHits(List<ArchiveHit> found, Instant now) {
        if (state != State.RUNNING || found.isEmpty()) {
            return;
        }
        int room = maxHits - hits.size();
        hits.addAll(found.size() > room ? found.subList(0, room) : found);
        notifyAll();
        if (hits.size() >= maxHits) {
            finish(State.LIMIT, "Trefferlimit von " + maxHits + " erreicht, der Zeitraum ist nicht vollständig "
                + "durchsucht", now);
        }
    }

    synchronized void addFailure(String file) {
        failed.add(file);
    }

    void frameDone(long frameBytes) {
        framesDone.incrementAndGet();
        bytesDone.addAndGet(frameBytes);
    }

    void fileDone() {
        filesDone.incrementAndGet();
    }

    /**
     * Hits found so far.
     *
     * @param from index of the first hit
     * @param max most hits returned
     * @return the hits
     */
    public synchronized List<ArchiveHit> hits(int from, int max) {
        int start = Math.clamp(from, 0, hits.size());
        return List.copyOf(hits.subList(start, Math.min(hits.size(), start + Math.max(0, max))));
    }

    /**
     * Waits until there are more hits than {@code count} or the job has finished.
     *
     * @param count hits already known
     * @param millis longest wait
     * @throws InterruptedException if the thread is interrupted
     */
    public synchronized void await(int count, long millis) throws InterruptedException {
        long end = System.nanoTime() + millis * 1_000_000;
        while (state == State.RUNNING && hits.size() <= count) {
            long left = (end - System.nanoTime()) / 1_000_000;
            if (left <= 0) {
                return;
            }
            wait(left);
        }
    }

    /**
     * Progress and result of the job.
     *
     * @return the status
     */
    public synchronized Status status() {
        return new Status(id, state, message, apps, request.hosts(), request.from(), request.to(), request.query(),
            request.regex(), request.ignoreCase(), request.context(), pseudonymized, started, finished, files,
            filesDone.get(), frames, framesDone.get(), bytes, bytesDone.get(), hits.size(), List.copyOf(failed));
    }

    synchronized Instant finished() {
        return finished;
    }

    /**
     * Who started a job. A job of the GUI or the API belongs to the user and the token, a job of the MCP endpoint to
     * its session, because its hits use the pseudonyms of the session.
     *
     * @param user name of the user
     * @param token name of the token, {@code null} for the GUI
     * @param channel {@code web} or the pseudonyms of the MCP session
     */
    public record Owner(String user, String token, Object channel) {
    }

    /**
     * States of a job.
     */
    public enum State {
        /** Frames are being searched. */
        RUNNING,
        /** All frames are searched. */
        DONE,
        /** Cancelled by the user. */
        CANCELLED,
        /** Stopped at {@code max-hits}. */
        LIMIT,
        /** Stopped at {@code max-runtime}. */
        TIMEOUT
    }

    /**
     * Progress and result.
     *
     * @param id the job
     * @param state the state
     * @param message why the job stopped, in German, {@code null} while it runs or if it is done
     * @param apps the searched applications
     * @param hosts the requested hosts, empty for all
     * @param from start of the range
     * @param to end of the range
     * @param query the words or the expression
     * @param regex whether the query is a regular expression
     * @param ignoreCase whether case is ignored
     * @param context lines of context per hit
     * @param pseudonymized whether the hits are pseudonymized
     * @param started start of the job
     * @param finished end of the job, {@code null} while it runs
     * @param files files to search
     * @param filesDone files searched
     * @param frames frames to search
     * @param framesDone frames searched
     * @param bytes uncompressed bytes to search
     * @param bytesDone uncompressed bytes searched
     * @param hits hits so far
     * @param failed files that could not be read
     */
    public record Status(String id, State state, String message, List<String> apps, List<String> hosts,
        Instant from, Instant to, String query, boolean regex, boolean ignoreCase, Integer context,
        boolean pseudonymized, Instant started, Instant finished, int files, int filesDone, int frames,
        int framesDone, long bytes, long bytesDone, int hits, List<String> failed) {
    }
}
