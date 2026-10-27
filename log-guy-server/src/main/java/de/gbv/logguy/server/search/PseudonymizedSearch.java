package de.gbv.logguy.server.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import de.gbv.logguy.server.pseudonym.PseudonymQueries;
import de.gbv.logguy.server.pseudonym.PseudonymService;
import de.gbv.logguy.server.pseudonym.Pseudonyms;
import de.gbv.logguy.server.search.ContextService.ContextView;
import de.gbv.logguy.server.search.SearchService.EventView;
import de.gbv.logguy.server.search.SearchService.FacetValue;
import de.gbv.logguy.server.search.SearchService.Histogram;
import de.gbv.logguy.server.search.SearchService.SearchResult;
import de.gbv.logguy.server.security.LogGuyUser;

/**
 * Runs search results through the pseudonymization, for the MCP tools and for users who may read some applications
 * only pseudonymized. Pseudonyms in queries and filters are translated back to the real values first.
 */
@Component
@Profile("!cli")
public class PseudonymizedSearch {

    /** Replaces an event, a line or a value whose pseudonymization failed. */
    public static final String HELD_BACK = "[zurückgehalten]";

    private static final Set<String> EVENT_KEYS = Set.of("app", "host", "ts", "message", "parse_error");

    private final SearchService search;

    private final PseudonymService pseudonyms;

    /**
     * Creates the component.
     *
     * @param search the search
     * @param pseudonyms the pseudonymization
     */
    public PseudonymizedSearch(SearchService search, PseudonymService pseudonyms) {
        this.search = search;
        this.pseudonyms = pseudonyms;
    }

    /**
     * The user a search runs as: the user itself if it may read all requested applications in plain text,
     * otherwise its pseudonymized view, which also reads the applications with
     * {@link de.gbv.logguy.server.config.LogGuyProperties.Right#READ_PSEUDONYMIZED}.
     *
     * @param user the user of the request
     * @param requested requested applications, empty for all allowed ones
     * @return the user to search with, {@link LogGuyUser#pseudonymized()} tells whether to pseudonymize
     * @throws SearchException if an application is unknown or not allowed
     */
    public LogGuyUser searchUser(LogGuyUser user, Collection<String> requested) {
        if (user.pseudonymized()) {
            return user;
        }
        LogGuyUser view = user.asPseudonymized();
        Set<String> apps = search.apps(view, requested);
        return apps.stream().allMatch(user::readsPlain) ? user : view;
    }

    /**
     * Checks a search of a pseudonymized user and translates the pseudonyms of the session in query and filters.
     *
     * @param user the pseudonymized user
     * @param session the pseudonyms of the session
     * @param request the request with pseudonyms
     * @return the checked search with real values
     * @throws SearchException if a protected field is searched with something other than a known pseudonym
     */
    public SearchScope scope(LogGuyUser user, Pseudonyms session, SearchRequest request) {
        Set<String> apps = search.apps(user, request.apps());
        Set<String> protectedFields = new TreeSet<>();
        apps.forEach(app -> protectedFields.addAll(pseudonyms.protectedFields(app)));
        String query = PseudonymQueries.query(request.query(), protectedFields, session);
        Map<String, List<String>> filters = PseudonymQueries.filters(request.filters() == null ? Map.of()
            : request.filters(), protectedFields, session);
        return search.scope(user, new SearchRequest(List.copyOf(apps), query, request.from(), request.to(),
            filters));
    }

    /**
     * Pseudonymizes an event of the search.
     *
     * @param session the pseudonyms
     * @param event the event
     * @return the result of the scripts with {@code app}, {@code host}, {@code ts}, {@code message}, optionally
     *     {@code parse_error} and the fields, empty if the event is held back
     */
    public Optional<Map<String, Object>> event(Pseudonyms session, EventView event) {
        Map<String, Object> input = new LinkedHashMap<>(event.fields());
        input.put("app", event.app());
        input.put("host", event.host());
        input.put("ts", event.ts() == null ? null : event.ts().toString());
        input.put("message", event.message());
        if (event.parseError() != null) {
            input.put("parse_error", event.parseError());
        }
        return pseudonyms.apply(session, input);
    }

