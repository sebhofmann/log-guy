package de.gbv.logguy.server.archive;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

import de.gbv.logguy.server.config.AppGroups;
import de.gbv.logguy.server.script.FieldDefinition;
import de.gbv.logguy.server.script.ModuleInfo;
import de.gbv.logguy.server.script.ParsedEvent;
import de.gbv.logguy.server.script.ParserScript;
import de.gbv.logguy.server.script.ScriptException;
import de.gbv.logguy.server.script.ScriptKind;
import de.gbv.logguy.server.script.ScriptListener;
import de.gbv.logguy.server.script.ScriptRuntime;
import de.gbv.logguy.server.script.ScriptStore;
import de.gbv.logguy.server.script.ScriptStore.ScriptId;
import de.gbv.logguy.server.script.Templates;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The active lifecycle filters: every file in {@code scripts/lifecycle/} and every always active template not
 * replaced by a file, in the order of their names. A filter changes lines on their way into the archive with
 * {@code line(text)} and the fields of events on their way into the index with {@code fields(event)}.
 * <p>
 * GraalJS contexts are single threaded, so every thread gets its own {@link Runner}.
 */
public class LifecycleFilters implements ScriptListener {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ScriptRuntime runtime;

    private final JsonMapper json;

    private final Supplier<AppGroups> groups;

    private final AtomicReference<Generation> current = new AtomicReference<>(
        new Generation(List.of(), List.of("not loaded")));

    /**
     * Creates the filters and loads the active scripts.
     *
     * @param runtime the script runtime
     * @param store the scripts
     * @param json for passing events to {@code fields(event)}
     * @param groups the application groups as they are now
     */
    public LifecycleFilters(ScriptRuntime runtime, ScriptStore store, JsonMapper json, Supplier<AppGroups> groups) {
        this.runtime = runtime;
        this.json = json;
        this.groups = groups;
        load(store.active());
    }

    @Override
    public void changed(ScriptKind kind, ScriptStore store) {
        if (kind == ScriptKind.LIFECYCLE) {
            load(store.active());
        }
    }

    /**
     * Loads the filters.
     *
     * @param active all active scripts
     * @return errors of filters that could not be loaded, empty if all could
     */
    public synchronized List<String> load(Map<ScriptId, String> active) {
        Map<String, String> code = new TreeMap<>();
        for (ScriptId id : Templates.ALWAYS_ACTIVE) {
            if (id.kind() == ScriptKind.LIFECYCLE) {
                try {
                    code.put(id.name(), Templates.read(id));
                } catch (IOException e) {
                    throw new IllegalStateException("template " + id + " missing", e);
                }
            }
        }
        active.forEach((id, source) -> {
            if (id.kind() == ScriptKind.LIFECYCLE) {
                code.put(id.name(), source);
            }
        });
        List<Definition> definitions = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        code.forEach((name, source) -> {
            try {
                ModuleInfo info = ModuleInfo.check(runtime, ScriptKind.LIFECYCLE, name, source);
                definitions.add(new Definition(name, source, ScriptStore.hash(source), info.apps()));
            } catch (ScriptException e) {
                errors.add(e.getMessage());
            }
        });
        if (!errors.isEmpty()) {
            LOGGER.error("Lifecycle filters failed, nothing is archived until they are repaired: {}", errors);
        } else {
            LOGGER.info("lifecycle filters {}", definitions.stream().map(Definition::label).toList());
        }
        current.set(new Generation(List.copyOf(definitions), List.copyOf(errors)));
        return errors;
    }

    /**
     * Errors of filters that could not be loaded. As long as there are any, nothing is archived, because lines
     * must never reach the archive unfiltered.
     *
     * @return the errors
     */
    public List<String> errors() {
        return current.get().errors();
    }

    /**
     * The filters for an application.
     *
     * @param app the application
     * @return name and version of each filter, in the order they run
     */
    public List<String> labels(String app) {
        AppGroups now = groups.get();
        return current.get().definitions().stream().filter(d -> d.appliesTo(app, now)).map(Definition::label)
            .toList();
    }

    /**
     * Creates a runner for the calling thread.
     *
     * @return the runner, to be closed by the thread
     */
    public Runner runner() {
        return new Runner();
    }

