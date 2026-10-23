package de.gbv.logguy.server.script;

import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

import de.gbv.logguy.server.alert.RuleDefinition;

/**
 * What a check of a rule, lifecycle filter or pseudonymization module found out.
 *
 * @param apps the application patterns from {@code meta.apps}
 */
public record ModuleInfo(List<String> apps) {

    /**
     * Checks a module of a kind other than parser: it must evaluate, export {@code meta} with {@code apps} and the
     * functions the kind needs.
     *
     * @param runtime the script runtime
     * @param kind the kind
     * @param name the name of the script
     * @param code the module
     * @return the information from {@code meta}
     * @throws ScriptException if the module is not valid for the kind
     */
    public static ModuleInfo check(ScriptRuntime runtime, ScriptKind kind, String name, String code)
        throws ScriptException {
        String label = kind.dir() + "/" + name;
        try (Context context = runtime.newContext()) {
            Value exports = context.eval(ScriptRuntime.module(label + ".js", code));
            Value meta = exports.getMember("meta");
            if (meta == null || !meta.hasMembers()) {
                throw new ScriptException(label + ": export meta is missing", null);
            }
            List<String> apps = strings(meta.getMember("apps"));
            if (apps.isEmpty()) {
                throw new ScriptException(label + ": meta.apps must list at least one application", null);
            }
            switch (kind) {
                case RULE -> RuleDefinition.read(name, code, "", exports);
                case LIFECYCLE -> {
                    if (!function(exports, "line") && !function(exports, "fields")) {
                        throw new ScriptException(label + ": export line(text) or fields(event) is missing", null);
                    }
                }
                case PSEUDONYM -> {
                    if (!function(exports, "apply")) {
                        throw new ScriptException(label + ": export apply(event) is missing", null);
                    }
                }
                case PARSER -> throw new IllegalArgumentException("parsers are checked by ParserScript");
            }
            return new ModuleInfo(apps);
        } catch (PolyglotException e) {
            throw new ScriptException(label + ": " + e.getMessage(), e);
        }
    }

    private static boolean function(Value exports, String name) {
        Value member = exports.getMember(name);
        return member != null && member.canExecute();
    }

    /**
     * Converts a JavaScript array of strings.
     *
     * @param value the array, may be {@code null}
     * @return the strings, empty if the value is no array
     */
    static List<String> strings(Value value) {
        List<String> list = new ArrayList<>();
        if (value != null && value.hasArrayElements()) {
            for (long i = 0; i < value.getArraySize(); i++) {
                Value element = value.getArrayElement(i);
                if (element.isString()) {
                    list.add(element.asString());
                }
            }
        }
        return List.copyOf(list);
    }
}
