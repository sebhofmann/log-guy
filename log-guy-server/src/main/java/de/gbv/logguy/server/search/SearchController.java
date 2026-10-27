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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import de.gbv.logguy.server.search.ContextService.ContextView;
import de.gbv.logguy.server.search.SearchService.FacetValue;
import de.gbv.logguy.server.search.SearchService.FieldsView;
import de.gbv.logguy.server.search.SearchService.Histogram;
import de.gbv.logguy.server.search.SearchService.SearchResult;
import de.gbv.logguy.server.pseudonym.Pseudonyms;
import de.gbv.logguy.server.security.CurrentUser;
import de.gbv.logguy.server.security.LogGuyUser;

/**
 * The search API. Common parameters: {@code apps} (comma separated applications or application groups like
 * {@code @produktiv}, empty for all allowed), {@code q} (Solr syntax
 * with declared field names), {@code from} and {@code to} (ISO-8601 instants) and {@code f.<field>} (filter values,
 * repeatable).
 * <p>
 * If one of the applications may only be read pseudonymized, the whole response is pseudonymized as for the MCP
 * endpoint, with the pseudonyms of the GUI session. Queries and filters then accept these pseudonyms.
 */
@RestController
@RequestMapping("/api/v1")
@Profile("!cli")
public class SearchController {

    private static final String FILTER_PREFIX = "f.";

    private static final String SESSION_PSEUDONYMS = Pseudonyms.class.getName();

    private final SearchService search;

    private final ContextService context;

    private final CurrentUser currentUser;

    private final PseudonymizedSearch pseudonymized;

    private final HttpServletRequest request;

    /**
     * Creates the controller.
     *
     * @param search the search
     * @param context the context reader
     * @param currentUser resolves the user
     * @param pseudonymized pseudonymizes the responses for users who may only read pseudonymized
     * @param request the current request, for the pseudonyms of the session
     */
    public SearchController(SearchService search, ContextService context, CurrentUser currentUser,
        PseudonymizedSearch pseudonymized, HttpServletRequest request) {
        this.search = search;
        this.context = context;
        this.currentUser = currentUser;
        this.pseudonymized = pseudonymized;
        this.request = request;
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
        Search s = scope(params);
        SearchResult result = search.search(s.scope(), cursor, rows, "asc".equalsIgnoreCase(order));
        return s.session() == null ? result : pseudonymized.result(s.session(), result);
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
        Search s = scope(params);
        Histogram histogram = search.histogram(s.scope(), split);
        return s.session() == null ? histogram
            : pseudonymized.histogram(s.session(), s.scope().apps(), histogram, value -> value);
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
        Search s = scope(params);
        Map<String, List<FacetValue>> facets = search.facets(s.scope(), fields == null ? List.of() : fields, limit);
        return s.session() == null ? facets
            : pseudonymized.facets(s.session(), s.scope().apps(), facets, value -> value);
    }

    /**
     * The allowed applications, their groups and the fields of the requested ones.
     *
     * @param apps applications or groups, comma separated, empty for all allowed
     * @return applications and fields
     */
    @GetMapping("/fields")
    public FieldsView fields(@RequestParam(required = false) List<String> apps) {
        return search.describeFields(currentUser.get(), apps == null ? List.of() : apps);
    }

    /**
     * The lines around an event, filtered as in the archive.
     *
     * @param id the event id
     * @param before lines before the event
     * @param after lines from the event on
     * @param original the unfiltered original from {@code hot/}, only for {@code admin} of the application
     * @return the lines
     * @throws IOException if reading fails
     */
    @GetMapping("/events/{id}/context")
    public ContextView context(@PathVariable String id, @RequestParam(required = false) Integer before,
        @RequestParam(required = false) Integer after, @RequestParam(defaultValue = "false") boolean original)
        throws IOException {
        LogGuyUser user = currentUser.get();
        ContextView view = context.context(user.asPseudonymized(), id, before, after, original);
        return user.readsPlain(view.app()) ? view : pseudonymized.context(session(user), view, line -> line);
    }

    private Search scope(MultiValueMap<String, String> params) {
        Map<String, List<String>> filters = new LinkedHashMap<>();
        params.forEach((key, values) -> {
            if (key.startsWith(FILTER_PREFIX) && key.length() > FILTER_PREFIX.length()) {
                filters.put(key.substring(FILTER_PREFIX.length()),
                    values.stream().filter(v -> v != null && !v.isEmpty()).toList());
            }
        });
        SearchRequest searchRequest = new SearchRequest(list(params.get("apps")), params.getFirst("q"),
            instant(params.getFirst("from")), instant(params.getFirst("to")), filters);
        LogGuyUser user = pseudonymized.searchUser(currentUser.get(), searchRequest.apps());
        if (!user.pseudonymized()) {
            return new Search(search.scope(user, searchRequest), null);
        }
        Pseudonyms session = session(user);
        return new Search(pseudonymized.scope(user, session, searchRequest), session);
    }

    // a GUI session keeps its pseudonyms, a request with an API token gets new ones
    private Pseudonyms session(LogGuyUser user) {
        if (user.token() != null) {
            return new Pseudonyms();
        }
        HttpSession session = this.request.getSession(true);
        synchronized (session) {
            if (!(session.getAttribute(SESSION_PSEUDONYMS) instanceof Pseudonyms pseudonyms)) {
                Pseudonyms created = new Pseudonyms();
                session.setAttribute(SESSION_PSEUDONYMS, created);
                return created;
            }
            return pseudonyms;
        }
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

    // the checked search, with the pseudonyms of the session if the response is pseudonymized
    private record Search(SearchScope scope, Pseudonyms session) {
    }
}
