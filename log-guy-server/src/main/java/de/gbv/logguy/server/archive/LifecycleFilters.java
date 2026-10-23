package de.gbv.logguy.server.archive;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

import de.gbv.logguy.server.script.ModuleInfo;
import de.gbv.logguy.server.script.ScriptException;
import de.gbv.logguy.server.script.ScriptKind;
import de.gbv.logguy.server.script.ScriptListener;
import de.gbv.logguy.server.script.ScriptRuntime;
import de.gbv.logguy.server.script.ScriptStore;
import de.gbv.logguy.server.script.ScriptStore.ScriptId;
import de.gbv.logguy.server.script.Templates;
import de.gbv.logguy.server.security.LogGuyUser;
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

    private final AtomicReference<Generation> current = new AtomicReference<>(
        new Generation(List.of(), List.of("not loaded")));

    /**
     * Creates the filters and loads the active scripts.
     *
     * @param runtime the script runtime
     * @param store the scripts
     * @param json for passing events to {@code fields(event)}
     */
    public LifecycleFilters(ScriptRuntime runtime, ScriptStore store, JsonMapper json) {
        this.runtime = runtime;
        this.json = json;
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
        return current.get().definitions().stream().filter(d -> d.appliesTo(app)).map(Definition::label).toList();
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
     * Code of a filter with its applications.
     *
     * @param name name of the script
     * @param code the module
     * @param version short hash of the code
     * @param apps application patterns from {@code meta.apps}
     */
    private record Definition(String name, String code, String version, List<String> apps) {

        boolean appliesTo(String app) {
            return apps.stream().anyMatch(pattern -> LogGuyUser.matches(pattern, app));
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
            return byApp.computeIfAbsent(app, a -> filters.stream().filter(f -> f.definition.appliesTo(a))
                .toList());
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
