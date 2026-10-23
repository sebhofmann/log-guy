package de.gbv.logguy.server.search;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.gbv.logguy.server.script.FieldDefinition;

/**
 * A checked search: the applications the user may see, their fields, the time range and the query for Solr.
 *
 * @param apps the applications searched, all allowed for the user, may be empty
 * @param fields the declared fields of these applications by name, a name can have several types
 * @param from start of the range, inclusive
 * @param to end of the range, exclusive
 * @param query the translated main query
 * @param filters the translated field filters, each tagged with {@code f_<name>}
 */
public record SearchScope(Set<String> apps, Map<String, List<FieldDefinition>> fields, Instant from, Instant to,
    String query, List<String> filters) {

    /**
     * Prefix of the tag of a field filter, used to exclude the filter of a field from its own facet.
     */
    public static final String FILTER_TAG = "f_";
}
