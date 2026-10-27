package de.gbv.logguy.server.pseudonym;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.gbv.logguy.server.search.QueryLexer;
import de.gbv.logguy.server.search.QueryLexer.Kind;
import de.gbv.logguy.server.search.QueryLexer.Token;
import de.gbv.logguy.server.search.SearchException;

/**
 * Translates pseudonyms in pseudonymized queries and filters, from the MCP tools or from users who may only read
 * pseudonymized, back to the real values. On protected fields, the {@code meta.fields} of the pseudonymization
 * scripts, only pseudonyms of the session are accepted, so nobody can find out by guessing whether a certain user
 * appears in the logs.
 * <p>
 * Queries are split by the same {@link QueryLexer} that {@link de.gbv.logguy.server.search.QueryTranslator} uses
 * afterwards, so both always agree on which tokens are field names and which are values.
 */
public final class PseudonymQueries {

    private PseudonymQueries() {
    }

    /**
     * Checks a query and replaces the pseudonyms of the session with their real values. A term that is a pseudonym
     * becomes a phrase with the real value, pseudonyms in phrases are replaced inside the phrase.
     *
     * @param query the pseudonymized query, may be {@code null}
     * @param protectedFields fields that only accept pseudonyms
     * @param session the pseudonyms of the session
     * @return the query with real values
     * @throws SearchException if a protected field is used with something other than a known pseudonym, or the query
     *     uses syntax the search does not allow
     */
    public static String query(String query, Set<String> protectedFields, Pseudonyms session) {
        if (query == null || query.isBlank()) {
            return query;
        }
        List<Token> tokens = QueryLexer.tokenize(query);
        Set<Integer> checked = new HashSet<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token field = tokens.get(i);
            if (field.kind() == Kind.FIELD && protectedFields.contains(field.text())) {
                checked.add(checkProtected(tokens, i, session));
            }
        }
        StringBuilder out = new StringBuilder(query.length() + 16);
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (checked.contains(i) || token.kind() == Kind.TERM && session.isPseudonym(token.value())) {
                out.append(phrase(session.real(token.value()).orElseThrow()));
            } else if (token.kind() == Kind.QUOTED) {
                out.append(session.resolve(token.text(), PseudonymQueries::escape));
            } else {
                out.append(token.text());
            }
        }
        return out.toString();
    }

    /**
     * Checks the value of a protected field: a single term or phrase with a pseudonym of the session, optionally
     * boosted, but without groups, wildcards, ranges, regular expressions or fuzzy search.
     *
     * @return the index of the value
     */
    private static int checkProtected(List<Token> tokens, int field, Pseudonyms session) {
        String name = tokens.get(field).text();
        int value = QueryLexer.value(tokens, field);
        Token token = value < tokens.size() ? tokens.get(value) : null;
        if (token == null || token.kind() != Kind.TERM && token.kind() != Kind.QUOTED
            || !session.isPseudonym(token.value())) {
            throw new SearchException(clearValue(name));
        }
        for (int i = QueryLexer.next(tokens, value); i < tokens.size(); i = QueryLexer.next(tokens, i)) {
            Kind kind = tokens.get(i).kind();
            if (kind == Kind.SLOP) {
                throw new SearchException(clearValue(name));
            }
            if (kind != Kind.BOOST) {
                break;
            }
        }
        return value;
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

    private static String phrase(String value) {
        return "\"" + escape(value) + "\"";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