    /**
     * The fields of a pseudonymized event without the fixed keys.
     *
     * @param result the result of {@link #event(Pseudonyms, EventView)}
     * @return the fields, sorted
     */
    public static Map<String, Object> fields(Map<String, Object> result) {
        Map<String, Object> fields = new TreeMap<>();
        result.forEach((key, value) -> {
            if (!EVENT_KEYS.contains(key)) {
                fields.put(key, value);
            }
        });
        return fields;
    }

    /**
     * Pseudonymizes a page of the search, held back events keep only id, time, application and host.
     *
     * @param session the pseudonyms
     * @param result the page
     * @return the pseudonymized page
     */
    public SearchResult result(Pseudonyms session, SearchResult result) {
        List<EventView> events = new ArrayList<>(result.events().size());
        for (EventView event : result.events()) {
            events.add(event(session, event).map(out -> new EventView(event.id(), event.ts(), event.app(),
                event.host(), String.valueOf(out.get("message")), string(out.get("parse_error")), fields(out)))
                .orElseGet(() -> new EventView(event.id(), event.ts(), event.app(), event.host(), HELD_BACK, null,
                    Map.of())));
        }
        return new SearchResult(events, result.total(), result.cursor(), result.done());
    }

    /**
     * Pseudonymizes the values the histogram is split by. Values with the same pseudonym are added up.
     *
     * @param session the pseudonyms
     * @param apps the applications of the search
     * @param histogram the histogram
     * @param finish applied to every pseudonymized value, e.g. to cut it
     * @return the pseudonymized histogram
     */
    public Histogram histogram(Pseudonyms session, Collection<String> apps, Histogram histogram,
        UnaryOperator<String> finish) {
        if (histogram.series() == null || histogram.split() == null) {
            return histogram;
        }
        Map<String, List<Long>> series = new LinkedHashMap<>();
        histogram.series().forEach((key, counts) -> series.merge(finish.apply(value(session, apps,
            histogram.split(), key)), counts, PseudonymizedSearch::sum));
        return new Histogram(histogram.interval(), histogram.times(), histogram.total(), histogram.split(), series);
    }

    /**
     * Pseudonymizes facet values. Values with the same pseudonym are added up.
     *
     * @param session the pseudonyms
     * @param apps the applications of the search
     * @param facets values per field
     * @param finish applied to every pseudonymized value, e.g. to cut it
     * @return the pseudonymized values per field
     */
    public Map<String, List<FacetValue>> facets(Pseudonyms session, Collection<String> apps,
        Map<String, List<FacetValue>> facets, UnaryOperator<String> finish) {
        Map<String, List<FacetValue>> out = new LinkedHashMap<>();
        facets.forEach((field, values) -> {
            Map<String, Long> merged = new LinkedHashMap<>();
            for (FacetValue value : values) {
                merged.merge(finish.apply(value(session, apps, field, value.value())), value.count(), Long::sum);
            }
            out.put(field, merged.entrySet().stream().map(e -> new FacetValue(e.getKey(), e.getValue())).toList());
        });
        return out;
    }

    /**
     * Pseudonymizes a single value of a field, e.g. a facet value, with the scripts of the applications.
     *
     * @param session the pseudonyms
     * @param apps the applications the value comes from
     * @param field the field
     * @param value the value
     * @return the pseudonymized value, {@link #HELD_BACK} if it is held back
     */
    public String value(Pseudonyms session, Collection<String> apps, String field, String value) {
        if (value == null || "app".equals(field) || "host".equals(field)) {
            return value;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("app", "");
        event.put("message", "");
        event.put(field, value);
        return pseudonyms.applyFor(session, apps, event).map(result -> String.valueOf(result.get(field)))
            .orElse(HELD_BACK);
    }

    /**
     * The lines around an event for a pseudonymized reader. They are already in the form of the archive, filtered and
     * pseudonymized with the tokens of the file, and like lines of the archive they are not pseudonymized again.
     *
     * @param view the lines
     * @param finish applied to every line, e.g. to cut it
     * @return the lines
     */
    public ContextView context(ContextView view, UnaryOperator<String> finish) {
        return new ContextView(view.id(), view.app(), view.host(), view.before().stream().map(finish).toList(),
            view.lines().stream().map(finish).toList(), view.start(), view.end());
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<Long> sum(List<Long> a, List<Long> b) {
        List<Long> result = new ArrayList<>(a.size());
        for (int i = 0; i < a.size(); i++) {
            result.add(a.get(i) + b.get(i));
        }
        return result;
    }

}
