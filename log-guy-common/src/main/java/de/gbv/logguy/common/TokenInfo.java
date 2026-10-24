package de.gbv.logguy.common;

import java.util.List;

/**
 * What an ingest token allows. Returned by {@code GET /api/v1/streams}, the agent checks its configuration against
 * it.
 *
 * @param name name of the token
 * @param host the only host the token may send for
 * @param apps the applications the token may send for, sorted
 */
public record TokenInfo(String name, String host, List<String> apps) {
}
