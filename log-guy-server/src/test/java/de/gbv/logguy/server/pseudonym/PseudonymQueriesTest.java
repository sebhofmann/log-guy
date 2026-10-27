package de.gbv.logguy.server.pseudonym;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import de.gbv.logguy.server.search.SearchException;

class PseudonymQueriesTest {

    private static final Set<String> PROTECTED = Set.of("login", "ip");

    @Test
    void translatesPseudonymsBack() {
        Pseudonyms session = new Pseudonyms();
        session.pseudonym("Nutzer", "mueller");
        session.pseudonym("IP", "10.0.0.1");
        assertEquals("login:\"mueller\" AND level:ERROR",
            PseudonymQueries.query("login:$Nutzer1 AND level:ERROR", PROTECTED, session));
        assertEquals("message:\"from 10.0.0.1 by mueller\"",
            PseudonymQueries.query("message:\"from $IP1 by $Nutzer1\"", PROTECTED, session));
        assertEquals("ip:\"10.0.0.1\"", PseudonymQueries.query("ip:\"$IP1\"", PROTECTED, session));
        assertEquals("$Nutzer7", PseudonymQueries.query("$Nutzer7", PROTECTED, session));
        assertEquals(Map.of("login", List.of("mueller"), "level", List.of("ERROR")),
            PseudonymQueries.filters(Map.of("login", List.of("$Nutzer1"), "level", List.of("ERROR")), PROTECTED,
                session));
    }

    @Test
    void rejectsClearValuesOnProtectedFields() {
        Pseudonyms session = new Pseudonyms();
        session.pseudonym("Nutzer", "mueller");
        assertThrows(SearchException.class, () -> PseudonymQueries.query("login:mueller", PROTECTED, session));
        assertThrows(SearchException.class, () -> PseudonymQueries.query("level:ERROR AND -login : mue*", PROTECTED,
            session));
        assertThrows(SearchException.class, () -> PseudonymQueries.query("login:(a OR b)", PROTECTED, session));
        assertThrows(SearchException.class, () -> PseudonymQueries.query("login:$Nutzer2", PROTECTED, session));
        assertThrows(SearchException.class, () -> PseudonymQueries.query("login:$Nutzer1*", PROTECTED, session));
        assertThrows(SearchException.class,
            () -> PseudonymQueries.filters(Map.of("login", List.of("mueller")), PROTECTED, session));
        assertEquals("message:login", PseudonymQueries.query("message:login", PROTECTED, session));
    }

    /** The cases from the issues #2 and #5: protected fields hidden from the check by escapes or missing spaces. */
    @ParameterizedTest
    @ValueSource(strings = { "lo\\gin:mueller", "logi\\n:mueller", "\\_query_:\"login:mueller\"",
        "(*:*)login:mueller", "\"x\"login:mueller", "level:[A TO Z]login:mueller", "level:{A TO Z}login:mueller",
        "(*:*)_query_:\"{!join from=x to=y}a\"", "a^2login:mueller", "a~2login:mueller", "/x/login:mueller",
        "/* c */login:mueller", "login :mueller", "login\n:mueller", "login /* c */ : mueller", "x\u3000login:mueller",
        "filter(login:mueller)", "+login:mueller", "!login:mueller", "login:\"mueller\"", "login:mu\\eller",
        "login:$Nutzer1~2", "login:$Nutzer1^2~1", "login:$Nutzer1~", "login:/mueller/", "login:[a TO z]",
        "login:filter(mueller)", "login:*", "login:", "ip:$Nutzer1*" })
    void rejectsHiddenClearValues(String query) {
        Pseudonyms session = new Pseudonyms();
        session.pseudonym("Nutzer", "mueller");
        assertThrows(SearchException.class, () -> PseudonymQueries.query(query, PROTECTED, session));
    }

    @Test
    void acceptsPseudonymsInEveryForm() {
        Pseudonyms session = new Pseudonyms();
        session.pseudonym("Nutzer", "mue\"ller");
        assertEquals("(*:*)login:\"mue\\\"ller\"", PseudonymQueries.query("(*:*)login:$Nutzer1", PROTECTED, session));
        assertEquals("login : \"mue\\\"ller\"^2", PseudonymQueries.query("login : \"$Nutzer1\"^2", PROTECTED,
            session));
        assertEquals("login:\"mue\\\"ller\"", PseudonymQueries.query("login:$Nutz\\er1", PROTECTED, session));
        // pseudonyms in a phrase or as a whole term of another field, but not inside other terms
        assertEquals("message:\"by mue\\\"ller\" \"mue\\\"ller\" x$Nutzer1",
            PseudonymQueries.query("message:\"by $Nutzer1\" $Nutzer1 x$Nutzer1", PROTECTED, session));
    }
}
