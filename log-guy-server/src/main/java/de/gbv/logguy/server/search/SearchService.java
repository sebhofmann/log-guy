package de.gbv.logguy.server.search;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.json.BucketBasedJsonFacet;
import org.apache.solr.client.solrj.response.json.BucketJsonFacet;
import org.apache.solr.client.solrj.response.json.NestableJsonFacet;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.params.CursorMarkParams;

import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.Right;
import de.gbv.logguy.server.config.LogGuyProperties.SearchProperties;
import de.gbv.logguy.server.script.FieldDefinition;
import de.gbv.logguy.server.script.FieldType;
import de.gbv.logguy.server.script.ParserRegistry;
import de.gbv.logguy.server.script.ParserScript;
import de.gbv.logguy.server.security.LogGuyUser;
import de.gbv.logguy.server.solr.SolrService;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fast search over the index window in Solr: events, histogram, facets and the fields of the applications. Every
 * request is limited to the applications the user may read.
 */
public class SearchService {

    /** Fields every event has, with the name used in queries and in Solr. */
    public static final Map<String, FieldType> FIXED_FIELDS = fixedFields();

    /** Fixed fields that can be used for facets. */
    public static final Set<String> FIXED_FACETS = Set.of("app", "host", "parse_error", "parser_version");

    /** Candidate intervals of the histogram, the smallest one that stays below the maximum of bars is taken. */
    private static final List<Duration> INTERVALS = List.of(Duration.ofSeconds(1), Duration.ofSeconds(2),
        Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(1),
        Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(15),
        Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(3), Duration.ofHours(6),
        Duration.ofHours(12), Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(7));

    private static final String OTHER = "andere";

    private final LogGuyProperties properties;

    private final ParserRegistry parsers;

    private final SolrService solr;

    private final Clock clock;

    private final JsonMapper json;

    /**
     * Creates the service.
     *
     * @param properties configuration
     * @param parsers the parser scripts with the declared fields
     * @param solr connection to Solr
     * @param clock clock for the default range
     * @param json mapper for the JSON facet requests
     */
    public SearchService(LogGuyProperties properties, ParserRegistry parsers, SolrService solr, Clock clock,
        JsonMapper json) {
        this.properties = properties;
        this.parsers = parsers;
        this.solr = solr;
        this.clock = clock;
        this.json = json;
    }

    private SearchProperties settings() {
        return properties.search();
    }

    /**
     * Checks a request against the rights of the user and translates it for Solr.
     *
     * @param user the user
     * @param request the request
     * @return the checked search
     * @throws SearchException if the request is invalid
     */
    public SearchScope scope(LogGuyUser user, SearchRequest request) {
        Set<String> apps = apps(user, request.apps());
        Map<String, List<FieldDefinition>> fields = fields(apps);
        Instant to = request.to() != null ? request.to() : clock.instant();
        Instant from = request.from() != null ? request.from() : to.minus(settings().defaultRange());
        if (!from.isBefore(to)) {
            throw new SearchException("Der Beginn des Zeitraums muss vor dem Ende liegen.");
        }
        String query = QueryTranslator.translate(request.query(), name -> solrField(fields, name));
        List<String> filters = new ArrayList<>();
        for (Map.Entry<String, List<String>> filter : new TreeMap<>(request.filters()).entrySet()) {
            if (filter.getValue().isEmpty()) {
                continue;
            }
            String field = solrField(fields, filter.getKey());
            if (field == null) {
                throw new SearchException("Unbekanntes Feld „" + filter.getKey() + "“ im Filter.");
            }
            String values = filter.getValue().stream()
                .map(value -> "\"" + ClientUtils.escapeQueryChars(value) + "\"")
                .collect(Collectors.joining(" OR "));
            filters.add("{!tag=" + SearchScope.FILTER_TAG + filter.getKey() + "}" + field + ":(" + values + ")");
        }
        return new SearchScope(apps, fields, from, to, query, filters);
    }

