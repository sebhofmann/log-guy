package de.gbv.logguy.server.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import de.gbv.logguy.server.search.QueryLexer.Kind;
import de.gbv.logguy.server.search.QueryLexer.Token;

class QueryTranslatorTest {

    private static final Map<String, String> FIELDS = Map.of("level", "level_s", "import_id", "import_id_s",
        "message", "message", "ts", "ts", "logger", "logger_s", "login", "login_s");

    private static String translate(String query) {
        return QueryTranslator.translate(query, FIELDS);
    }

    @Test
    void mapsDeclaredFields() {
        assertEquals("message:(connection AND refused) AND import_id_s:4711",
            translate("message:(connection AND refused) AND import_id:4711"));
        assertEquals("(level_s:ERROR OR level_s:WARN) -logger_s:Foo",
            translate("(level:ERROR OR level:WARN) -logger:Foo"));
        assertEquals("*:*", translate(" "));
        assertEquals("*:*", translate("*:*"));
        assertEquals("(*:*) AND level_s:ERROR", translate("(*:*) AND level:ERROR"));
    }

    @Test
    void mapsFieldNamesWithoutWhitespaceBefore() {
        // Solr reads a field name after any token, not only after whitespace
        assertEquals("(*:*)login_s:x", translate("(*:*)login:x"));
        assertEquals("\"x\"login_s:x", translate("\"x\"login:x"));
        assertEquals("level_s:[A TO Z]login_s:x", translate("level:[A TO Z]login:x"));
        assertEquals("level_s:{A TO Z}login_s:x", translate("level:{A TO Z}login:x"));
        assertEquals("a^2login_s:x", translate("a^2login:x"));
        assertEquals("a~login_s:x a~2login_s:x", translate("a~login:x a~2login:x"));
        assertEquals("/x/login_s:x", translate("/x/login:x"));
        assertEquals("/* c */login_s:x", translate("/* c */login:x"));
        assertEquals("login_s :x login_s\n: x", translate("login :x login\n: x"));
        assertEquals("x\u3000login_s:x", translate("x\u3000login:x"));
        assertEquals("filter(login_s:x)", translate("filter(login:x)"));
    }

    @Test
    void leavesTermsQuotesAndRangesAlone() {
        assertEquals("refused \"level:ERROR\" foo\\:bar", translate("refused \"level:ERROR\" foo\\:bar"));
        assertEquals("ts:[2026-10-23T10:00:00Z TO *]", translate("ts:[2026-10-23T10:00:00Z TO *]"));
        assertEquals("ts:{2026-10-23T10:00:00Z TO NOW}", translate("ts:{2026-10-23T10:00:00Z TO NOW}"));
        assertEquals("message:/a:b/", translate("message:/a:b/"));
        assertEquals("message:/a\\/login:b/", translate("message:/a\\/login:b/"));
        assertEquals("level_s:[A TO \"]login:x\"]", translate("level:[A TO \"]login:x\"]"));
        assertEquals("x /* login:y /* nested: */ still: */ z", translate("x /* login:y /* nested: */ still: */ z"));
        assertEquals("message:\"x\\\" login:y\"", translate("message:\"x\\\" login:y\""));
        assertEquals("a/b c-d e!f a/*b", translate("a/b c-d e!f a/*b"));
    }

