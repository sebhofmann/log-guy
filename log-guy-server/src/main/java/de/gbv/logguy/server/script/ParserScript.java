package de.gbv.logguy.server.script;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

/**
 * A checked parser script: name, code, version and declared fields. Create a {@link Parser} per thread to run it.
 *
 * @param name name of the script, e.g. {@code mycore-log4j}
 * @param code the JavaScript module
 * @param version short hash of the code, stored with every document as {@code parser_version}
 * @param fields the declared fields
 * @param hasIsStart whether the script exports {@code isStart}; without it every line is an event
 */
public record ParserScript(String name, String code, String version, Map<String, FieldDefinition> fields,
    boolean hasIsStart) {

    private static final Pattern FIELD_NAME = Pattern.compile("[a-z][a-z0-9_]{0,49}");

    /** Field names reserved for the fixed fields. */
    private static final java.util.Set<String> RESERVED = java.util.Set.of("id", "app", "host", "file", "offset",
        "ts", "message", "parse_error", "parser_version");

    /**
     * Loads and checks a parser script.
     *
     * @param runtime the script runtime
     * @param name name of the script
     * @param code the JavaScript module
     * @return the checked script
     * @throws ScriptException if the module cannot be evaluated or misses required exports
     */
    public static ParserScript load(ScriptRuntime runtime, String name, String code) throws ScriptException {
        try (Context context = runtime.newContext()) {
            Value exports = context.eval(ScriptRuntime.module("parser/" + name + ".js", code));
            Value parse = exports.getMember("parse");
            if (parse == null || !parse.canExecute()) {
                throw new ScriptException(name + ": export parse(text) is missing", null);
            }
            Value isStart = exports.getMember("isStart");
            if (isStart != null && !isStart.isNull() && !isStart.canExecute()) {
                throw new ScriptException(name + ": export isStart is not a function", null);
            }
            Value fieldsValue = exports.getMember("fields");
            if (fieldsValue == null || !fieldsValue.hasMembers()) {
                throw new ScriptException(name + ": export fields is missing", null);
            }
            Map<String, FieldDefinition> fields = new LinkedHashMap<>();
            for (String field : fieldsValue.getMemberKeys()) {
                if (!FIELD_NAME.matcher(field).matches() || RESERVED.contains(field)) {
                    throw new ScriptException(name + ": invalid field name " + field, null);
                }
                Value definition = fieldsValue.getMember(field);
                Value type = definition.getMember("type");
                if (type == null || !type.isString()) {
                    throw new ScriptException(name + ": field " + field + " has no type", null);
                }
                FieldType fieldType;
                try {
                    fieldType = FieldType.of(type.asString());
                } catch (IllegalArgumentException e) {
                    throw new ScriptException(name + ": field " + field + " has unknown type " + type.asString(), e);
                }
                Value facet = definition.getMember("facet");
                fields.put(field, new FieldDefinition(field, fieldType, facet != null && facet.isBoolean()
                    && facet.asBoolean()));
            }
            return new ParserScript(name, code, version(code), Map.copyOf(fields),
                isStart != null && !isStart.isNull());
        } catch (PolyglotException e) {
            throw new ScriptException(name + ": " + e.getMessage(), e);
        }
    }

    private static String version(String code) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