    /**
     * Runs a filter that is not active against test lines and events, without changing anything.
     *
     * @param name name of the script
     * @param code the module
     * @param source description of the test source
     * @param lines the lines for {@code line(text)}
     * @param events the parsed events for {@code fields(event)}, may be empty
     * @param maxSamples most examples of each kind in the result
     * @return what the filter changed
     * @throws ScriptException if the module is not a valid filter
     */
    public LifecycleTestResult test(String name, String code, String source, List<String> lines,
        List<Map<String, Object>> events, int maxSamples) throws ScriptException {
        ModuleInfo info = ModuleInfo.check(runtime, ScriptKind.LIFECYCLE, name, code);
        Filter filter = Filter.load(runtime, json, new Definition(name, code, ScriptStore.hash(code), info.apps()));
        try {
            int changed = 0;
            int dropped = 0;
            int errors = 0;
            List<LineChange> samples = new ArrayList<>();
            List<LineChange> failed = new ArrayList<>();
            if (filter.filtersLines()) {
                for (int i = 0; i < lines.size(); i++) {
                    String before = lines.get(i);
                    LineResult result = filter.line(before);
                    LineChange change = new LineChange(i + 1, before, result.text(), result.error());
                    if (result.error() != null) {
                        errors++;
                        add(failed, change, maxSamples);
                    } else if (result.text() == null) {
                        dropped++;
                        add(samples, change, maxSamples);
                    } else if (!result.text().equals(before)) {
                        changed++;
                        add(samples, change, maxSamples);
                    }
                }
            }
            int eventsChanged = 0;
            int eventErrors = 0;
            List<EventChange> eventSamples = new ArrayList<>();
            if (filter.filtersFields()) {
                for (int i = 0; i < events.size(); i++) {
                    Map<String, Object> before = events.get(i);
                    try {
                        Map<String, Object> after = filter.fields(new LinkedHashMap<>(before));
                        if (!after.equals(json.readValue(json.writeValueAsString(before), MAP))) {
                            eventsChanged++;
                            add(eventSamples, new EventChange(i + 1, before, after, null), maxSamples);
                        }
                    } catch (ScriptException e) {
                        eventErrors++;
                        add(eventSamples, new EventChange(i + 1, before, null, e.getMessage()), maxSamples);
                    }
                }
            }
            return new LifecycleTestResult(source, info.apps(), filter.filtersLines(), filter.filtersFields(),
                lines.size(), changed, dropped, errors, samples, failed, events.size(), eventsChanged, eventErrors,
                eventSamples);
        } finally {
            filter.close();
        }
    }

    private static <T> void add(List<T> list, T value, int max) {
        if (list.size() < max) {
            list.add(value);
        }
    }

    /**
     * A line of a test that a filter changed, dropped or failed on.
     *
     * @param line number of the line, from 1
     * @param before the line
     * @param after the new line, {@code null} if dropped or failed
     * @param error why the filter failed, {@code null} if it did not
     */
    public record LineChange(int line, String before, String after, String error) {
    }

    /**
     * An event of a test whose fields a filter changed or failed on.
     *
     * @param event number of the event, from 1
     * @param before the event
     * @param after the changed event, {@code null} if the filter failed
     * @param error why the filter failed, {@code null} if it did not
     */
    public record EventChange(int event, Map<String, Object> before, Map<String, Object> after, String error) {
    }

    /**
     * Result of a test of a lifecycle filter.
     *
     * @param source the test source
     * @param apps the application patterns of the filter
     * @param filtersLines whether the filter exports {@code line(text)}
     * @param filtersFields whether the filter exports {@code fields(event)}
     * @param lines lines tested
     * @param changed lines changed
     * @param dropped lines left out of the archive
     * @param errors lines the filter failed on, replaced by the placeholder in the archive
     * @param samples the first changed or dropped lines
     * @param failed the first lines the filter failed on
     * @param events events tested with {@code fields(event)}
     * @param eventsChanged events whose fields changed
     * @param eventErrors events the filter failed on
     * @param eventSamples the first changed events and failures
     */
    public record LifecycleTestResult(String source, List<String> apps, boolean filtersLines,
        boolean filtersFields, int lines, int changed, int dropped, int errors, List<LineChange> samples,
        List<LineChange> failed, int events, int eventsChanged, int eventErrors, List<EventChange> eventSamples) {
    }

