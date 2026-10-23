package de.gbv.logguy.server.pseudonym;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.gbv.logguy.server.search.SearchException;

/**
 * Translates pseudonyms in pseudonymized queries and filters, from the MCP tools or from users who may only read
 * pseudonymized, back to the real values. On protected fields, the {@code meta.fields} of the pseudonymization
 * scripts, only pseudonyms of the session are accepted, so nobody can find out by guessing whether a certain user
 * appears in the logs.
 */
public final class PseudonymQueries {

    // a field name followed by a colon, the value is a quoted phrase, a group or a single term
    private static final Pattern FIELD_TERM =
        Pattern.compile(
            "(?<![\\w.\\\\])([\\p{L}_][\\w.]*)\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|\\(|\\[|\\{|[^\\s()\\[\\]{}]+)");

    private static final Pattern QUOTED = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    private PseudonymQueries() {
    }

    /**
     * Checks a query and replaces the pseudonyms of the session with their real values, quoted as Solr phrases.
     *
     * @param query the pseudonymized query, may be {@code null}
     * @param protectedFields fields that only accept pseudonyms
     * @param session the pseudonyms of the session
     * @return the query with real values
     * @throws SearchException if a protected field is used with something other than a known pseudonym
     */
    public static String query(String query, Set<String> protectedFields, Pseudonyms session) {
        if (query == null || query.isBlank()) {
            return query;
        }
        Matcher matcher = FIELD_TERM.matcher(query);
        while (matcher.find()) {
            if (protectedFields.contains(matcher.group(1))) {
                String value = unquote(matcher.group(2));
                if (!session.isPseudonym(value)) {
                    throw new SearchException(clearValue(matcher.group(1)));
                }
            }
        }
        StringBuilder out = new StringBuilder();
        Matcher quoted = QUOTED.matcher(query);
        int last = 0;
        while (quoted.find()) {
            out.append(session.resolve(query.substring(last, quoted.start()), PseudonymQueries::phrase));
            out.append(session.resolve(quoted.group(), PseudonymQueries::escape));
            last = quoted.end();
        }
        out.append(session.resolve(query.substring(last), PseudonymQueries::phrase));
        return out.toString();
    }

    /**
     * Checks filters and replaces the pseudonyms of the session with their real values.
     *
     * @param filters values per field, may be {@code null}
     * @param protectedFields fields that only accept pseudonyms
     * @param session the pseudonyms of the session
     * @return the filters with real values
     * @throws SearchException if a protected field has a value that is not a known pseudonym
     */
    public static Map<String, List<String>> filters(Map<String, List<String>> filters, Set<String> protectedFields,
        Pseudonyms session) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (filters == null) {
            return result;
        }
        filters.forEach((field, values) -> {
            List<String> real = new ArrayList<>();
            for (String value : values == null ? List.<String>of() : values) {
                if (session.isPseudonym(value)) {
                    real.add(session.real(value).orElseThrow());
                } else if (protectedFields.contains(field)) {
                    throw new SearchException(clearValue(field));
                } else {
                    real.add(value);
                }
            }
            result.put(field, real);
        });
        return result;
    }

    private static String clearValue(String field) {
        return "Auf dem Feld „" + field + "“ sind nur Pseudonyme dieser Sitzung erlaubt, z. B. aus einem früheren "
            + "Suchergebnis.";
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return value;
    }

    private static String phrase(String value) {
        return "\"" + escape(value) + "\"";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