    /**
     * The applications of a request the user may read.
     *
     * @param user the user
     * @param requested requested applications, empty for all allowed ones
     * @return the applications, sorted
     * @throws SearchException if an application is unknown or not allowed
     */
    public Set<String> apps(LogGuyUser user, Collection<String> requested) {
        Set<String> allowed = user.readableApps(properties.apps().keySet());
        if (requested.isEmpty()) {
            return allowed;
        }
        Set<String> apps = new TreeSet<>();
        for (String app : requested) {
            if (!allowed.contains(app)) {
                throw new SearchException("Die Anwendung „" + app + "“ gibt es nicht oder sie ist nicht freigegeben.");
            }
            apps.add(app);
        }
        return apps;
    }

    /**
     * The declared fields of applications, plus the fixed fields.
     *
     * @param apps the applications
     * @return the fields by name, sorted, with all types the name has in these applications
     */
    public Map<String, List<FieldDefinition>> fields(Collection<String> apps) {
        parsers.ensureLoaded();
        Map<String, List<FieldDefinition>> fields = new TreeMap<>();
        FIXED_FIELDS.forEach((name, type) -> fields.put(name,
            List.of(new FieldDefinition(name, type, FIXED_FACETS.contains(name)))));
        for (String app : apps) {
            ParserScript script = parsers.forApp(app).orElse(null);
            if (script == null) {
                continue;
            }
            for (FieldDefinition field : script.fields().values()) {
                List<FieldDefinition> known = fields.computeIfAbsent(field.name(), k -> new ArrayList<>());
                if (known.stream().noneMatch(f -> f.type() == field.type())) {
                    known.add(field);
                } else if (field.facet() && known.stream().noneMatch(FieldDefinition::facet)) {
                    known.replaceAll(f -> f.type() == field.type() ? field : f);
                }
            }
        }
        return fields;
    }

    /**
     * Describes the fields of the applications a user may read, also those it may only read pseudonymized.
     *
     * @param user the user
     * @param requested requested applications, empty for all allowed ones
     * @return allowed applications and fields
     */
    public FieldsView describeFields(LogGuyUser user, Collection<String> requested) {
        LogGuyUser view = user.asPseudonymized();
        Set<String> allowed = view.readableApps(properties.apps().keySet());
        Set<String> apps = apps(view, requested);
        parsers.ensureLoaded();
        Map<String, FieldInfo> infos = new TreeMap<>();
        for (String app : apps) {
            parsers.forApp(app).ifPresent(script -> {
                for (FieldDefinition field : script.fields().values()) {
                    String key = field.name() + "\u0000" + field.type();
                    FieldInfo info = infos.computeIfAbsent(key, k -> new FieldInfo(field.name(),
                        field.type().name().toLowerCase(java.util.Locale.ROOT), field.facet(), new TreeSet<>()));
                    info.apps().add(app);
                }
            });
        }
        List<FieldInfo> fields = new ArrayList<>();
        FIXED_FIELDS.forEach((name, type) -> fields.add(new FieldInfo(name, type.name().toLowerCase(
            java.util.Locale.ROOT), FIXED_FACETS.contains(name), new TreeSet<>(apps))));
        fields.addAll(infos.values());
        return new FieldsView(List.copyOf(allowed), allowed.stream().filter(app -> !user.readsPlain(app)).toList(),
            fields);
    }

