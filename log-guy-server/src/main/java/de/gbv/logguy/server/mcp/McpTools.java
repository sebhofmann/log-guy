package de.gbv.logguy.server.mcp;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import de.gbv.logguy.server.alert.Alert;
import de.gbv.logguy.server.alert.AlertService;
import de.gbv.logguy.server.alert.AlertTexts;
import de.gbv.logguy.server.alert.RuleTester;
import de.gbv.logguy.server.alert.RuleTester.RuleTestResult;
import de.gbv.logguy.server.archive.search.ArchiveHit;
import de.gbv.logguy.server.archive.search.ArchiveSearch;
import de.gbv.logguy.server.archive.search.ArchiveSearchJob;
import de.gbv.logguy.server.archive.search.ArchiveSearchJob.Owner;
import de.gbv.logguy.server.archive.search.ArchiveSearchRequest;
import de.gbv.logguy.server.audit.Actor;
import de.gbv.logguy.server.audit.Actor.Via;
import de.gbv.logguy.server.audit.AuditLog;
import de.gbv.logguy.server.config.LogGuyProperties.McpProperties;
import de.gbv.logguy.server.pseudonym.PseudonymService;
import de.gbv.logguy.server.archive.LifecycleFilters.LifecycleTestResult;
import de.gbv.logguy.server.pseudonym.PseudonymService.PseudonymTestResult;
import de.gbv.logguy.server.pseudonym.Pseudonyms;
import de.gbv.logguy.server.script.ScriptController;
import de.gbv.logguy.server.script.ScriptException;
import de.gbv.logguy.server.script.ScriptKind;
import de.gbv.logguy.server.script.ScriptRequestException;
import de.gbv.logguy.server.script.ScriptService;
import de.gbv.logguy.server.script.ScriptService.Caller;
import de.gbv.logguy.server.script.ScriptStore.ScriptId;
import de.gbv.logguy.server.script.ScriptTester;
import de.gbv.logguy.server.script.ScriptTester.ParserTestResult;
import de.gbv.logguy.server.script.ScriptTester.TestEvent;
import de.gbv.logguy.server.script.ScriptTester.TestSource;
import de.gbv.logguy.server.search.ContextService;
import de.gbv.logguy.server.search.ContextService.ContextView;
import de.gbv.logguy.server.search.ContextUnavailableException;
import de.gbv.logguy.server.search.PseudonymizedSearch;
import de.gbv.logguy.server.search.SearchException;
import de.gbv.logguy.server.search.SearchRequest;
import de.gbv.logguy.server.search.SearchScope;
import de.gbv.logguy.server.search.SearchService;
import de.gbv.logguy.server.search.SearchService.EventView;
import de.gbv.logguy.server.search.SearchService.FacetValue;
import de.gbv.logguy.server.search.SearchService.Histogram;
import de.gbv.logguy.server.search.SearchService.SearchResult;
import de.gbv.logguy.server.security.CurrentUser.ApiTokenDetails;
import de.gbv.logguy.server.security.LogGuyUser;
import de.gbv.logguy.server.security.UserRegistry;
import de.gbv.logguy.server.serverlog.LogEntry;
import de.gbv.logguy.server.serverlog.ServerLog;
import de.gbv.logguy.server.status.StatusService;
import de.gbv.logguy.server.status.StatusService.StatusView;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tools of the MCP endpoint. Everything a tool returns from the logs runs through the pseudonymization of the
 * session first, pseudonyms in requests are translated back to the real values.
 */
public class McpTools {

    /** Key of the authentication in the MCP transport context. */
    public static final String AUTHENTICATION = "log-guy.authentication";

    private static final Logger LOGGER = LogManager.getLogger();

    private static final String HELD_BACK = PseudonymizedSearch.HELD_BACK;

    private static final Pattern RELATIVE = Pattern.compile("(\\d+)\\s*([smhd])");

