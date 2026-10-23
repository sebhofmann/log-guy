package de.gbv.logguy.server.script;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

/**
 * A parser script running in its own context. Not thread safe, every indexer thread has its own.
 */
public final class Parser implements AutoCloseable {

    private final ParserScript script;

    private final ScriptRuntime runtime;

    private final Context context;

    private final Value parse;

    private final Value isStart;

    /**
     * Creates the parser.
     *
     * @param runtime the script runtime
     * @param script the checked script
     * @throws ScriptException if the module cannot be evaluated
     */
    public Parser(ScriptRuntime runtime, ParserScript script) throws ScriptException {
        this.runtime = runtime;
        this.script = script;
        this.context = runtime.newContext();
        try {
            Value exports = context.eval(ScriptRuntime.module("parser/" + script.name() + ".js", script.code()));
            this.parse = exports.getMember("parse");
            this.isStart = script.hasIsStart() ? exports.getMember("isStart") : null;
        } catch (PolyglotException e) {
            context.close();
            throw new ScriptException(script.name() + ": " + e.getMessage(), e);
        }
    }

    /**
     * The script this parser runs.
     *
     * @return the script
     */
    public ParserScript script() {
        return script;
    }

    /**
     * Whether a line starts a new event. Without {@code isStart} in the script, every line does. An error counts as
     * start, so a broken script does not merge the whole file into one event.
     *
     * @param line the line without line break
     * @return true if a new event starts
     */
    public boolean isStart(String line) {
        if (isStart == null) {
            return true;
        }
        ScriptRuntime.Call call = runtime.start(context);
        try {
            return isStart.execute(line).asBoolean();
        } catch (PolyglotException | ClassCastException e) {
            return true;
        } finally {
            runtime.end(call);
        }
    }

    /**
     * Parses an event.
     *
     * @param text the lines of the event, joined with {@code \n}
     * @return the result, with {@link ParsedEvent#error()} set if the script failed
     */
    public ParsedEvent parse(String text) {
        ScriptRuntime.Call call = runtime.start(context);
        Value result;
        try {
            result = parse.execute(text);
        } catch (PolyglotException e) {
            boolean timeout = runtime.end(call);
            if (e.isInterrupted() && !timeout) {
                // the watchdog was late and hit the next call, try once more
                return parse(text);
            }
            return failed(text, timeout ? "timeout" : e.getMessage());
        }
        runtime.end(call);
        try {
            return convert(text, result);
        } catch (PolyglotException | ClassCastException | IllegalArgumentException e) {
            return failed(text, "invalid result: " + e.getMessage());
        }
    }

    private static ParsedEvent failed(String text, String error) {
        return new ParsedEvent(null, text, Map.of(), error == null ? "error" : error);
    }

    private ParsedEvent convert(String text, Value result) {
        if (result == null || result.isNull() || !result.hasMembers()) {
            return new ParsedEvent(null, text, Map.of(), null);
        }
        Instant ts = toInstant(result.getMember("ts"));
        Value messageValue = result.getMember("message");
        String message = messageValue == null || messageValue.isNull() ? text : messageValue.asString();
        Map<String, Object> fields = new HashMap<>();
        for (FieldDefinition field : script.fields().values()) {
            Object value = toJava(result.getMember(field.name()), field.type());
            if (value != null) {
                fields.put(field.name(), value);
            }
        }
        return new ParsedEvent(ts, message, fields, null);
    }

    /**
     * Returns the names of fields in a result that are not declared, for the parser lab.
     *
     * @param text the event text
     * @return undeclared field names
     */
    public List<String> undeclaredFields(String text) {
        Value result = parse.execute(text);
        List<String> undeclared = new ArrayList<>();
        if (result != null && result.hasMembers()) {
            for (String key : result.getMemberKeys()) {
                if (!key.equals("ts") && !key.equals("message") && !script.fields().containsKey(key)) {
                    undeclared.add(key);
                }
            }
        }
        return undeclared;
    }

    private static Instant toInstant(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return Instant.ofEpochMilli(value.asLong());
        }
        if (value.isInstant()) {
            return value.asInstant();
        }
        if (value.isString()) {
            return Instant.parse(value.asString());
        }
        throw new IllegalArgumentException("ts is neither number, date nor string");
    }

    private static Object toJava(Value value, FieldType type) {
        if (value == null || value.isNull()) {
            return null;
        }
        return switch (type) {
            case STRING, TEXT -> value.isString() ? value.asString() : value.toString();
            case STRINGS -> {
                if (!value.hasArrayElements()) {
                    yield List.of(value.isString() ? value.asString() : value.toString());
                }
                List<String> list = new ArrayList<>((int) value.getArraySize());
                for (long i = 0; i < value.getArraySize(); i++) {
                    Value element = value.getArrayElement(i);
                    if (!element.isNull()) {
                        list.add(element.isString() ? element.asString() : element.toString());
                    }
                }
                yield list.isEmpty() ? null : list;
            }
            case INT -> value.isNumber() ? value.asInt() : Integer.parseInt(value.asString());
            case LONG -> value.isNumber() ? value.asLong() : Long.parseLong(value.asString());
            case DOUBLE -> value.isNumber() ? value.asDouble() : Double.parseDouble(value.asString());
            case BOOL -> value.isBoolean() ? value.asBoolean() : Boolean.parseBoolean(value.asString());
            case DATE -> toInstant(value);
        };
    }

    @Override
    public void close() {
        context.close();
    }
}