    /**
     * Code of a filter with its applications.
     *
     * @param name name of the script
     * @param code the module
     * @param version short hash of the code
     * @param apps application patterns from {@code meta.apps}
     */
    private record Definition(String name, String code, String version, List<String> apps) {

        boolean appliesTo(String app, AppGroups groups) {
            return groups.matchesAny(apps, app);
        }

        String label() {
            return name + "@" + version;
        }
    }

    private record Generation(List<Definition> definitions, List<String> errors) {
    }

    /**
     * What a filter made of a line.
     *
     * @param text the new line, {@code null} if the line is dropped or the filter failed
     * @param error why the filter failed, {@code null} if it did not
     */
    public record LineResult(String text, String error) {
    }

    /**
     * The filters of one thread, with their own script contexts. Not thread safe. Follows changes of the active
     * filters on the next call.
     */
    public final class Runner implements AutoCloseable {

        private Generation generation;

        private AppGroups loadedGroups;

        private final List<Filter> filters = new ArrayList<>();

        private final Map<String, List<Filter>> byApp = new HashMap<>();

        private Runner() {
        }

        /**
         * The filters for an application, loaded for this thread.
         *
         * @param app the application
         * @return the filters in the order they run
         * @throws ScriptException if a filter cannot be loaded
         */
        public List<Filter> forApp(String app) throws ScriptException {
            Generation now = current.get();
            if (now != generation) {
                close();
                for (Definition definition : now.definitions()) {
                    filters.add(Filter.load(runtime, json, definition));
                }
                generation = now;
            }
            AppGroups nowGroups = groups.get();
            if (nowGroups != loadedGroups) {
                byApp.clear();
                loadedGroups = nowGroups;
            }
            return byApp.computeIfAbsent(app, a -> filters.stream().filter(f -> f.definition.appliesTo(a, nowGroups))
                .toList());
        }

        /**
         * Runs {@code fields(event)} of all filters of the application on a parsed event, on its way into the
         * index. If a filter fails, the message is replaced by the placeholder and the fields are dropped, so
         * nothing the filter should have removed reaches the index.
         *
         * @param app the application
         * @param host the host
         * @param parsed the event from the parser
         * @param script the parser, for the declared fields
         * @param placeholder replaces the message if a filter fails, {@code {filter}} is the name of the filter
         * @return the changed event, the same if no filter changes fields
         * @throws ScriptException if a filter cannot be loaded
         */
        public ParsedEvent fields(String app, String host, ParsedEvent parsed, ParserScript script,
            String placeholder) throws ScriptException {
            List<Filter> active = forApp(app);
            if (active.stream().noneMatch(Filter::filtersFields)) {
                return parsed;
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("app", app);
            event.put("host", host);
            if (parsed.ts() != null) {
                event.put("ts", parsed.ts().toString());
            }
            event.put("message", parsed.message());
            parsed.fields().forEach((name, value) -> event.put(name, value instanceof Instant instant
                ? instant.toString() : value));
            Map<String, Object> changed = event;
            for (Filter filter : active) {
                if (!filter.filtersFields()) {
                    continue;
                }
                try {
                    changed = filter.fields(changed);
                } catch (ScriptException e) {
                    return withheld(parsed, filter, placeholder, e.getMessage());
                }
            }
            Map<String, Object> fields = new HashMap<>();
            for (FieldDefinition definition : script.fields().values()) {
                try {
                    Object value = definition.type().convert(changed.get(definition.name()));
                    if (value != null) {
                        fields.put(definition.name(), value);
                    }
                } catch (RuntimeException e) {
                    Filter last = active.stream().filter(Filter::filtersFields).reduce((a, b) -> b).orElseThrow();
                    return withheld(parsed, last, placeholder, last.label() + ": invalid field "
                        + definition.name() + ": " + e.getMessage());
                }
            }
            Object message = changed.get("message");
            return new ParsedEvent(parsed.ts(), message == null ? "" : message.toString(), fields, parsed.error());
        }

        private static ParsedEvent withheld(ParsedEvent parsed, Filter filter, String placeholder, String error) {
            return new ParsedEvent(parsed.ts(), placeholder.replace("{filter}", filter.name()), Map.of(), error);
        }

        /**
         * Whether the active filters changed since the last call of {@link #forApp(String)}.
         *
         * @return true if the next call loads new filters
         */
        public boolean outdated() {
            return current.get() != generation;
        }

        @Override
        public void close() {
            filters.forEach(Filter::close);
            filters.clear();
            byApp.clear();
            generation = null;
            loadedGroups = null;
        }
    }