    private static final String RANGE_SCHEMA = """
        "apps": {"type": "array", "items": {"type": "string"},
          "description": "Anwendungen oder Anwendungsgruppen wie @produktiv, leer für alle erlaubten"},
        "query": {"type": "string",
          "description": "Solr-Syntax mit den Feldnamen aus list_applications, z. B. level:ERROR AND logger:MCRSolr*.\
         Pseudonyme wie $Nutzer1 werden zurückübersetzt, auf geschützten Feldern sind nur Pseudonyme erlaubt."},
        "filters": {"type": "object", "additionalProperties": {"type": "array", "items": {"type": "string"}},
          "description": "exakte Werte je Feld, z. B. {\\"level\\": [\\"ERROR\\", \\"WARN\\"]}"},
        "from": {"type": "string", "description": "Beginn als ISO-8601-Zeitpunkt, z. B. 2026-10-01T08:00:00Z"},
        "to": {"type": "string", "description": "Ende als ISO-8601-Zeitpunkt, Standard jetzt"},
        "last": {"type": "string", "description": "statt from: Zeitraum bis to, z. B. 30m, 6h, 2d"}
        """;

    private static final String SCRIPT_SCHEMA = """
        "kind": {"type": "string", "enum": ["parser", "rule", "lifecycle", "pseudonym"]},
        "name": {"type": "string", "description": "Name ohne .js"}
        """;

    private final McpProperties settings;

    private final UserRegistry users;

    private final McpSessions sessions;

    private final SearchService search;

    private final ContextService context;

    private final StatusService status;

    private final ServerLog serverLog;

    private final AlertService alerts;

    private final AlertTexts alertTexts;

    private final ScriptService scripts;

    private final ScriptTester tester;

    private final PseudonymService pseudonyms;

    private final PseudonymizedSearch pseudonymizedSearch;

    private final AuditLog audit;

    private final ArchiveSearch archiveSearch;

    private final JsonMapper json;

    private final McpJsonMapper mcpJson;

    /**
     * Creates the tools.
     *
     * @param settings limits of the endpoint
     * @param users finds the user of a token
     * @param sessions pseudonyms per session
     * @param search the search
     * @param context lines around an event
     * @param status the status of the server
     * @param serverLog the own log
     * @param scripts the script store
     * @param tester tests scripts
     * @param pseudonyms the pseudonymization
     * @param pseudonymizedSearch pseudonymizes search results
     * @param audit the audit log
     * @param archiveSearch the archive search
     * @param alerts the alerts
     * @param alertTexts pseudonymizes the alerts
     * @param json serializes the results
     * @param mcpJson parses the input schemas
     */
    public McpTools(McpProperties settings, UserRegistry users, McpSessions sessions, SearchService search,
        ContextService context, StatusService status, ServerLog serverLog, ScriptService scripts, ScriptTester tester,
        PseudonymService pseudonyms, PseudonymizedSearch pseudonymizedSearch, AuditLog audit,
        ArchiveSearch archiveSearch, AlertService alerts, AlertTexts alertTexts, JsonMapper json,
        McpJsonMapper mcpJson) {
        this.settings = settings;
        this.users = users;
        this.sessions = sessions;
        this.search = search;
        this.context = context;
        this.status = status;
        this.serverLog = serverLog;
        this.scripts = scripts;
        this.tester = tester;
        this.pseudonyms = pseudonyms;
        this.pseudonymizedSearch = pseudonymizedSearch;
        this.audit = audit;
        this.archiveSearch = archiveSearch;
        this.alerts = alerts;
        this.alertTexts = alertTexts;
        this.json = json;
        this.mcpJson = mcpJson;
    }

