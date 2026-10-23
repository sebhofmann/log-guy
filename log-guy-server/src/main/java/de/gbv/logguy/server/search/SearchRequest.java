package de.gbv.logguy.server.search;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A search as the client sent it, before checking rights and fields.
 *
 * @param apps requested applications, empty for all allowed ones
 * @param query query in Solr syntax with the declared field names
 * @param from start of the range, {@code null} for {@code to} minus the default range
 * @param to end of the range, {@code null} for now
 * @param filters values per declared field name, the values of one field are combined with OR
 */
public record SearchRequest(List<String> apps, String query, Instant from, Instant to,
    Map<String, List<String>> filters) {

    /**
     * Normalizes missing collections.
     */
    public SearchRequest {
        apps = apps == null ? List.of() : List.copyOf(apps);
        filters = filters == null ? Map.of() : Map.copyOf(filters);
    }
}
