package de.gbv.logguy.server.search;

import java.util.Map;
import java.util.function.Function;

import de.gbv.logguy.server.search.QueryLexer.Kind;
import de.gbv.logguy.server.search.QueryLexer.Token;

/**
 * Translates a query in Solr syntax with the declared field names into one with the Solr field names, e.g.
 * {@code level:ERROR} into {@code level_s:ERROR}. The query is split by {@link QueryLexer} like Solr splits it, so
 * every name Solr would read as a field name is translated. Unknown fields, internal fields like {@code _query_},
 * escapes in field names and local parameters like {@code {!join}} are rejected, so a query cannot reach fields or
 * query parsers the user should not use.
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
     * @throws SearchException if the query uses unknown fields, local parameters or syntax Solr cannot read
     */
    public static String translate(String query, Function<String, String> fields) {
        if (query == null || query.isBlank()) {
            return "*:*";
        }
        StringBuilder out = new StringBuilder(query.length() + 16);
        for (Token token : QueryLexer.tokenize(query)) {
            out.append(token.kind() == Kind.FIELD ? field(token.text(), fields) : token.text());
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
        if (solrField.startsWith("_")) {
            throw new SearchException("Das Feld „" + name + "“ ist intern und in der Suche nicht erlaubt.");
        }
        return solrField;
    }
}
