package de.gbv.logguy.server.search;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a query into tokens exactly like the lexer of the Solr standard query parser
 * ({@code org.apache.solr.parser.QueryParser.jj} in Solr 9) does, so log-guy sees every field name Solr would see.
 * The search translates the field names of the tokens, the pseudonymized search checks the values of protected
 * fields on the same tokens. Concatenating the texts of all tokens gives the query again.
 * <p>
 * Solr reads a field name wherever a term is followed by a colon, whitespace and comments in between are skipped and
 * no whitespace is needed before the name: {@code (*:*)login_s:x}, {@code "x"login_s:x} and {@code a^2login_s:x}
 * all search {@code login_s}. Backslash escapes are removed from field names, so {@code lo\gin_s:x} searches
 * {@code login_s}. The lexer therefore marks every term before a colon as {@link Kind#FIELD} and rejects
 * <ul>
 * <li>escapes in field names,</li>
 * <li>field names starting with an underscore, these are internal to Solr like {@code _query_} and
 * {@code _val_},</li>
 * <li>a colon that does not follow a field name,</li>
 * <li>{@code *} as field name except in {@code *:*},</li>
 * <li>local parameters like {@code {!join}},</li>
 * <li>everything Solr would reject as a lexical error, like an open phrase or a {@code ]} without range.</li>
 * </ul>
 */
public final class QueryLexer {

    private final String query;

    private final List<Token> tokens = new ArrayList<>();

    private int pos;

    private QueryLexer(String query) {
        this.query = query;
    }

    /**
     * Splits and checks a query.
     *
     * @param query the query, not {@code null}
     * @return the tokens, their texts concatenated give the query
     * @throws SearchException if the query uses syntax the search does not allow or Solr cannot read
     */
    public static List<Token> tokenize(String query) {
        QueryLexer lexer = new QueryLexer(query);
        lexer.run();
        lexer.markFields();
        return List.copyOf(lexer.tokens);
    }

    private void run() {
        int length = query.length();
        while (pos < length) {
            char c = query.charAt(pos);
            if (isWhitespace(c)) {
                add(Kind.SKIP, pos + 1);
            } else if (c == '/' && next(1) == '*') {
                add(Kind.SKIP, comment());
            } else if (c == '/') {
                add(Kind.REGEXP, regexp());
            } else if (c == '"') {
                add(Kind.QUOTED, quoted());
            } else if (c == '(' || c == ')') {
                add(c == '(' ? Kind.OPEN : Kind.CLOSE, pos + 1);
            } else if (c == ':') {
                add(Kind.COLON, pos + 1);
            } else if (c == '^') {
                add(Kind.BOOST, boost());
            } else if (c == '~') {
                add(Kind.SLOP, slop());
            } else if (c == '{' && next(1) == '!') {
                throw new SearchException("Lokale Parameter wie {!…} sind in der Suche nicht erlaubt.");
            } else if (c == '[' || c == '{') {
                add(Kind.RANGE, range());
            } else if (c == '+' || c == '-' || c == '!') {
                add(Kind.OPERATOR, pos + (pos + 1 < length && isWhitespace(query.charAt(pos + 1)) ? 2 : 1));
            } else if (c == ']' || c == '}') {
                throw new SearchException("Unerwartetes „" + c + "“ ohne Bereich in der Suche.");
            } else {
                term();
            }
        }
    }

    private void add(Kind kind, int end) {
        tokens.add(new Token(kind, query.substring(pos, end)));
        pos = end;
    }

    private char next(int offset) {
        int i = pos + offset;
        return i < query.length() ? query.charAt(i) : 0;
    }

    /** A comment {@code /* … *}{@code /}, Solr allows nested comments. */
    private int comment() {
        int depth = 0;
        int i = pos;
        while (i + 1 < query.length()) {
            if (query.startsWith("/*", i)) {
                depth++;
                i += 2;
            } else if (query.startsWith("*/", i)) {
                depth--;
                i += 2;
                if (depth == 0) {
                    return i;
                }
            } else {
                i++;
            }
        }
        throw new SearchException("Nicht geschlossener Kommentar /* in der Suche.");
    }

