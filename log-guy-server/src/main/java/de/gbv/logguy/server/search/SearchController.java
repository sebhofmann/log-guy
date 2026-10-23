package de.gbv.logguy.server.search;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.solr.client.solrj.SolrServerException;
import org.springframework.context.annotation.Profile;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.gbv.logguy.server.search.ContextService.ContextView;
import de.gbv.logguy.server.search.SearchService.FacetValue;
import de.gbv.logguy.server.search.SearchService.FieldsView;
import de.gbv.logguy.server.search.SearchService.Histogram;
import de.gbv.logguy.server.search.SearchService.SearchResult;
import de.gbv.logguy.server.security.CurrentUser;

/**
 * The search API. Common parameters: {@code apps} (comma separated, empty for all allowed), {@code q} (Solr syntax
 * with declared field names), {@code from} and {@code to} (ISO-8601 instants) and {@code f.<field>} (filter values,
 * repeatable).
 */
@RestController
@RequestMapping("/api/v1")
@Profile("!cli")
public class SearchController {

    private static final String FILTER_PREFIX = "f.";

    private final SearchService search;

    private final ContextService context;

    private final CurrentUser currentUser;

    /**
     * Creates the controller.
     *
     * @param search the search
     * @param context the context reader
     * @param currentUser resolves the user
     */
    public SearchController(SearchService search, ContextService context, CurrentUser currentUser) {
        this.search = search;
        this.context = context;
        this.currentUser = currentUser;
    }

    /**
     * Searches events.
     *
     * @param params all parameters
     * @param cursor cursor of the previous page
     * @param rows events per page
     * @param order {@code desc} (newest first, default) or {@code asc}
     * @return one page of events
     * @throws IOException on network errors
     * @throws SolrServerException on errors reported by Solr
     */
    @GetMapping("/search")
    public SearchResult search(@RequestParam MultiValueMap<String, String> params,
        @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer rows,
        @RequestParam(defaultValue = "desc") String order) throws IOException, SolrServerException {
        return search.search(scope(params), cursor, rows, "asc".equalsIgnoreCase(order));
    }

    /**
     * Counts events per interval.
     *
     * @param params all parameters
     * @param split field to split the counts by, e.g. {@code level}
     * @return the histogram
     * @throws IOException on network errors
     * @throws SolrServerException on errors reported by Solr
     */
    @GetMapping("/histogram")
    public Histogram histogram(@RequestParam MultiValueMap<String, String> params,
        @RequestParam(required = false) String split) throws IOException, SolrServerException {
        return search.histogram(scope(params), split);
    }

    /**
     * The most frequent values of fields.
     *
     * @param params all parameters
     * @param fields fields, comma separated, empty for all with {@code facet = true}
     * @param limit values per field
     * @return values per field
     * @throws IOException on network errors
     * @throws SolrServerException on errors reported by Solr
     */
    @GetMapping("/facets")
    public Map<String, List<FacetValue>> facets(@RequestParam MultiValueMap<String, String> params,
        @RequestParam(required = false) List<String> fields, @RequestParam(required = false) Integer limit)
        throws IOException, SolrServerException {
        return search.facets(scope(params), fields == null ? List.of() : fields, limit);
    }

    /**
     * The allowed applications and the fields of the requested ones.
     *
     * @param apps applications, comma separated, empty for all allowed
     * @return applications and fields
     */
    @GetMapping("/fields")
    public FieldsView fields(@RequestParam(required = false) List<String> apps) {
        return search.describeFields(currentUser.get(), apps == null ? List.of() : apps);
    }

    /**
     * The original lines around an event.
     *
     * @param id the event id
     * @param before lines before the event
     * @param after lines from the event on
     * @return the lines
     * @throws IOException if reading fails
     */
    @GetMapping("/events/{id}/context")
    public ContextView context(@PathVariable String id, @RequestParam(required = false) Integer before,
        @RequestParam(required = false) Integer after) throws IOException {
        return context.context(currentUser.get(), id, before, after);
    }

    private SearchScope scope(MultiValueMap<String, String> params) {
        Map<String, List<String>> filters = new LinkedHashMap<>();
        params.forEach((key, values) -> {
            if (key.startsWith(FILTER_PREFIX) && key.length() > FILTER_PREFIX.length()) {
                filters.put(key.substring(FILTER_PREFIX.length()),
                    values.stream().filter(v -> v != null && !v.isEmpty()).toList());
            }
        });
        SearchRequest request = new SearchRequest(list(params.get("apps")), params.getFirst("q"),
            instant(params.getFirst("from")), instant(params.getFirst("to")), filters);
        return search.scope(currentUser.get(), request);
    }

    private static List<String> list(List<String> values) {
        List<String> list = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                for (String part : value.split(",")) {
                    if (!part.isBlank()) {
                        list.add(part.trim());
                    }
                }
            }
        }
        return list;
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new SearchException("Ungültige Zeitangabe „" + value + "“, erwartet wird z.B. 2026-10-23T08:00:00Z.");
        }
    }
}
