package de.gbv.logguy.server.alert;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyObject;

import de.gbv.logguy.server.config.AppGroups;
import de.gbv.logguy.server.script.ScriptException;
import de.gbv.logguy.server.script.ScriptKind;
import de.gbv.logguy.server.script.ScriptListener;
import de.gbv.logguy.server.script.ScriptRuntime;
import de.gbv.logguy.server.script.ScriptStore;
import de.gbv.logguy.server.script.ScriptStore.ScriptId;

/**
 * The active alert rules: every file in {@code scripts/rule/}. A rule that cannot be loaded is reported and left
 * out, the others keep running.
 * <p>
 * GraalJS contexts are single threaded, so every thread gets its own {@link Runner}.
 */
public class AlertRules implements ScriptListener {

    private static final Logger LOGGER = LogManager.getLogger();

    private final ScriptRuntime runtime;

    private final AtomicReference<Generation> current = new AtomicReference<>(new Generation(List.of(), List.of()));

    private final Map<String, AtomicLong> checkErrors = new ConcurrentHashMap<>();

    private final Map<String, String> lastErrors = new ConcurrentHashMap<>();

    private final Supplier<AppGroups> groups;

    /**
     * Creates the rules and loads the active scripts.
     *
     * @param runtime the script runtime
     * @param store the scripts
     * @param groups the application groups as they are now
     */
    public AlertRules(ScriptRuntime runtime, ScriptStore store, Supplier<AppGroups> groups) {
        this.runtime = runtime;
        this.groups = groups;
        load(store.active());
    }

    @Override
    public void changed(ScriptKind kind, ScriptStore store) {
        if (kind == ScriptKind.RULE) {
            load(store.active());
        }
    }

    /**
     * Loads the rules.
     *
     * @param active all active scripts
     * @return errors of rules that could not be loaded
     */
    public synchronized List<String> load(Map<ScriptId, String> active) {
        Map<String, String> code = new TreeMap<>();
        active.forEach((id, source) -> {
            if (id.kind() == ScriptKind.RULE) {
                code.put(id.name(), source);
            }
        });
        List<RuleDefinition> rules = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        code.forEach((name, source) -> {
            try {
                rules.add(define(runtime, name, source));
            } catch (ScriptException e) {
                errors.add(e.getMessage());
            }
        });
        if (!errors.isEmpty()) {
            LOGGER.error("alert rules failed and are left out: {}", errors);
        }
        LOGGER.info("alert rules {}", rules.stream().map(RuleDefinition::label).toList());
        current.set(new Generation(List.copyOf(rules), List.copyOf(errors)));
        return errors;
    }

    /**
     * Reads a rule without activating it.
     *
     * @param runtime the script runtime
     * @param name name of the script
     * @param code the module
     * @return the rule
     * @throws ScriptException if the module is no valid rule
     */
    public static RuleDefinition define(ScriptRuntime runtime, String name, String code) throws ScriptException {
        try (Context context = runtime.newContext()) {
            Value exports = runtime.evalModule(context, "rule/" + name, code);
            return RuleDefinition.read(name, code, ScriptStore.hash(code), exports);
        } catch (PolyglotException e) {
            throw new ScriptException("rule/" + name + ": " + e.getMessage(), e);
        }
    }

    /**
     * The loaded rules.
     *
     * @return the rules, sorted by name
     */
    public List<RuleDefinition> rules() {
        return current.get().rules();
    }

    /**
     * Errors of rules that could not be loaded.
     *
     * @return the errors
     */
    public List<String> errors() {
        return current.get().errors();
    }

    /**
     * Errors of {@code check(event)} and {@code message(event, count)} since the start, by rule.
     *
     * @return the counts
     */
    public Map<String, Long> checkErrors() {
        Map<String, Long> result = new TreeMap<>();
        checkErrors.forEach((name, count) -> result.put(name, count.get()));
        return result;
    }

    /**
     * The last error of each rule since the start.
     *
     * @return the errors by rule
     */
    public Map<String, String> lastErrors() {
        return new TreeMap<>(lastErrors);
    }

    /**
     * Creates a runner for the calling thread that follows the active rules.
     *
     * @return the runner, to be closed by the thread
     */
    public Runner runner() {
        return new Runner(runtime, () -> current.get().rules(), groups, this::failed);
    }

    /**
     * Creates a runner for rules that are not active, e.g. for a test.
     *
     * @param runtime the script runtime
     * @param rules the rules
     * @param groups the application groups
     * @param errors called with rule and message for every failed call
     * @return the runner, to be closed by the thread
     */
    public static Runner runner(ScriptRuntime runtime, List<RuleDefinition> rules, AppGroups groups,
        ErrorListener errors) {
        List<RuleDefinition> fixed = List.copyOf(rules);
        return new Runner(runtime, () -> fixed, () -> groups, errors);
    }

    private void failed(RuleDefinition rule, String error) {
        long count = checkErrors.computeIfAbsent(rule.name(), name -> new AtomicLong()).incrementAndGet();
        lastErrors.put(rule.name(), error);
        if (Long.bitCount(count) == 1) {
            // 1, 2, 4, 8, ... so a rule failing on every event does not flood the log
            LOGGER.warn("alert rule {} failed {} times, last: {}", rule.label(), count, error);
        }
    }