    /**
     * A regular expression {@code /…/}. Solr takes the longest match of {@code "/" (~["*","/"] | "\\/")
     * (~["/"] | "\\/")* "/"}: it ends at the first slash without a backslash before it, or if there is none, at the
     * last slash with a backslash before it.
     */
    private int regexp() {
        int last = -1;
        for (int i = pos + 1; i < query.length(); i++) {
            if (query.charAt(i) != '/') {
                continue;
            }
            boolean escaped = query.charAt(i - 1) == '\\' && i - 1 > pos;
            if (escaped) {
                last = i + 1;
            } else if (i > pos + 1) {
                return i + 1;
            } else {
                break;
            }
        }
        if (last < 0) {
            throw new SearchException("Nicht geschlossener regulärer Ausdruck / in der Suche.");
        }
        return last;
    }

    /** A phrase {@code "…"}, a backslash escapes the next character. */
    private int quoted() {
        int i = pos + 1;
        while (i < query.length()) {
            char c = query.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '"') {
                return i + 1;
            } else {
                i++;
            }
        }
        throw new SearchException("Nicht geschlossenes \" in der Suche.");
    }

    /** {@code ^} and a number like {@code 2}, {@code 1.5} or {@code =3}, without whitespace in between. */
    private int boost() {
        int i = pos + 1;
        if (i < query.length() && query.charAt(i) == '=') {
            i++;
        }
        if (i < query.length() && query.charAt(i) == '-') {
            i++;
        }
        int end = number(i);
        if (end == i) {
            throw new SearchException("Nach ^ muss in der Suche eine Zahl stehen.");
        }
        return end;
    }

    /** {@code ~}, optionally followed by a number. */
    private int slop() {
        return number(pos + 1);
    }

    /** The end of {@code [0-9]+ ("." [0-9]+)?} starting at {@code start}, {@code start} if there is no number. */
    private int number(int start) {
        int i = digits(start);
        if (i > start && i + 1 < query.length() && query.charAt(i) == '.' && isDigit(query.charAt(i + 1))) {
            i = digits(i + 1);
        }
        return i;
    }

    private int digits(int start) {
        int i = start;
        while (i < query.length() && isDigit(query.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * A range {@code [a TO b]} or {@code {a TO b}}. Solr ends a range at the first {@code ]} or {@code }} outside a
     * quoted bound, either bracket ends either range.
     */
    private int range() {
        int i = pos + 1;
        while (i < query.length()) {
            char c = query.charAt(i);
            if (c == ']' || c == '}') {
                return i + 1;
            } else if (c == '"') {
                i = Math.max(rangeQuoted(i), goop(i));
            } else if (c == ' ') {
                i++;
            } else {
                i = goop(i);
            }
        }
        throw new SearchException("Nicht geschlossener Bereich in der Suche.");
    }

    /**
     * The end of a quoted bound of a range, {@code "\"" (~["\""] | "\\\"")+ "\""} as longest match, or
     * {@code start} if there is none.
     */
    private int rangeQuoted(int start) {
        int last = start;
        for (int i = start + 1; i < query.length(); i++) {
            if (query.charAt(i) != '"') {
                continue;
            }
            if (i == start + 1) {
                break;
            }
            if (query.charAt(i - 1) == '\\') {
                last = i + 1;
            } else {
                return i + 1;
            }
        }
        return last;
    }

    /** The end of an unquoted bound of a range, anything up to a space, {@code ]} or {@code }}. */
    private int goop(int start) {
        int i = start;
        while (i < query.length() && " ]}".indexOf(query.charAt(i)) < 0) {
            i++;
        }
        return i;
    }

    /** A term, a term with wildcards, {@code *}, an operator like {@code AND} or {@code filter(}. */
    private void term() {
        int i = pos;
        boolean wildcard = false;
        boolean escaped = false;
        while (i < query.length()) {
            char c = query.charAt(i);
            if (c == '\\') {
                if (i + 1 >= query.length()) {
                    throw new SearchException("Die Suche darf nicht mit \\ enden.");
                }
                escaped = true;
                i += 2;
            } else if (c == '*' || c == '?') {
                wildcard = true;
                i++;
            } else if (isTermStart(c) || i > pos && isTermPart(c)) {
                i++;
            } else {
                break;
            }
        }
        String text = query.substring(pos, i);
        if ("filter".equals(text) && i < query.length() && query.charAt(i) == '(') {
            add(Kind.OPEN, i + 1);
        } else if (List.of("AND", "OR", "NOT", "&&", "||").contains(text)) {
            add(Kind.OPERATOR, i);
        } else if ("*".equals(text)) {
            add(Kind.STAR, i);
        } else if (wildcard) {
            add(Kind.WILDCARD, i);
        } else {
            tokens.add(new Token(Kind.TERM, text, escaped));
            pos = i;
        }
    }

    /** Marks terms before a colon as field names and checks them. */
    private void markFields() {
        int previous = -1;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.kind() == Kind.SKIP) {
                continue;
            }
            if (token.kind() == Kind.COLON) {
                Token name = previous < 0 ? null : tokens.get(previous);
                if (name == null || name.kind() != Kind.TERM && name.kind() != Kind.STAR) {
                    throw new SearchException("Vor einem Doppelpunkt muss in der Suche ein Feldname stehen. "
                        + "Ein Doppelpunkt im Suchbegriff wird mit \\: geschrieben.");
                }
                checkField(name, i);
                tokens.set(previous, new Token(Kind.FIELD, name.text()));
            }
            previous = i;
        }
    }

    private void checkField(Token name, int colon) {
        if (name.escaped()) {
            throw new SearchException("Feldnamen dürfen in der Suche keinen \\ enthalten: „" + name.text() + "“.");
        }
        if (name.text().startsWith("_")) {
            throw new SearchException("Das Feld „" + name.text() + "“ ist intern und in der Suche nicht erlaubt.");
        }
        int value = next(tokens, colon);
        if (value == tokens.size()) {
            throw new SearchException("Nach dem Feld „" + name.text() + "“ fehlt in der Suche der Suchbegriff.");
        }
        if (name.kind() == Kind.STAR && tokens.get(value).kind() != Kind.STAR) {
            throw new SearchException("Das Feld * ist in der Suche nur als *:* erlaubt.");
        }
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '　';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isTermStart(char c) {
        return !isWhitespace(c) && "+-!():^[]\"{}~*?\\/".indexOf(c) < 0;
    }

    private static boolean isTermPart(char c) {
        return c == '-' || c == '+' || c == '/' || c == '!';
    }

    /**
     * The value of a field: the first token after the colon that is not whitespace or a comment.
     *
     * @param tokens the tokens of a query
     * @param field index of a {@link Kind#FIELD} token
     * @return index of the value token
     */
    public static int value(List<Token> tokens, int field) {
        int colon = next(tokens, field);
        return next(tokens, colon);
    }

    /**
     * The next token that is not whitespace or a comment.
     *
     * @param tokens the tokens of a query
     * @param index start, exclusive
     * @return the index, {@code tokens.size()} if there is none
     */
    public static int next(List<Token> tokens, int index) {
        int i = index + 1;
        while (i < tokens.size() && tokens.get(i).kind() == Kind.SKIP) {
            i++;
        }
        return i;
    }

    /** The kinds of tokens. */
    public enum Kind {
        /** Whitespace or a comment. */
        SKIP,
        /** A field name, followed by a {@link #COLON} after optional whitespace. */
        FIELD,
        /** The colon after a field name. */
        COLON,
        /** A term without wildcards, may contain escapes. */
        TERM,
        /** A single {@code *}. */
        STAR,
        /** A term with {@code *} or {@code ?}. */
        WILDCARD,
        /** A phrase in double quotes. */
        QUOTED,
        /** A regular expression in slashes. */
        REGEXP,
        /** A whole range like {@code [a TO b]}. */
        RANGE,
        /** {@code ^} with a number. */
        BOOST,
        /** {@code ~} with an optional number. */
        SLOP,
        /** {@code AND}, {@code OR}, {@code NOT}, {@code &&}, {@code ||}, {@code +}, {@code -} or {@code !}. */
        OPERATOR,
        /** {@code (} or {@code filter(}. */
        OPEN,
        /** {@code )}. */
        CLOSE
    }

    /**
     * A token.
     *
     * @param kind the kind
     * @param text the text as in the query
     * @param escaped whether the text contains a backslash escape, only set for terms
     */
    public record Token(Kind kind, String text, boolean escaped) {

        /**
         * Creates a token without escapes.
         *
         * @param kind the kind
         * @param text the text
         */
        public Token(Kind kind, String text) {
            this(kind, text, false);
        }

        /**
         * The text as Solr reads it: without the quotes of a phrase and with backslash escapes removed.
         *
         * @return the value
         */
        public String value() {
            String inner = kind == Kind.QUOTED ? text.substring(1, text.length() - 1) : text;
            StringBuilder out = new StringBuilder(inner.length());
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '\\' && i + 1 < inner.length()) {
                    i++;
                    c = inner.charAt(i);
                }
                out.append(c);
            }
            return out.toString();
        }
    }
}
