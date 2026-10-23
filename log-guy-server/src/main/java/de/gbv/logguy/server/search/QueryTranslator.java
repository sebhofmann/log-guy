package de.gbv.logguy.server.search;

import java.util.Map;
import java.util.function.Function;

/**
 * Translates a query in Solr syntax with the declared field names into one with the Solr field names, e.g.
 * {@code level:ERROR} into {@code level_s:ERROR}. Unknown fields and local parameters like {@code {!join}} are
 * rejected, so a query cannot reach fields or query parsers the user should not use.
 */
public final class QueryTranslator {

    private QueryTranslator() {
    }

    /**
     * Translates a query.
     *
     * @param query the query of the user, blank means all events
     * @param fields maps a declared field name to the Solr field, returns {@code null} for unknown names; it may throw
     *     a {@link SearchException} itself, e.g. for a field with different types
     * @return the query for Solr
     * @throws SearchException if the query uses unknown fields or local parameters
     */
    public static String translate(String query, Function<String, String> fields) {
        if (query == null || query.isBlank()) {
            return "*:*";
        }
        StringBuilder out = new StringBuilder(query.length() + 16);
        int length = query.length();
        int ranges = 0;
        int i = 0;
        while (i < length) {
            char c = query.charAt(i);
            if (c == '\\') {
                out.append(query, i, Math.min(i + 2, length));
                i += 2;
            } else if (c == '"') {
                int end = closing(query, i, '"');
                out.append(query, i, end);
                i = end;
            } else if (c == '/' && termStart(query, i)) {
                int end = closing(query, i, '/');
                out.append(query, i, end);
                i = end;
            } else if (c == '{' && i + 1 < length && query.charAt(i + 1) == '!') {
                throw new SearchException("Lokale Parameter wie {!…} sind in der Suche nicht erlaubt.");
            } else if (c == '[' || c == '{') {
                ranges++;
                out.append(c);
                i++;
            } else if (c == ']' || c == '}') {
                ranges = Math.max(0, ranges - 1);
                out.append(c);
                i++;
            } else if (ranges == 0 && isNameChar(c) && termStart(query, i)) {
                int end = i;
                while (end < length && isNameChar(query.charAt(end))) {
                    end++;
                }
                String name = query.substring(i, end);
                if (end < length && query.charAt(end) == ':') {
                    out.append(field(name, fields)).append(':');
                    i = end + 1;
                } else {
                    out.append(name);
                    i = end;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Translates with a fixed map of field names.
     *
     * @param query the query of the user
     * @param fields declared name to Solr field
     * @return the query for Solr
     */
    public static String translate(String query, Map<String, String> fields) {
        return translate(query, fields::get);
    }

    private static String field(String name, Function<String, String> fields) {
        if ("*".equals(name)) {
            return name;
        }
        String solrField = fields.apply(name);
        if (solrField == null) {
            throw new SearchException("Unbekanntes Feld „" + name + "“ in der Suche.");
        }
        return solrField;
    }

    private static int closing(String query, int start, char quote) {
        int i = start + 1;
        while (i < query.length()) {
            char c = query.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote) {
                return i + 1;
            } else {
                i++;
            }
        }
        throw new SearchException("Nicht geschlossenes " + quote + " in der Suche.");
    }

    private static boolean termStart(String query, int i) {
        if (i == 0) {
            return true;
        }
        char before = query.charAt(i - 1);
        return Character.isWhitespace(before) || before == '(' || before == '+' || before == '-' || before == '!';
    }

    private static boolean isNameChar(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '.'
            || c == '*';
    }
}
