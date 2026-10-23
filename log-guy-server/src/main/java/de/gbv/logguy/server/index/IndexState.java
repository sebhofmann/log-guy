package de.gbv.logguy.server.index;

import java.time.Instant;

/**
 * Progress of the indexer for one file, stored in {@code state/index/<fingerprint>}.
 *
 * @param offset start of the first event not yet indexed for good
 * @param lastTs time of the last indexed event, used for events without time
 * @param done true if the file is closed and completely indexed
 */
public record IndexState(long offset, Instant lastTs, boolean done) {
}