    /** All cases from the issues #2 and #5 and further ways Solr reads a field name the old translator missed. */
    @ParameterizedTest
    @ValueSource(strings = {
        // escaped field names (#2)
        "lo\\gin_s:mueller", "logi\\n:mueller", "\\_query_:\"login_s:mueller\"", "lev\\el_s:ERROR",
        "lo\\gin:mueller", "login\\ :mueller",
        // field names without whitespace before (#5)
        "(*:*)login_s:mueller", "\"x\"login_s:mueller", "level:[A TO Z]login_s:mueller",
        "(*:*)_query_:\"{!join from=x to=y}a\"",
        // after a range with } or a quoted bound, a boost, a slop, a regular expression or a comment
        "level:{A TO Z}login_s:mueller", "level:[A TO \"]\"]login_s:mueller", "level:[A TO \"a\\\"]login_s:mueller",
        "a^2login_s:mueller", "a^=2.5login_s:mueller", "(a)^2login_s:mueller", "level:[A TO Z]^2login_s:mueller",
        "a~login_s:mueller", "a~2login_s:mueller", "\"a\"~2login_s:mueller", "/x/login_s:mueller",
        "/* c */login_s:mueller", "/* a /* b */ c */login_s:mueller",
        // whitespace or comments between name and colon, ideographic space as separator
        "login_s :mueller", "login_s\n:mueller", "login_s\t:\tmueller", "login_s /* c */ :mueller",
        "x\u3000login_s:mueller",
        // operators and groups directly before the name
        "+login_s:mueller", "!login_s:mueller", "-login_s:mueller", "filter(login_s:mueller)",
        "x AND(login_s:mueller)",
        // names Solr reads as one term, they are not declared
        "a-login_s:mueller", "x\u00a0login_s:mueller", "x\u2003login_s:mueller", "x&&login_s:mueller",
        "a/login:mueller", "a!login:mueller" })
    void rejectsUndeclaredOrEscapedFieldNames(String query) {
        assertThrows(SearchException.class, () -> translate(query));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // internal and magic fields of Solr
        "_query_:\"{!join from=a to=b}x\"", "_val_:\"recip(1,2,3,4)\"", "_version_:1", "_root_:x",
        // local parameters
        "{!join from=a to=b}x", "foo {!lucene}x", "x {!term f=login_s}mueller", "(*:*){!term f=login_s}mueller",
        // field * only in *:*
        "*:mueller", "*:(a)",
        // colons without field name
        "\"x\":y", "(a):b", "a::b", ":x", "login:x:y", "AND:x", "lo*:x", "lo?in:x",
        // lexical errors of Solr
        "message:\"open", "/open", "//", "/* open", "x]", "x}", "level:[A TO B", "a^", "a^x", "x\\", "login:" })
    void rejectsInternalFieldsLocalParamsAndInvalidSyntax(String query) {
        assertThrows(SearchException.class, () -> translate(query));
    }

    @Test
    void splitsLikeSolr() {
        assertEquals(List.of(new Token(Kind.OPEN, "("), new Token(Kind.FIELD, "*"), new Token(Kind.COLON, ":"),
            new Token(Kind.STAR, "*"), new Token(Kind.CLOSE, ")"), new Token(Kind.FIELD, "login"),
            new Token(Kind.COLON, ":"), new Token(Kind.TERM, "x")), QueryLexer.tokenize("(*:*)login:x"));
        assertEquals(List.of(Kind.REGEXP, Kind.SKIP, Kind.TERM), kinds("/a\\/b/ c"));
        assertEquals(List.of(Kind.REGEXP), kinds("/a\\/b\\/"));
        assertEquals(List.of(Kind.FIELD, Kind.COLON, Kind.RANGE, Kind.FIELD, Kind.COLON, Kind.TERM),
            kinds("level:[A TO \"a\\\"]login:x"));
        assertEquals(List.of(Kind.FIELD, Kind.COLON, Kind.RANGE), kinds("level:[A TO \"a\\\"]login:x\"]"));
        assertEquals(List.of(Kind.TERM, Kind.BOOST, Kind.SLOP, Kind.SKIP, Kind.OPERATOR, Kind.SKIP, Kind.WILDCARD),
            kinds("a^=1.5~2 AND b*"));
        assertEquals("mueller", QueryLexer.tokenize("\"mu\\eller\"").getFirst().value());
    }

    private static List<Kind> kinds(String query) {
        return QueryLexer.tokenize(query).stream().map(Token::kind).toList();
    }
}