    /**
     * All tools.
     *
     * @return the tool specifications
     */
    public List<SyncToolSpecification> specifications() {
        List<SyncToolSpecification> tools = new ArrayList<>();
        tools.add(tool("list_applications", "Anwendungen, auf die das Token Zugriff hat, mit ihren Feldern",
            "{}", true, (call, args) -> search.describeFields(call.user(), List.of())));
        tools.add(tool("search_logs", "Sucht Events, neueste zuerst. Höchstens " + settings.maxEvents()
            + " Events pro Aufruf, weiter mit cursor.", RANGE_SCHEMA + """
                ,"limit": {"type": "integer", "minimum": 1},
                "order": {"type": "string", "enum": ["desc", "asc"]},
                "cursor": {"type": "string", "description": "cursor aus dem letzten Ergebnis"}
                """, true, this::searchLogs));
        tools.add(tool("get_histogram", "Anzahl Events pro Intervall, z. B. um einen Fehlerbeginn zu finden",
            RANGE_SCHEMA + """
                ,"split": {"type": "string", "description": "Facettenfeld für eigene Reihen, z. B. level"}
                """, true, this::histogram));
        tools.add(tool("get_top_values", "Häufigste Werte von Facettenfeldern im Zeitraum, z. B. logger, exc, host",
            RANGE_SCHEMA + """
                ,"fields": {"type": "array", "items": {"type": "string"}},
                "limit": {"type": "integer", "minimum": 1}
                """, true, this::topValues));
        tools.add(tool("get_context", "Zeilen vor und nach einem Event, gefiltert wie im Archiv", """
            "id": {"type": "string", "description": "id aus search_logs"},
            "before": {"type": "integer", "minimum": 0},
            "after": {"type": "integer", "minimum": 1}
            """, true, this::context));
        tools.add(tool("get_status", "Zustand von log-guy: Anwendungen, Indexer, Agents und Solr", "{}", true,
            this::status));
        tools.add(tool("get_server_log", "Einträge aus dem eigenen Log von log-guy, nur mit Recht admin", """
            "level": {"type": "string", "enum": ["TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"]},
            "logger": {"type": "string", "description": "Teil des Logger-Namens"},
            "after": {"type": "integer", "description": "seq des letzten bekannten Eintrags"},
            "limit": {"type": "integer", "minimum": 1}
            """, true, this::serverLog));
        tools.add(tool("list_scripts", "Alle Skripte, die das Token sehen darf, mit Entwürfen", "{}", true,
            (call, args) -> scripts.list(call.user())));
        tools.add(tool("get_script", "Code, Entwurf und Historie eines Skripts", SCRIPT_SCHEMA, true,
            (call, args) -> scripts.get(call.user(), scriptId(args))));
        tools.add(tool("save_draft", "Speichert einen Entwurf. Aktiv wird er erst mit activate_script.",
            SCRIPT_SCHEMA + """
                ,"code": {"type": "string"},
                "base": {"type": "string", "description": "hash der aktiven Fassung, auf der der Entwurf beruht"}
                """, false, (call, args) -> scripts.saveDraft(call.caller(), scriptId(args), required(args, "code"),
                string(args, "base"))));
        tools.add(tool("test_script", "Testet ein Skript gegen Logzeilen, eine Meldungsregel gegen "
            + "die indexierten Events der letzten 24 Stunden. Ohne code wird der Entwurf getestet, sonst die aktive Fassung. "
            + "Beispiel-Events eines Parsers gibt es nur für die von einem Menschen freigegebene aktive Fassung, "
            + "sonst nur Zahlen.",
            SCRIPT_SCHEMA + """
                ,"code": {"type": "string", "description": "zu testender Code, sonst Entwurf oder aktive Fassung"},
                "active": {"type": "boolean", "description": "aktive Fassung testen, auch wenn es einen Entwurf gibt"},
                "app": {"type": "string", "description": "Anwendung, deren Zeilen aus hot/ gelesen werden"},
                "lines": {"type": "integer", "minimum": 1},
                "from": {"type": "string"},
                "to": {"type": "string"},
                "text": {"type": "string", "description": "eigene Logzeilen statt einer Anwendung"}
                """, true, this::testScript));
        tools.add(tool("activate_script", "Aktiviert den Entwurf. Nur mit einem Token mit activate: true, "
            + "Lifecycle-Filter und Pseudonymisierung aktiviert immer ein Mensch.", SCRIPT_SCHEMA + """
                ,"force": {"type": "boolean", "description": "auch wenn sich die aktive Fassung geändert hat"},
                "confirm": {"type": "boolean", "description": "auch bei mehr als 1 % Fehlern im letzten Test"}
                """, false,
            (call, args) -> scripts.activate(call.caller(), scriptId(args), bool(args, "force"),
                bool(args, "confirm"))));
        tools.add(tool("rollback_script", "Aktiviert eine frühere Fassung aus der Historie wieder", SCRIPT_SCHEMA + """
            ,"at": {"type": "string", "description": "Zeitpunkt der Fassung aus get_script"}
            """, false, (call, args) -> scripts.rollback(call.caller(), scriptId(args),
            instant(args, "at").orElseThrow(() -> new SearchException("at fehlt.")))));
        tools.add(tool("archive_search_start", "Startet eine Suche im Archiv wie zgrep, auch über Monate. Läuft im "
            + "Hintergrund, Treffer mit archive_search_result abholen. Nur mit Recht archive.", """
                "apps": {"type": "array", "items": {"type": "string"},
                  "description": "Anwendungen, leer für alle mit Recht archive"},
                "hosts": {"type": "array", "items": {"type": "string"}, "description": "Hosts, leer für alle"},
                "query": {"type": "string", "description": "Text, mit regex: true ein regulärer Ausdruck in RE2-Syntax.\
                 Pseudonyme wie $Nutzer1 werden zurückübersetzt."},
                "regex": {"type": "boolean"},
                "ignoreCase": {"type": "boolean"},
                "context": {"type": "integer", "minimum": 0, "description": "Zeilen vor und nach jedem Treffer"},
                "from": {"type": "string", "description": "Beginn als ISO-8601-Zeitpunkt"},
                "to": {"type": "string", "description": "Ende als ISO-8601-Zeitpunkt, Standard jetzt"},
                "last": {"type": "string", "description": "statt from: Zeitraum bis to, z. B. 6h, 30d"}
                """, true, this::archiveSearchStart));
        tools.add(tool("archive_search_result", "Fortschritt und Treffer einer Archivsuche, weiter mit next. "
            + "Mit wait wird bis zu 30 Sekunden auf neue Treffer gewartet. cancel: true bricht die Suche ab.", """
                "id": {"type": "string", "description": "id aus archive_search_start"},
                "offset": {"type": "integer", "minimum": 0},
                "limit": {"type": "integer", "minimum": 1},
                "wait": {"type": "integer", "minimum": 0, "maximum": 30, "description": "Sekunden"},
                "cancel": {"type": "boolean"}
                """, false, this::archiveSearchResult));
        tools.add(tool("list_alerts", "Meldungen der Regeln, neueste zuerst, pseudonymisiert. eventId führt mit "
            + "get_context zum auslösenden Event.", """
                "state": {"type": "string", "enum": ["open", "acked", "resolved", "all"],
                    "description": "Standard open"},
                "limit": {"type": "integer", "minimum": 1}
                """, true, this::listAlerts));
        return tools;
    }