    /**
     * A filter loaded into a context of one thread.
     */
    public static final class Filter {

        private final ScriptRuntime runtime;

        private final JsonMapper json;

        private final Definition definition;

        private final Context context;

        private final Value line;

        private final Value fields;

        private Filter(ScriptRuntime runtime, JsonMapper json, Definition definition, Context context, Value line,
            Value fields) {
            this.runtime = runtime;
            this.json = json;
            this.definition = definition;
            this.context = context;
            this.line = line;
            this.fields = fields;
        }

        static Filter load(ScriptRuntime runtime, JsonMapper json, Definition definition) throws ScriptException {
            Context context = runtime.newContext();
            try {
                Value exports = context.eval(ScriptRuntime.module("lifecycle/" + definition.name() + ".js",
                    definition.code()));
                return new Filter(runtime, json, definition, context, function(exports, "line"),
                    function(exports, "fields"));
            } catch (PolyglotException e) {
                context.close();
                throw new ScriptException("lifecycle/" + definition.name() + ": " + e.getMessage(), e);
            }
        }

        private static Value function(Value exports, String name) {
            Value member = exports.getMember(name);
            return member != null && member.canExecute() ? member : null;
        }

        /**
         * Name and version, e.g. {@code ip-kuerzen@3f2a9c01b2d4}.
         *
         * @return the label
         */
        public String label() {
            return definition.label();
        }

        /**
         * Name of the script.
         *
         * @return e.g. {@code ip-kuerzen}
         */
        public String name() {
            return definition.name();
        }

        /**
         * Whether the filter changes lines.
         *
         * @return true if it exports {@code line(text)}
         */
        public boolean filtersLines() {
            return line != null;
        }

        /**
         * Whether the filter changes the fields in the index.
         *
         * @return true if it exports {@code fields(event)}
         */
        public boolean filtersFields() {
            return fields != null;
        }

        /**
         * Runs {@code line(text)}.
         *
         * @param text the line without line break
         * @return the new line, or why the filter failed
         */
        public LineResult line(String text) {
            if (line == null) {
                return new LineResult(text, null);
            }
            ScriptRuntime.Call call = runtime.start(context);
            try {
                Value result = line.execute(text);
                runtime.end(call);
                if (result == null || result.isNull()) {
                    return new LineResult(null, null);
                }
                return new LineResult(result.isString() ? result.asString() : result.toString(), null);
            } catch (RuntimeException e) {
                boolean timeout = runtime.end(call);
                return new LineResult(null, timeout ? "timeout" : e.getMessage());
            }
        }

        /**
         * Runs {@code fields(event)}.
         *
         * @param event {@code app}, {@code host}, {@code ts}, {@code message} and the fields
         * @return the changed event
         * @throws ScriptException if the filter fails
         */
        public Map<String, Object> fields(Map<String, Object> event) throws ScriptException {
            if (fields == null) {
                return event;
            }
            Value jsonObject = context.getBindings("js").getMember("JSON");
            ScriptRuntime.Call call = runtime.start(context);
            try {
                Value input = jsonObject.invokeMember("parse", json.writeValueAsString(event));
                Value output = fields.execute(input);
                Value result = output == null || output.isNull() ? input : output;
                String text = jsonObject.invokeMember("stringify", result).asString();
                runtime.end(call);
                return json.readValue(text, MAP);
            } catch (RuntimeException e) {
                boolean timeout = runtime.end(call);
                throw new ScriptException(label() + ": " + (timeout ? "timeout" : e.getMessage()), e);
            }
        }

        void close() {
            context.close();
        }
    }
}