    /**
     * Converts an event into a JavaScript object for {@code check(event)}: lists become arrays, times ISO strings.
     *
     * @param event {@code app}, {@code host}, {@code ts}, {@code message} and the fields
     * @return the object
     */
    static ProxyObject proxy(Map<String, Object> event) {
        Map<String, Object> values = new HashMap<>();
        event.forEach((name, value) -> values.put(name, value(value)));
        return ProxyObject.fromMap(values);
    }

    private static Object value(Object value) {
        if (value instanceof List<?> list) {
            return ProxyArray.fromList(new ArrayList<>(list.stream().map(AlertRules::value).toList()));
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        return value;
    }

    private record Generation(List<RuleDefinition> rules, List<String> errors) {
    }

    /**
     * Receives errors of rule calls.
     */
    @FunctionalInterface
    public interface ErrorListener {

        /**
         * Called after a call of a rule failed.
         *
         * @param rule the rule
         * @param error the error
         */
        void failed(RuleDefinition rule, String error);
    }

    /**
     * The rules of one thread, with their own script contexts. Not thread safe.
     */
    public static final class Runner implements AutoCloseable {

        private final ScriptRuntime runtime;

        private final Supplier<List<RuleDefinition>> source;

        private final Supplier<AppGroups> groups;

        private final ErrorListener errors;

        private List<RuleDefinition> loaded;

        private AppGroups loadedGroups;

        private final Map<String, Loaded> contexts = new HashMap<>();

        private final Map<String, List<Loaded>> byApp = new HashMap<>();

        private Runner(ScriptRuntime runtime, Supplier<List<RuleDefinition>> source, Supplier<AppGroups> groups,
            ErrorListener errors) {
            this.runtime = runtime;
            this.source = source;
            this.groups = groups;
            this.errors = errors;
        }

        private List<Loaded> forApp(String app) {
            List<RuleDefinition> now = source.get();
            if (now != loaded) {
                close();
                for (RuleDefinition rule : now) {
                    Context context = runtime.newContext();
                    try {
                        Value exports = runtime.evalModule(context, "rule/" + rule.name(), rule.code());
                        contexts.put(rule.name(), new Loaded(rule, context, member(exports, "check"),
                            member(exports, "message")));
                    } catch (ScriptException | PolyglotException e) {
                        context.close();
                        errors.failed(rule, e.getMessage());
                    }
                }
                loaded = now;
            }
            AppGroups nowGroups = groups.get();
            if (nowGroups != loadedGroups) {
                byApp.clear();
                loadedGroups = nowGroups;
            }
            return byApp.computeIfAbsent(app, a -> contexts.values().stream()
                .filter(l -> l.rule.appliesTo(a, nowGroups)).toList());
        }

        private static Value member(Value exports, String name) {
            Value member = exports.getMember(name);
            return member != null && member.canExecute() ? member : null;
        }

        /**
         * Runs {@code check(event)} of all hit and threshold rules of the application.
         *
         * @param app the application
         * @param event {@code app}, {@code host}, {@code ts}, {@code message} and the fields
         * @return the rules whose check returned {@code true}
         */
        public List<RuleDefinition> check(String app, Map<String, Object> event) {
            List<Loaded> rules = forApp(app);
            if (rules.isEmpty()) {
                return List.of();
            }
            List<RuleDefinition> matched = new ArrayList<>(1);
            ProxyObject input = null;
            for (Loaded rule : rules) {
                if (rule.check == null) {
                    continue;
                }
                if (input == null) {
                    input = proxy(event);
                }
                ScriptRuntime.Call call = runtime.start(rule.context);
                try {
                    Value result = rule.check.execute(input);
                    runtime.end(call);
                    if (result.isBoolean() ? result.asBoolean() : !result.isNull() && truthy(result)) {
                        matched.add(rule.rule);
                    }
                } catch (RuntimeException e) {
                    boolean timeout = runtime.end(call);
                    errors.failed(rule.rule, timeout ? "timeout" : e.getMessage());
                }
            }
            return matched;
        }

        private static boolean truthy(Value value) {
            if (value.isNumber()) {
                return value.asDouble() != 0;
            }
            if (value.isString()) {
                return !value.asString().isEmpty();
            }
            return true;
        }

        /**
         * Runs {@code message(event, count)} of a rule.
         *
         * @param rule the rule
         * @param event the event, for silence rules only {@code app}
         * @param count the count for the text
         * @return the text, {@code null} if the rule has no message or it failed
         */
        public String message(RuleDefinition rule, Map<String, Object> event, long count) {
            if (!rule.hasMessage()) {
                return null;
            }
            forApp(String.valueOf(event.get("app")));
            Loaded loadedRule = contexts.get(rule.name());
            if (loadedRule == null || loadedRule.message == null || !loadedRule.rule.version().equals(rule
                .version())) {
                return null;
            }
            ScriptRuntime.Call call = runtime.start(loadedRule.context);
            try {
                Value result = loadedRule.message.execute(proxy(event), count);
                runtime.end(call);
                return result.isNull() ? null : result.isString() ? result.asString() : result.toString();
            } catch (RuntimeException e) {
                boolean timeout = runtime.end(call);
                errors.failed(rule, timeout ? "timeout" : e.getMessage());
                return null;
            }
        }

        @Override
        public void close() {
            contexts.values().forEach(l -> l.context.close());
            contexts.clear();
            byApp.clear();
            loaded = null;
            loadedGroups = null;
        }

        private record Loaded(RuleDefinition rule, Context context, Value check, Value message) {
        }
    }
}
