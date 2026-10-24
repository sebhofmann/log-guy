package de.gbv.logguy.server.index;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Progress of the indexer for one file, stored in {@code state/index/<fingerprint>}.
 *
 * @param offset start of the first event not yet indexed for good
 * @param lastTs time of the last indexed event, used for events without time
 * @param done true if the file is closed and completely indexed
 * @param matched alert rules that already matched the provisional events from {@code offset} on, by offset, so
 *     that a restart does not raise their alerts again; null in state files written before
 */
public record IndexState(long offset, Instant lastTs, boolean done, Map<Long, Set<String>> matched) {

    /**
     * Progress without provisional matches.
     *
     * @param offset start of the first event not yet indexed for good
     * @param lastTs time of the last indexed event
     * @param done true if the file is completely indexed
     */
    public IndexState(long offset, Instant lastTs, boolean done) {
        this(offset, lastTs, done, Map.of());
    }

    /**
     * Normalizes a missing map.
     */
    public IndexState {
        matched = matched == null ? Map.of() : Map.copyOf(matched);
    }
}
