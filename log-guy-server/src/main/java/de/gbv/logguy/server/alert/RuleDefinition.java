package de.gbv.logguy.server.alert;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;

import org.graalvm.polyglot.Value;

import de.gbv.logguy.server.config.AppGroups;
import de.gbv.logguy.server.script.ScriptException;

/**
 * An alert rule with what its {@code meta} says.
 *
 * @param name name of the script, identifies counters and cooldown as long as it stays the same
 * @param code the module
 * @param version short hash of the code
 * @param apps application patterns from {@code meta.apps}
 * @param type hit, threshold or silence
 * @param cooldown time after a notification in which further hits are collected, {@code null} for the default
 * @param targets targets from {@code meta.notify}, e.g. {@code mail:it}
 * @param count events of a threshold
 * @param within time window of a threshold or {@code null}
 * @param per field a threshold is counted by besides the application, {@code null} for the application only
 * @param absentFor silence after which a silence rule fires, {@code null} for other rules
 * @param hasMessage whether the rule exports {@code message(event, count)}
 */
public record RuleDefinition(String name, String code, String version, List<String> apps, Type type,
    Duration cooldown, List<String> targets, int count, Duration within, String per, Duration absentFor,
    boolean hasMessage) {

    private static final Pattern DURATION = Pattern.compile("(\\d+)(ms|s|m|h|d)");

    /**
     * Kind of a rule.
     */
    public enum Type {
        /** Every matching event. */
        HIT,
        /** At least {@code count} matching events within {@code within}. */
        THRESHOLD,
        /** No event of an application for {@code absentFor}. */
        ABSENCE
    }

    /**
     * Whether the rule applies to an application.
     *
     * @param app the application
     * @param groups the application groups, for patterns like {@code @mir}
     * @return true if a pattern of {@code meta.apps} matches
     */
    public boolean appliesTo(String app, AppGroups groups) {
        return groups.matchesAny(apps, app);
    }

    /**
     * Name and version, e.g. {@code pool-voll@3f2a9c01b2d4}.
     *
     * @return the label
     */
    public String label() {
        return name + "@" + version;
    }

    /**
     * Reads the rule from the exports of its module.
     *
     * @param name name of the script
     * @param code the module
     * @param version short hash of the code
     * @param exports the evaluated module
     * @return the rule
     * @throws ScriptException if {@code meta} is invalid
     */
    public static RuleDefinition read(String name, String code, String version, Value exports) throws ScriptException {
        String label = "rule/" + name;
        Value meta = exports.getMember("meta");
        if (meta == null || !meta.hasMembers()) {
            throw new ScriptException(label + ": export meta is missing", null);
        }
        List<String> apps = strings(meta.getMember("apps"));
        if (apps.isEmpty()) {
            throw new ScriptException(label + ": meta.apps must list at least one application", null);
        }
        Duration cooldown = duration(label, "cooldown", meta.getMember("cooldown"));
        List<String> targets = strings(meta.getMember("notify"));
        Duration absentFor = duration(label, "absentFor", meta.getMember("absentFor"));
        Value threshold = meta.getMember("threshold");
        boolean hasThreshold = threshold != null && !threshold.isNull();
        boolean hasCheck = function(exports, "check");
        if (hasThreshold && absentFor != null) {
            throw new ScriptException(label + ": meta.threshold and meta.absentFor exclude each other", null);
        }
        if (absentFor == null && !hasCheck) {
            throw new ScriptException(label + ": export check(event) is missing", null);
        }
        boolean hasMessage = function(exports, "message");
        if (absentFor != null) {
            return new RuleDefinition(name, code, version, apps, Type.ABSENCE, cooldown, targets, 0, null, null,
                absentFor, hasMessage);
        }
        if (!hasThreshold) {
            return new RuleDefinition(name, code, version, apps, Type.HIT, cooldown, targets, 0, null, null, null,
                hasMessage);
        }
        Value count = threshold.getMember("count");
        if (count == null || !count.fitsInInt() || count.asInt() < 1) {
            throw new ScriptException(label + ": meta.threshold.count must be a number of at least 1", null);
        }
        Duration within = duration(label, "threshold.within", threshold.getMember("within"));
        if (within == null) {
            throw new ScriptException(label + ": meta.threshold.within is missing", null);
        }
        Value perValue = threshold.getMember("per");
        String per = perValue == null || perValue.isNull() ? null : perValue.asString();
        if (per != null && (per.isBlank() || per.equals("app"))) {
            per = null;
        }
        return new RuleDefinition(name, code, version, apps, Type.THRESHOLD, cooldown, targets, count.asInt(),
            within, per, null, hasMessage);
    }

    /**
     * Parses a duration like {@code 30s}, {@code 15m}, {@code 2h} or {@code 1d}.
     *
     * @param text the duration
     * @return the duration
     * @throws IllegalArgumentException if the text is no such duration
     */
    public static Duration parseDuration(String text) {
        Matcher matcher = DURATION.matcher(text.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("not a duration like 15m: " + text);
        }
        long amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2)) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            default -> Duration.ofDays(amount);
        };
    }

    /**
     * Formats a duration the way rules write it.
     *
     * @param duration the duration
     * @return e.g. {@code 15m}
     */
    public static String format(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds == 0) {
            return duration.toMillis() + "ms";
        }
        if (seconds % 86_400 == 0) {
            return seconds / 86_400 + "d";
        }
        if (seconds % 3600 == 0) {
            return seconds / 3600 + "h";
        }
        return seconds % 60 == 0 ? seconds / 60 + "m" : seconds + "s";
    }

    private static Duration duration(String label, String name, Value value) throws ScriptException {
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            Duration duration = parseDuration(value.isString() ? value.asString() : value.toString());
            if (duration.isZero()) {
                throw new IllegalArgumentException("must be longer than 0");
            }
            return duration;
        } catch (IllegalArgumentException e) {
            throw new ScriptException(label + ": meta." + name + ": " + e.getMessage(), e);
        }
    }

    private static boolean function(Value exports, String name) {
        Value member = exports.getMember(name);
        return member != null && member.canExecute();
    }

    private static List<String> strings(Value value) {
        if (value == null || !value.hasArrayElements()) {
            return List.of();
        }
        return LongStream.range(0, value.getArraySize()).mapToObj(value::getArrayElement)
            .filter(Value::isString).map(Value::asString).toList();
    }
}
