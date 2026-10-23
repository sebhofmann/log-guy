package de.gbv.logguy.server.archive.search;

import java.time.Instant;
import java.util.List;

/**
 * What an archive search looks for.
 *
 * @param apps applications, empty for all the user may search
 * @param hosts hosts, empty for all
 * @param from start of the time range, {@code null} for {@code default-range} before {@code to}
 * @param to end of the time range, {@code null} for now
 * @param query the words or the regular expression
 * @param regex whether {@code query} is a regular expression in RE2 syntax, otherwise it is searched literally
 * @param ignoreCase whether case is ignored
 * @param context lines before and after each hit, {@code null} for {@code context-lines}
 */
public record ArchiveSearchRequest(List<String> apps, List<String> hosts, Instant from, Instant to, String query,
    boolean regex, boolean ignoreCase, Integer context) {

    /**
     * Normalizes missing lists.
     */
    public ArchiveSearchRequest {
        apps = apps == null ? List.of() : List.copyOf(apps);
        hosts = hosts == null ? List.of() : List.copyOf(hosts);
    }
}