    /**
     * Searches events, sorted by time and id, one page per call.
     *
     * @param scope the checked search
     * @param cursor the cursor of the previous page, {@code null} for the first page
     * @param rows events per page, {@code null} for the default
     * @param ascending oldest first instead of newest first
     * @return the page
     * @throws IOException on network errors
     * @throws SolrServerException on errors reported by Solr
     */
    public SearchResult search(SearchScope scope, String cursor, Integer rows, boolean ascending)
        throws IOException, SolrServerException {
        String mark = cursor == null || cursor.isBlank() ? CursorMarkParams.CURSOR_MARK_START : cursor;
        if (scope.apps().isEmpty()) {
            return new SearchResult(List.of(), 0, mark, true);
        }
        SolrQuery query = query(scope);
        int size = rows == null ? settings().defaultRows() : Math.clamp(rows, 1, settings().maxRows());
        query.setRows(size);
        SolrQuery.ORDER order = ascending ? SolrQuery.ORDER.asc : SolrQuery.ORDER.desc;
        query.addSort("ts", order);
        query.addSort("id", order);
        query.set(CursorMarkParams.CURSOR_MARK_PARAM, mark);
        Map<String, String> solrToName = new HashMap<>();
        scope.fields().forEach((name, definitions) -> {
            if (!FIXED_FIELDS.containsKey(name)) {
                definitions.forEach(definition -> solrToName.put(definition.solrField(), name));
            }
        });
        List<String> fl = new ArrayList<>(List.of("id", "ts", "app", "host", "message", "parse_error"));
        fl.addAll(new TreeSet<>(solrToName.keySet()));
        query.setFields(fl.toArray(String[]::new));
        QueryResponse response = solr.client().query(query);
        List<EventView> events = new ArrayList<>();
        for (SolrDocument doc : response.getResults()) {
            Map<String, Object> fields = new TreeMap<>();
            for (String field : doc.getFieldNames()) {
                String name = solrToName.get(field);
                if (name != null) {
                    Object value = doc.getFieldValue(field);
                    fields.put(name, value instanceof Date date ? date.toInstant() : value);
                }
            }
            events.add(new EventView((String) doc.getFieldValue("id"), ((Date) doc.getFieldValue("ts")).toInstant(),
                (String) doc.getFieldValue("app"), (String) doc.getFieldValue("host"),
                (String) doc.getFieldValue("message"), (String) doc.getFieldValue("parse_error"), fields));
        }
        String next = response.getNextCursorMark();
        return new SearchResult(events, response.getResults().getNumFound(), next,
            next == null || next.equals(mark) || events.size() < size);
    }

    /**
     * Counts events per interval. The interval is chosen so the histogram has at most {@code max-bars} bars.
     *
     * @param scope the checked search
     * @param split declared field whose most frequent values get their own series, {@code null} for none
     * @return the histogram
     * @throws IOException on network errors
     * @throws SolrServerException on errors reported by Solr
     */
    public Histogram histogram(SearchScope scope, String split) throws IOException, SolrServerException {
        Duration interval = interval(Duration.between(scope.from(), scope.to()));
        Instant start = align(scope.from(), interval, properties.zone());
        int bars = (int) Math.ceilDiv(Duration.between(start, scope.to()).toMillis(), interval.toMillis());
        Instant end = start.plus(interval.multipliedBy(bars));
        List<Long> times = new ArrayList<>(bars);
        for (int i = 0; i < bars; i++) {
            times.add(start.plus(interval.multipliedBy(i)).getEpochSecond());
        }
        String splitField = split == null || split.isBlank() ? null : facetField(scope, split);
        long[] total = new long[bars];
        Map<String, long[]> series = new LinkedHashMap<>();
        if (!scope.apps().isEmpty()) {
            Map<String, Object> range = new LinkedHashMap<>();
            range.put("type", "range");
            range.put("field", "ts");
            range.put("start", start.toString());
            range.put("end", end.toString());
            range.put("gap", gap(interval));
            if (splitField != null) {
                range.put("facet", Map.of("s",
                    Map.of("type", "terms", "field", splitField, "limit", settings().splitLimit())));
            }
            SolrQuery query = query(scope);
            query.setRows(0);
            query.set("TZ", properties.zone().getId());
            query.set("json.facet", json.writeValueAsString(Map.of("h", range)));
            NestableJsonFacet facets = solr.client().query(query).getJsonFacetingResponse();
            BucketBasedJsonFacet histogram = facets == null ? null : facets.getBucketBasedFacets("h");
            if (histogram != null) {
                for (BucketJsonFacet bucket : histogram.getBuckets()) {
                    int i = (int) (Duration.between(start, ((Date) bucket.getVal()).toInstant()).toMillis()
                        / interval.toMillis());
                    if (i < 0 || i >= bars) {
                        continue;
                    }
                    total[i] = bucket.getCount();
                    BucketBasedJsonFacet values = bucket.getBucketBasedFacets("s");
                    long rest = bucket.getCount();
                    if (values != null) {
                        for (BucketJsonFacet value : values.getBuckets()) {
                            series.computeIfAbsent(String.valueOf(value.getVal()), k -> new long[bars])[i] = value
                                .getCount();
                            rest -= value.getCount();
                        }
                        if (rest > 0) {
                            series.computeIfAbsent(OTHER, k -> new long[bars])[i] = rest;
                        }
                    }
                }
            }
        }
        Map<String, List<Long>> seriesLists = new LinkedHashMap<>();
        series.forEach((name, counts) -> seriesLists.put(name, list(counts)));
        return new Histogram(interval.toSeconds(), times, list(total), split, seriesLists);
    }

