package de.gbv.logguy.server.pseudonym;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

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
import de.gbv.logguy.server.script.TextFunctions;
import de.gbv.logguy.server.security.LogGuyUser;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the pseudonymization scripts over everything that leaves the MCP endpoint. If a script fails or runs too
 * long, the event is held back.
 */
public class PseudonymService implements ScriptListener, AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final int MAX_SUSPICIOUS = 100;

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ScriptRuntime runtime;

    private final JsonMapper json;

    private final AtomicReference<Modules> modules = new AtomicReference<>(new Modules(List.of(), List.of()));

    /**
     * Creates the service and loads the active scripts.
     *
     * @param runtime the script runtime
     * @param store the scripts
     * @param json for passing events to the scripts
     */
    public PseudonymService(ScriptRuntime runtime, ScriptStore store, JsonMapper json) {
        this.runtime = runtime;
        this.json = json;
        load(store.active());
    }

    @Override
    public void changed(ScriptKind kind, ScriptStore store) {
        if (kind == ScriptKind.PSEUDONYM) {
            load(store.active());
        }
    }

    /**
     * Loads the pseudonymization scripts: every active file and every template not replaced by a file.
     *
     * @param active all active scripts
     * @return errors of scripts that could not be loaded, empty if all could
     */
    public synchronized List<String> load(Map<ScriptId, String> active) {
        Map<ScriptId, String> code = new TreeMap<>();
        for (ScriptId id : Templates.ALWAYS_ACTIVE) {
            try {
                code.put(id, Templates.read(id));
            } catch (IOException e) {
                throw new IllegalStateException("template " + id + " missing", e);
            }
        }
        active.forEach((id, source) -> {
            if (id.kind() == ScriptKind.PSEUDONYM) {
                code.put(id, source);
            }
        });
        List<Module> loaded = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        code.forEach((id, source) -> {
            try {
                loaded.add(Module.load(runtime, id, source));
            } catch (ScriptException e) {
                errors.add(e.getMessage());
            }
        });
        if (!errors.isEmpty()) {
            LOGGER.error("Pseudonymization scripts failed, everything leaving the MCP endpoint is held back: {}",
                errors);
        }
        Modules old = modules.getAndSet(new Modules(List.copyOf(loaded), List.copyOf(errors)));
        old.close();
        return errors;
    }

    /**
     * Errors of scripts that could not be loaded. As long as there are any, every event is held back.
     *
     * @return the errors
     */
    public List<String> errors() {
        return modules.get().errors();
    }

    /**
     * Pseudonymizes an event.
     *
     * @param session the pseudonyms of the session
     * @param event the event: {@code app}, {@code host}, {@code ts}, {@code message} and the fields
     * @return the pseudonymized event, empty if it has to be held back
     */
    public Optional<Map<String, Object>> apply(Pseudonyms session, Map<String, Object> event) {
        String app = event.get("app") instanceof String name ? name : "";
        return applyFor(session, List.of(app), event);
    }

    /**
     * Pseudonymizes a value that belongs to several applications, e.g. a facet value over all applications of a
     * search or a line of the own log. Every script for at least one of the applications runs once.
     *
     * @param session the pseudonyms of the session
     * @param apps the applications, {@code null} for all scripts
     * @param event the event, as for {@link #apply}
     * @return the pseudonymized event, empty if it has to be held back
     */
    public Optional<Map<String, Object>> applyFor(Pseudonyms session, Collection<String> apps,
        Map<String, Object> event) {
        Modules current = modules.get();
        if (!current.errors().isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> result = event;
        for (Module module : current.modules()) {
            if (apps == null || apps.stream().anyMatch(module::appliesTo)) {
                Optional<Map<String, Object>> next = module.apply(runtime, json, session, result);
                if (next.isEmpty()) {
                    return next;
                }
                result = next.get();
            }
        }
        return Optional.of(result);
    }

    /**
     * Pseudonymizes a text that is not an event, e.g. a line of context.
     *
     * @param session the pseudonyms of the session
     * @param app the application the text belongs to, may be {@code null}
     * @param text the text
     * @return the pseudonymized text, empty if it has to be held back
     */
    public Optional<String> text(Pseudonyms session, String app, String text) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("app", app == null ? "" : app);
        event.put("message", text);
        return apply(session, event).map(result -> result.get("message") instanceof String message ? message : "");
    }

    /**
     * Fields on which only pseudonyms are accepted, by {@code meta.fields} of the scripts for the application.
     *
     * @param app the application
     * @return the field names
     */
    public Set<String> protectedFields(String app) {
        Set<String> fields = new TreeSet<>();
        for (Module module : modules.get().modules()) {
            if (module.appliesTo(app)) {
                fields.addAll(module.fields());
            }
        }
        return fields;
    }

    /**
     * Runs one script, usually a draft, over events without changing the active scripts. Pseudonyms are counted
     * per category, and values that look like IP or mail addresses after the script are listed as suspicious.
     *
     * @param id the script
     * @param code the code to test
     * @param events the events, as for {@link #apply}
     * @param sample number of events to return
     * @return the result
     * @throws ScriptException if the script cannot be loaded
     */
    public PseudonymTestResult test(ScriptId id, String code, List<Map<String, Object>> events, int sample)
        throws ScriptException {
        Module module = Module.load(runtime, id, code);
        try {
            Pseudonyms session = new Pseudonyms();
            List<Map<String, Object>> shown = new ArrayList<>();
            List<Suspicious> suspicious = new ArrayList<>();
            int held = 0;
            int index = 0;
            for (Map<String, Object> event : events) {
                index++;
                String app = event.get("app") instanceof String name ? name : "";
                Optional<Map<String, Object>> result = module.appliesTo(app)
                    ? module.apply(runtime, json, session, event) : Optional.of(event);
                if (result.isEmpty()) {
                    held++;
                    continue;
                }
                if (shown.size() < sample) {
                    shown.add(result.get());
                }
                if (suspicious.size() < MAX_SUSPICIOUS) {
                    findSuspicious(index, result.get(), session, suspicious);
                }
            }
            return new PseudonymTestResult(events.size(), held, session.counts(), suspicious, shown);
        } finally {
            module.close();
        }
    }

    private static void findSuspicious(int index, Map<String, Object> event, Pseudonyms session,
        List<Suspicious> found) {
        for (Map.Entry<String, Object> entry : event.entrySet()) {
            if (entry.getKey().equals("ts") || entry.getKey().equals("host")) {
                continue;
            }
            List<Object> values = entry.getValue() instanceof List<?> list ? new ArrayList<>(list)
                : List.of(String.valueOf(entry.getValue()));
            for (Object value : values) {
                String text = String.valueOf(value);
                for (String ip : TextFunctions.findIps(text)) {
                    found.add(new Suspicious(index, entry.getKey(), "IP", ip));
                }
                for (String mail : TextFunctions.findEmails(text)) {
                    found.add(new Suspicious(index, entry.getKey(), "Mail", mail));
                }
            }
        }
    }

    @Override
    public void close() {
        modules.getAndSet(new Modules(List.of(), List.of("closed"))).close();
    }

    /**
     * A value that looks personal after the script ran.
     *
     * @param event number of the event, from 1
     * @param field the field or {@code message}
     * @param category {@code IP} or {@code Mail}
     * @param value the value
     */
    public record Suspicious(int event, String field, String category, String value) {
    }

    /**
     * Result of a test of a pseudonymization script.
     *
     * @param events events tested
     * @param heldBack events the script would have held back
     * @param pseudonyms pseudonyms per category
     * @param suspicious values that look personal after the script, at most 100
     * @param sample the first events after the script
     */
    public record PseudonymTestResult(int events, int heldBack, Map<String, Integer> pseudonyms,
        List<Suspicious> suspicious, List<Map<String, Object>> sample) {
    }

    private record Modules(List<Module> modules, List<String> errors) {

        void close() {
            modules.forEach(Module::close);
        }
    }

    /**
     * A loaded script. GraalJS contexts are single threaded, so calls are serialized by a lock.
     */
    private record Module(ScriptId id, List<String> apps, Set<String> fields, Context context, Value apply,
        ReentrantLock lock) {

        static Module load(ScriptRuntime runtime, ScriptId id, String code) throws ScriptException {
            ModuleInfo info = ModuleInfo.check(runtime, ScriptKind.PSEUDONYM, id.name(), code);
            Context context = runtime.newContext();
            try {
                Value exports = context.eval(ScriptRuntime.module(id + ".js", code));
                Value meta = exports.getMember("meta");
                Set<String> fields = new TreeSet<>();
                Value list = meta.getMember("fields");
                if (list != null && list.hasArrayElements()) {
                    for (long i = 0; i < list.getArraySize(); i++) {
                        fields.add(list.getArrayElement(i).asString());
                    }
                }
                return new Module(id, info.apps(), Set.copyOf(fields), context, exports.getMember("apply"),
                    new ReentrantLock());
            } catch (PolyglotException | ClassCastException e) {
                context.close();
                throw new ScriptException(id + ": " + e.getMessage(), e);
            }
        }

        boolean appliesTo(String app) {
            return apps.stream().anyMatch(pattern -> LogGuyUser.matches(pattern, app));
        }

        Optional<Map<String, Object>> apply(ScriptRuntime runtime, JsonMapper json, Pseudonyms session,
            Map<String, Object> event) {
            lock.lock();
            try {
                return ScriptRuntime.withPseudonyms(session::pseudonym, () -> call(runtime, json, event));
            } finally {
                lock.unlock();
            }
        }

        private Optional<Map<String, Object>> call(ScriptRuntime runtime, JsonMapper json,
            Map<String, Object> event) {
            Value jsonObject = context.getBindings("js").getMember("JSON");
            ScriptRuntime.Call call = runtime.start(context);
            try {
                Value input = jsonObject.invokeMember("parse", json.writeValueAsString(event));
                Value output = apply.execute(input);
                Value result = output == null || output.isNull() ? input : output;
                String text = jsonObject.invokeMember("stringify", result).asString();
                runtime.end(call);
                return Optional.of(json.readValue(text, MAP));
            } catch (RuntimeException e) {
                boolean timeout = runtime.end(call);
                LOGGER.warn("Pseudonymization {} held back an event of {}: {}", id, event.get("app"),
                    timeout ? "timeout" : e.getMessage());
                return Optional.empty();
            }
        }

        void close() {
            lock.lock();
            try {
                context.close();
            } finally {
                lock.unlock();
            }
        }
    }
}
