package de.gbv.logguy.server.pseudonym;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

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
}