    private Object archiveSearchStart(Call call, Map<String, Object> args) {
        Instant to = instant(args, "to").orElse(null);
        Instant from = instant(args, "from").orElse(null);
        String last = string(args, "last");
        if (last != null) {
            from = (to == null ? Instant.now() : to).minus(duration(last));
        }
        ArchiveSearchJob job = archiveSearch.start(call.user(), owner(call),
            new ArchiveSearchRequest(strings(args, "apps"), strings(args, "hosts"), from, to, required(args, "query"),
                bool(args, "regex"), bool(args, "ignoreCase"), integer(args, "context").orElse(null)));
        return job.status();
    }

    private Object archiveSearchResult(Call call, Map<String, Object> args) throws InterruptedException {
        String id = required(args, "id");
        ArchiveSearchJob job;
        try {
            job = bool(args, "cancel") ? archiveSearch.cancel(owner(call), id) : archiveSearch.job(owner(call), id);
        } catch (NoSuchElementException e) {
            throw new SearchException(e.getMessage());
        }
        int offset = Math.max(0, integer(args, "offset").orElse(0));
        int limit = Math.clamp(integer(args, "limit").orElse(settings.maxEvents()), 1, settings.maxEvents());
        int wait = Math.clamp(integer(args, "wait").orElse(0), 0, 30);
        if (wait > 0) {
            job.await(offset, wait * 1000L);
        }
        List<ArchiveHit> hits = job.hits(offset, limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", job.status());
        result.put("hits", hits.stream().map(hit -> new ArchiveHit(hit.app(), hit.host(), hit.file(), hit.offset(),
            hit.before().stream().map(this::cut).toList(), cut(hit.line()),
            hit.after().stream().map(this::cut).toList())).toList());
        result.put("next", offset + hits.size());
        return result;
    }

    private Object listAlerts(Call call, Map<String, Object> args) {
        String state = string(args, "state");
        Alert.State filter = state == null ? Alert.State.OPEN : state.equalsIgnoreCase("all") ? null : state(state);
        int limit = Math.clamp(integer(args, "limit").orElse(settings.maxEvents()), 1, settings.maxEvents());
        return alerts.list(call.user(), filter).stream().limit(limit)
            .map(alert -> alertTexts.forUser(call.user(), call.session(), alert)).toList();
    }

    private static Alert.State state(String state) {
        try {
            return Alert.State.from(state);
        } catch (IllegalArgumentException e) {
            throw new SearchException("Unbekannter Zustand " + state + ", erlaubt sind open, acked, resolved, all.");
        }
    }

    private static Owner owner(Call call) {
        return new Owner(call.user().name(), call.user().token(), call.session());
    }

    private SyncToolSpecification tool(String name, String description, String properties, boolean readOnly,
        Handler handler) {
        String schema = properties.equals("{}") ? "{\"type\": \"object\", \"properties\": {}}"
            : "{\"type\": \"object\", \"properties\": {" + properties + "}}";
        Tool tool = Tool.builder().name(name).description(description).inputSchema(mcpJson, schema)
            .annotations(new ToolAnnotations(null, readOnly, false, readOnly, false, null)).build();
        BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> callHandler =
            (exchange, request) -> call(name, handler, exchange, request.arguments());
        return SyncToolSpecification.builder().tool(tool).callHandler(callHandler).build();
    }

    /**
     * Runs a tool for a session.
     *
     * @param name the tool
     * @param handler the implementation
     * @param exchange the MCP exchange, for the session id and the authentication
     * @param arguments the arguments
     * @return the result
     */
    CallToolResult call(String name, Handler handler, McpSyncServerExchange exchange, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        Call call;
        try {
            call = call(exchange);
        } catch (AccessDeniedException e) {
            return error("Zugriff verweigert: " + e.getMessage());
        }
        String error = null;
        try {
            Object result = handler.handle(call, args);
            return CallToolResult.builder().addTextContent(json.writeValueAsString(result)).build();
        } catch (SearchException | ScriptRequestException | ScriptException | NoSuchElementException e) {
            error = e.getMessage();
        } catch (AccessDeniedException e) {
            error = "Zugriff verweigert.";
        } catch (IllegalArgumentException | ClassCastException e) {
            error = "Ungültige Argumente: " + e.getMessage();
        } catch (Exception e) {
            LOGGER.error("MCP tool {} failed", name, e);
            error = "Interner Fehler, Details stehen im Server-Log.";
        } finally {
            audit.log(call.actor(), "mcp." + name, null, auditDetails(args, error));
        }
        return error(error);
    }

    private Call call(McpSyncServerExchange exchange) {
        Object value = exchange.transportContext() == null ? null
            : exchange.transportContext().get(AUTHENTICATION);
        if (!(value instanceof Authentication authentication)
            || !(authentication.getDetails() instanceof ApiTokenDetails details) || !details.mcp()) {
            throw new AccessDeniedException("kein MCP-Token");
        }
        LogGuyUser user = users.find(authentication.getName(), details)
            .orElseThrow(() -> new AccessDeniedException("unbekannter Nutzer " + authentication.getName()));
        Pseudonyms session = sessions.get(exchange.sessionId(), user);
        Actor actor = new Actor(user.name(), user.token(), Via.MCP);
        return new Call(user, session, new Caller(user, actor, details.activate()));
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    private static Map<String, Object> auditDetails(Map<String, Object> args, String error) {
        Map<String, Object> details = new TreeMap<>();
        args.forEach((key, value) -> {
            if (value instanceof String text && text.length() > 200) {
                details.put(key, text.length() + " Zeichen");
            } else {
                details.put(key, value);
            }
        });
        if (error != null) {
            details.put("error", error);
        }
        return details;
    }

    private Object searchLogs(Call call, Map<String, Object> args) throws Exception {
        SearchScope scope = scope(call, args);
        int limit = Math.clamp(integer(args, "limit").orElse(settings.maxEvents()), 1, settings.maxEvents());
        boolean ascending = "asc".equals(string(args, "order"));
        SearchResult result = search.search(scope, string(args, "cursor"), limit, ascending);
        List<Map<String, Object>> events = new ArrayList<>();
        int heldBack = 0;
        for (EventView event : result.events()) {
            Optional<Map<String, Object>> pseudonymized = event(call.session(), event);
            if (pseudonymized.isPresent()) {
                events.add(pseudonymized.get());
            } else {
                heldBack++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", result.total());
        out.put("events", events);
        out.put("heldBack", heldBack);
        out.put("cursor", result.done() ? null : result.cursor());
        out.put("done", result.done());
        return out;
    }

    /**
     * Pseudonymizes an event of the search and cuts long texts.
     *
     * @param session the pseudonyms
     * @param event the event
     * @return the event for the LLM, empty if it is held back
     */
    Optional<Map<String, Object>> event(Pseudonyms session, EventView event) {
        return pseudonymizedSearch.event(session, event).map(result -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", event.id());
            out.put("ts", event.ts());
            out.put("app", event.app());
            out.put("host", event.host());
            out.put("message", cut(result.get("message")));
            Map<String, Object> fields = new TreeMap<>();
            result.forEach((key, value) -> {
                if (!Set.of("app", "host", "ts", "message").contains(key)) {
                    fields.put(key, cut(value));
                }
            });
            out.put("fields", fields);
            return out;
        });
    }

    private Object histogram(Call call, Map<String, Object> args) throws Exception {
        SearchScope scope = scope(call, args);
        Histogram histogram = search.histogram(scope, string(args, "split"));
        return pseudonymizedSearch.histogram(call.session(), scope.apps(), histogram, this::cutText);
    }

    private Object topValues(Call call, Map<String, Object> args) throws Exception {
        SearchScope scope = scope(call, args);
        int limit = Math.clamp(integer(args, "limit").orElse(10), 1, settings.maxEvents());
        Map<String, List<FacetValue>> facets = search.facets(scope, strings(args, "fields"), limit);
        return pseudonymizedSearch.facets(call.session(), scope.apps(), facets, this::cutText);
    }

    private Object context(Call call, Map<String, Object> args) throws IOException {
        ContextView view;
        try {
            view = context.context(call.user(), required(args, "id"), integer(args, "before").orElse(null),
                integer(args, "after").orElse(null));
        } catch (ContextUnavailableException e) {
            // a broken lifecycle filter is reported to the client like a search error, never as unfiltered lines
            throw new SearchException(e.getMessage());
        }
        return pseudonymizedSearch.context(view, this::cutText);
    }

    private String cutText(String text) {
        return text == null ? null : String.valueOf(cut(text));
    }

    private Object status(Call call, Map<String, Object> args) {
        StatusView view = status.status();
        if (call.user().isAdmin()) {
            return view;
        }
        return Map.of("generated", view.generated(), "apps",
            view.apps().stream().filter(app -> call.user().mayRead(app.name())).toList());
    }

    private Object serverLog(Call call, Map<String, Object> args) {
        if (!call.user().isAdmin()) {
            throw new AccessDeniedException("admin");
        }
        String level = string(args, "level");
        int limit = Math.clamp(integer(args, "limit").orElse(settings.maxEvents()), 1, settings.maxEvents());
        List<LogEntry> entries = serverLog.entries(integer(args, "after").map(Long::valueOf).orElse(-1L),
            level == null ? null : Level.toLevel(level, Level.INFO), string(args, "logger"), limit);
        return entries.stream().map(entry -> new LogEntry(entry.seq(), entry.ts(), entry.level(), entry.logger(),
            entry.thread(), serverText(call.session(), entry.message()), serverText(call.session(), entry.thrown())))
            .toList();
    }

    private String serverText(Pseudonyms session, String text) {
        if (text == null) {
            return null;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("app", "");
        event.put("message", text);
        return pseudonyms.applyFor(session, null, event).map(result -> cut(result.get("message")))
            .map(String::valueOf).orElse(HELD_BACK);
    }

    private Object testScript(Call call, Map<String, Object> args) throws IOException {
        ScriptId id = scriptId(args);
        String app = string(args, "app");
        TestSource source = new TestSource(app, integer(args, "lines").orElse(null), instant(args, "from")
            .orElse(null), instant(args, "to").orElse(null), null, string(args, "text"));
        if (id.kind() == ScriptKind.PARSER) {
            String tested = scripts.codeForTest(call.user(), id, string(args, "code"), bool(args, "active"));
            ParserTestResult parser = tester.testParser(call.user(), id, tested, false, source);
            // a parser that is not approved by a human may encode plain values so no pseudonymization rule finds
            // them, so its test gives only numbers
            return scripts.isApprovedActive(id, tested) ? parserResult(call.session(), app, parser)
                : parserNumbers(parser);
        }
        Object result = tester.test(call.user(), id, string(args, "code"), bool(args, "active"), source);
        if (result instanceof PseudonymTestResult test) {
            Map<String, Integer> suspicious = new TreeMap<>();
            test.suspicious().forEach(s -> suspicious.merge(s.field() + " " + s.category(), 1, Integer::sum));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("events", test.events());
            out.put("heldBack", test.heldBack());
            out.put("pseudonyms", test.pseudonyms());
            out.put("suspicious", suspicious);
            return out;
        }
        if (result instanceof LifecycleTestResult test) {
            // only numbers, lifecycle filters are changed by humans
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("lines", test.lines());
            out.put("changed", test.changed());
            out.put("dropped", test.dropped());
            out.put("errors", test.errors());
            out.put("events", test.events());
            out.put("eventsChanged", test.eventsChanged());
            out.put("eventErrors", test.eventErrors());
            return out;
        }
        if (result instanceof RuleTestResult test) {
            // numbers and event ids only, the texts may contain plain data
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("source", test.source());
            out.put("events", test.events());
            out.put("truncated", test.truncated());
            out.put("hits", test.hits());
            out.put("alerts", test.alerts());
            out.put("notifications", test.notifications());
            out.put("errors", test.errors());
            out.put("hitEventIds", test.hitSamples().stream().map(RuleTester.Hit::id).toList());
            return out;
        }
        throw new ScriptRequestException(ScriptRequestException.Reason.INVALID,
            "Dieses Testergebnis gibt es über MCP nicht.");
    }

    // no field values, error texts or names the script returned, these could carry plain values
    private static Map<String, Object> parserNumbers(ParserTestResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", result.source());
        out.put("lines", result.lines());
        out.put("events", result.events());
        out.put("errors", result.errors());
        out.put("eventsPerSecond", result.eventsPerSecond());
        out.put("declared", result.declared());
        out.put("undeclared", result.undeclared().size());
        out.put("errorKinds", result.errorCounts().values().stream().sorted(Comparator.reverseOrder()).toList());
        out.put("truncated", result.truncated());
        out.put("note", "Nur Zahlen: Beispiel-Events, Feldwerte und Fehlertexte gibt es über MCP nur für die "
            + "aktive Fassung, die ein Mensch freigegeben hat.");
        return out;
    }

    private ParserTestResult parserResult(Pseudonyms session, String app, ParserTestResult result) {
        Set<String> apps = app == null ? null : Set.of(app);
        Map<String, Integer> errors = new TreeMap<>();
        result.errorCounts().forEach((message, count) -> errors.merge(serverText(session, message), count,
            Integer::sum));
        return new ParserTestResult(result.source(), result.lines(), result.events(), result.errors(),
            result.eventsPerSecond(), result.declared(), result.undeclared(), errors,
            testEvents(session, apps, result.sample()), testEvents(session, apps, result.failed()),
            result.truncated());
    }

    private List<TestEvent> testEvents(Pseudonyms session, Collection<String> apps, List<TestEvent> events) {
        List<TestEvent> out = new ArrayList<>();
        for (TestEvent event : events) {
            Map<String, Object> input = new LinkedHashMap<>(event.fields() == null ? Map.of() : event.fields());
            input.put("app", apps == null ? "" : apps.iterator().next());
            input.put("message", event.message());
            if (event.error() != null) {
                input.put("parse_error", event.error());
            }
            Optional<Map<String, Object>> result = pseudonyms.applyFor(session, apps, input);
            if (result.isEmpty()) {
                out.add(new TestEvent(event.line(), event.ts(), HELD_BACK, Map.of(), null));
                continue;
            }
            Map<String, Object> fields = new TreeMap<>();
            result.get().forEach((key, value) -> {
                if (!Set.of("app", "message", "parse_error").contains(key)) {
                    fields.put(key, cut(value));
                }
            });
            Object error = result.get().get("parse_error");
            out.add(new TestEvent(event.line(), event.ts(), String.valueOf(cut(result.get().get("message"))), fields,
                error == null ? null : String.valueOf(cut(error))));
        }
        return out;
    }

    private SearchScope scope(Call call, Map<String, Object> args) {
        Instant to = instant(args, "to").orElse(null);
        Instant from = instant(args, "from").orElse(null);
        String last = string(args, "last");
        if (last != null) {
            from = (to == null ? Instant.now() : to).minus(duration(last));
        }
        return pseudonymizedSearch.scope(call.user(), call.session(), new SearchRequest(strings(args, "apps"),
            string(args, "query"), from, to, filters(args)));
    }

    /**
     * Cuts long texts to the limit of the endpoint.
     *
     * @param value a value of an event
     * @return the value, strings and their list elements cut
     */
    Object cut(Object value) {
        if (value instanceof String text && text.length() > settings.maxText()) {
            int end = settings.maxText();
            if (Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            return text.substring(0, end) + " … [gekürzt, " + text.length() + " Zeichen]";
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::cut).toList();
        }
        return value;
    }

    private String cut(String value) {
        return (String) cut((Object) value);
    }

    private static ScriptId scriptId(Map<String, Object> args) {
        return ScriptController.id(required(args, "kind"), required(args, "name"));
    }

    private static String required(Map<String, Object> args, String name) {
        String value = string(args, name);
        if (value == null || value.isBlank()) {
            throw new SearchException("Das Argument „" + name + "“ fehlt.");
        }
        return value;
    }

    private static String string(Map<String, Object> args, String name) {
        Object value = args.get(name);
        return value == null ? null : value.toString();
    }

    private static boolean bool(Map<String, Object> args, String name) {
        Object value = args.get(name);
        return value instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(value));
    }

    private static Optional<Integer> integer(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Number number) {
            return Optional.of(number.intValue());
        }
        try {
            return Optional.of(Integer.parseInt(value.toString().trim()));
        } catch (NumberFormatException e) {
            throw new SearchException("„" + name + "“ ist keine Zahl.");
        }
    }

    private static Optional<Instant> instant(Map<String, Object> args, String name) {
        String value = string(args, name);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value.trim()));
        } catch (DateTimeException e) {
            throw new SearchException("„" + name + "“ ist kein ISO-8601-Zeitpunkt wie 2026-10-01T08:00:00Z.");
        }
    }

    private static Duration duration(String value) {
        Matcher matcher = RELATIVE.matcher(value.trim());
        if (!matcher.matches()) {
            throw new SearchException("„last“ erwartet z. B. 30m, 6h oder 2d.");
        }
        long amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2)) {
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            default -> Duration.ofDays(amount);
        };
    }

    private static List<String> strings(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        return List.of(value.toString().split("\\s*,\\s*"));
    }

    private static Map<String, List<String>> filters(Map<String, Object> args) {
        Object value = args.get("filters");
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new SearchException("„filters“ muss ein Objekt sein.");
        }
        Map<String, List<String>> filters = new LinkedHashMap<>();
        map.forEach((field, values) -> filters.put(String.valueOf(field), values instanceof Collection<?> list
            ? list.stream().map(String::valueOf).toList() : List.of(String.valueOf(values))));
        return filters;
    }

    /**
     * The implementation of a tool.
     */
    @FunctionalInterface
    interface Handler {

        /**
         * Runs the tool.
         *
         * @param call user and session
         * @param args the arguments
         * @return the result, serialized as JSON
         * @throws Exception on errors, reported to the LLM
         */
        Object handle(Call call, Map<String, Object> args) throws Exception;
    }

    /**
     * A tool call.
     *
     * @param user the user of the token
     * @param session the pseudonyms of the session
     * @param caller user and actor for script changes
     */
    record Call(LogGuyUser user, Pseudonyms session, Caller caller) {

        Actor actor() {
            return caller.actor();
        }
    }
}
