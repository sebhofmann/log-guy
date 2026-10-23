package de.gbv.logguy.server.pseudonym;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pseudonyms of one session. The same value gets the same pseudonym within the session, a new session starts
 * counting again. The mapping lives only in memory.
 */
public class Pseudonyms {

    /** A pseudonym: {@code $}, the category and a number, e.g. {@code $Nutzer1}. */
    public static final Pattern PSEUDONYM = Pattern.compile("\\$(\\p{L}[\\p{L}_]*)(\\d+)(?![\\p{L}\\d_])");

    private static final Pattern CATEGORY = Pattern.compile("\\p{L}[\\p{L}_]*");

    private final Map<String, Map<String, String>> byCategory = new HashMap<>();

    private final Map<String, String> real = new HashMap<>();

    /**
     * The pseudonym of a value, created on first use.
     *
     * @param category e.g. {@code Nutzer}, {@code IP}
     * @param value the real value
     * @return the pseudonym, or the value itself if it is empty or already a pseudonym
     * @throws IllegalArgumentException if the category is not made of letters
     */
    public synchronized String pseudonym(String category, String value) {
        if (category == null || !CATEGORY.matcher(category).matches()) {
            throw new IllegalArgumentException("invalid category " + category);
        }
        if (value == null || value.isEmpty() || real.containsKey(value)) {
            return value;
        }
        Map<String, String> values = byCategory.computeIfAbsent(category, c -> new HashMap<>());
        String existing = values.get(value);
        if (existing != null) {
            return existing;
        }
        String pseudonym = "$" + category + (values.size() + 1);
        values.put(value, pseudonym);
        real.put(pseudonym, value);
        return pseudonym;
    }

    /**
     * The real value of a pseudonym of this session.
     *
     * @param pseudonym e.g. {@code $Nutzer1}
     * @return the value, empty if the pseudonym is unknown
     */
    public synchronized Optional<String> real(String pseudonym) {
        return Optional.ofNullable(real.get(pseudonym));
    }

    /**
     * Checks whether a text is a pseudonym of this session.
     *
     * @param text the text
     * @return true if the session created it
     */
    public synchronized boolean isPseudonym(String text) {
        return real.containsKey(text);
    }

    /**
     * Replaces the known pseudonyms in a text with their real values.
     *
     * @param text the text, may be {@code null}
     * @param quote how a real value is written, e.g. escaped for Solr
     * @return the text with real values
     */
    public synchronized String resolve(String text, UnaryOperator<String> quote) {
        if (text == null || text.indexOf('$') < 0) {
            return text;
        }
        Matcher matcher = PSEUDONYM.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = real.get(matcher.group());
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group()
                : quote.apply(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Number of pseudonyms per category.
     *
     * @return the counts, sorted by category
     */
    public synchronized Map<String, Integer> counts() {
        Map<String, Integer> counts = new TreeMap<>();
        byCategory.forEach((category, values) -> counts.put(category, values.size()));
        return counts;
    }
}