    /**
     * The most frequent values of fields.
     *
     * @param scope the checked search
     * @param names declared fields, empty for all fields with {@code facet = true}
     * @param limit values per field, {@code null} for the default
     * @return the values per field, most frequent first; a field's own filter is ignored for its values
     * @throws IOException on network errors
     * @throws SolrServerException on errors reported by Solr
     */
    public Map<String, List<FacetValue>> facets(SearchScope scope, List<String> names, Integer limit)
        throws IOException, SolrServerException {
        List<String> fields = new ArrayList<>(names);
        if (fields.isEmpty()) {
            scope.fields().forEach((name, definitions) -> {
                if (definitions.stream().anyMatch(FieldDefinition::facet)) {
                    fields.add(name);
                }
            });
        }
        int size = limit == null ? settings().facetLimit() : Math.clamp(limit, 1, settings().maxFacetLimit());
        Map<String, Object> request = new LinkedHashMap<>();
        for (String name : fields) {
            request.put(name, Map.of("type", "terms", "field", facetField(scope, name), "limit", size, "domain",
                Map.of("excludeTags", SearchScope.FILTER_TAG + name)));
        }
        Map<String, List<FacetValue>> result = new LinkedHashMap<>();
        fields.forEach(name -> result.put(name, new ArrayList<>()));
        if (scope.apps().isEmpty() || fields.isEmpty()) {
            return result;
        }
        SolrQuery query = query(scope);
        query.setRows(0);
        query.set("json.facet", json.writeValueAsString(request));
        NestableJsonFacet facets = solr.client().query(query).getJsonFacetingResponse();
        for (String name : fields) {
            BucketBasedJsonFacet facet = facets == null ? null : facets.getBucketBasedFacets(name);
            if (facet != null) {
                for (BucketJsonFacet bucket : facet.getBuckets()) {
                    Object value = bucket.getVal();
                    result.get(name).add(new FacetValue(value instanceof Date date ? date.toInstant().toString()
                        : String.valueOf(value), bucket.getCount()));
                }
            }
        }
        return result;
    }

    private SolrQuery query(SearchScope scope) {
        SolrQuery query = new SolrQuery(scope.query());
        query.set("q.op", "AND");
        query.addFilterQuery("{!terms f=app}" + String.join(",", scope.apps()));
        query.addFilterQuery("ts:[" + scope.from() + " TO " + scope.to() + "}");
        scope.filters().forEach(query::addFilterQuery);
        return query;
    }

    private static String solrField(Map<String, List<FieldDefinition>> fields, String name) {
        List<FieldDefinition> definitions = fields.get(name);
        if (definitions == null || definitions.isEmpty()) {
            return null;
        }
        if (definitions.size() > 1) {
            throw new SearchException("Das Feld „" + name
                + "“ hat in den gewählten Anwendungen verschiedene Typen. Bitte die Anwendungen einschränken.");
        }
        return definitions.getFirst().solrField();
    }

    private static String facetField(SearchScope scope, String name) {
        String field = solrField(scope.fields(), name);
        if (field == null) {
            throw new SearchException("Unbekanntes Feld „" + name + "“.");
        }
        FieldType type = scope.fields().get(name).getFirst().type();
        if (type == FieldType.TEXT || "message".equals(name)) {
            throw new SearchException("Für das Textfeld „" + name + "“ gibt es keine Häufigkeiten.");
        }
        return field;
    }

    /**
     * Chooses the interval of the histogram.
     *
     * @param range the time range
     * @return the smallest candidate interval that gives at most {@code max-bars} bars
     */
    Duration interval(Duration range) {
        for (Duration interval : INTERVALS) {
            if (range.toMillis() / interval.toMillis() < settings().maxBars()) {
                return interval;
            }
        }
        Duration largest = INTERVALS.getLast();
        return largest.multipliedBy(Math.ceilDiv(range.toMillis(), largest.toMillis() * settings().maxBars()));
    }

    /**
     * Aligns the start of the histogram to a multiple of the interval in local time, so a day starts at midnight.
     *
     * @param from start of the range
     * @param interval the interval
     * @param zone the time zone
     * @return the aligned start, not after {@code from}
     */
    static Instant align(Instant from, Duration interval, ZoneId zone) {
        long offset = zone.getRules().getOffset(from).getTotalSeconds() * 1000L;
        long step = interval.toMillis();
        long local = from.toEpochMilli() + offset;
        return Instant.ofEpochMilli(Math.floorDiv(local, step) * step - offset);
    }

    private static String gap(Duration interval) {
        if (interval.toSeconds() % 86_400 == 0) {
            return "+" + interval.toDays() + "DAYS";
        }
        return "+" + interval.toSeconds() + "SECONDS";
    }

    private static List<Long> list(long[] values) {
        List<Long> list = new ArrayList<>(values.length);
        for (long value : values) {
            list.add(value);
        }
        return list;
    }

    private static Map<String, FieldType> fixedFields() {
        Map<String, FieldType> fields = new LinkedHashMap<>();
        fields.put("message", FieldType.TEXT);
        fields.put("app", FieldType.STRING);
        fields.put("host", FieldType.STRING);
        fields.put("file", FieldType.STRING);
        fields.put("ts", FieldType.DATE);
        fields.put("offset", FieldType.LONG);
        fields.put("id", FieldType.STRING);
        fields.put("parse_error", FieldType.STRING);
        fields.put("parser_version", FieldType.STRING);
        return java.util.Collections.unmodifiableMap(fields);
    }

    /**
     * One page of events.
     *
     * @param events the events
     * @param total number of all matching events
     * @param cursor cursor for the next page
     * @param done whether this is the last page
     */
    public record SearchResult(List<EventView> events, long total, String cursor, boolean done) {
    }

    /**
     * An event as returned by the search.
     *
     * @param id {@code <fingerprint>:<offset>}
     * @param ts time of the event
     * @param app the application
     * @param host the host
     * @param message the whole event text
     * @param parseError error of the parser script, {@code null} if it worked
     * @param fields the declared fields with a value
     */
    public record EventView(String id, Instant ts, String app, String host, String message, String parseError,
        Map<String, Object> fields) {
    }

    /**
     * Events per interval.
     *
     * @param interval length of a bar in seconds
     * @param times start of every bar in epoch seconds
     * @param total events per bar
     * @param split the field the series are split by, {@code null} if not split
     * @param series events per bar for the most frequent values of the split field, the rest as {@code andere}
     */
    public record Histogram(long interval, List<Long> times, List<Long> total, String split,
        Map<String, List<Long>> series) {
    }

    /**
     * A value of a field with its frequency.
     *
     * @param value the value
     * @param count number of events
     */
    public record FacetValue(String value, long count) {
    }

    /**
     * A field as described by {@code /api/v1/fields}. Fixed fields belong to all applications.
     *
     * @param name declared name
     * @param type type, lower case
     * @param facet whether the field is meant for facets and filters
     * @param apps applications with this field
     */
    public record FieldInfo(String name, String type, boolean facet, Set<String> apps) {
    }

    /**
     * The allowed applications and the fields of the requested ones.
     *
     * @param apps all applications the user may read, also pseudonymized
     * @param pseudonymized the applications whose events the user only gets pseudonymized
     * @param fields the fields
     */
    public record FieldsView(List<String> apps, List<String> pseudonymized, List<FieldInfo> fields) {
    }
}
